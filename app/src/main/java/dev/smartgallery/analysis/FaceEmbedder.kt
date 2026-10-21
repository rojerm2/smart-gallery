package dev.smartgallery.analysis

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Qualcomm v0.64.0 export contains RGB normalization and flip/sum augmentation internally. */
class FaceEmbedder(context: Context) : AutoCloseable {
    private val interpreter: Interpreter

    init {
        val bytes = context.assets.open("models/mobile_facenet.tflite").use { it.readBytes() }
        val digest =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        check(digest == SHA256) { "Face model checksum mismatch" }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buffer.rewind()
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(2))
        check(interpreter.inputTensorCount == 2)
        for (i in 0..1) {
            val tensor = interpreter.getInputTensor(i)
            check(tensor.dataType() == DataType.FLOAT32)
            check(tensor.shape().contentEquals(intArrayOf(1, 3, 112, 112)))
        }
        check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(2, 128)))
    }

    @Synchronized
    fun embed(aligned: Bitmap): FloatArray {
        require(aligned.width == 112 && aligned.height == 112)
        val pixels = IntArray(112 * 112)
        aligned.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val input = ByteBuffer.allocateDirect(3 * 112 * 112 * 4).order(ByteOrder.nativeOrder())
        // NCHW, RGB, [0,1]. Do not apply ImageNet normalization twice.
        for (shift in intArrayOf(16, 8, 0)) for (pixel in pixels) input.putFloat(
            ((pixel shr shift) and 255) / 255f
        )
        input.rewind()
        val output = ByteBuffer.allocateDirect(2 * 128 * 4).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(
            arrayOf(input, input.duplicate().order(ByteOrder.nativeOrder())),
            mapOf(0 to output),
        )
        output.rewind()
        val vector = FloatArray(128) { output.float }
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        check(norm.isFinite() && norm > 1e-8f) { "Invalid face embedding" }
        return FloatArray(128) { vector[it] / norm }
    }

    override fun close() = interpreter.close()

    companion object {
        const val VERSION = "qai-mobilefacenet-0.64.0-rgb-nchw-tta-v1"
        const val SHA256 = "2046ab776fba2ec7e420767ffe7eb74a7402a8412d3d7ea8b9f4ca8420e5f3d7"
    }
}
