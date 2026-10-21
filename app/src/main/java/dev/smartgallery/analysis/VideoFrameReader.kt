package dev.smartgallery.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.ensureActive

data class DecodedVideoFrame(val bitmap: Bitmap, val timestampMs: Long)

/** Decode real presentation timestamps. Some OEM retrievers return a poster for every seek. */
class VideoFrameReader(context: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    val durationMs: Long
    private var rotation = 0

    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: error("No supported video track")
            val format = extractor.getTrackFormat(track)
            rotation =
                if (format.containsKey(MediaFormat.KEY_ROTATION))
                    format.getInteger(MediaFormat.KEY_ROTATION)
                else 0
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216) {
                "Video resolution exceeds the decoder budget"
            }
            durationMs =
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) / 1000
                else 0
            extractor.selectTrack(track)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec!!.configure(format, null, null, 0)
            codec!!.start()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    suspend fun frameAt(timestampMs: Long, maxEdge: Int = 1280): DecodedVideoFrame {
        val decoder = checkNotNull(codec)
        val targetUs = timestampMs.coerceAtLeast(0) * 1000
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var candidate: DecodedVideoFrame? = null
        val deadline = SystemClock.elapsedRealtime() + 15_000
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val input = decoder.dequeueInputBuffer(5_000)
                    if (input >= 0) {
                        val buffer = decoder.getInputBuffer(input)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                input,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 5_000)
                if (output >= 0) {
                    var ready: DecodedVideoFrame? = null
                    try {
                        if (info.size > 0) {
                            val image =
                                decoder.getOutputImage(output)
                                    ?: error("Decoder does not expose supported YUV images")
                            image.use {
                                // Keep only one bounded candidate; timestamps come from decoder
                                // output.
                                if (
                                    info.presentationTimeUs >= targetUs - 1_000_000 ||
                                        candidate == null
                                ) {
                                    val bitmap = rgb(image, maxEdge, decoder.outputFormat)
                                    candidate?.bitmap?.recycle()
                                    candidate =
                                        DecodedVideoFrame(bitmap, info.presentationTimeUs / 1000)
                                }
                                if (info.presentationTimeUs >= targetUs) ready = candidate
                            }
                        }
                    } finally {
                        decoder.releaseOutputBuffer(output, false)
                    }
                    if (ready != null) {
                        candidate = null
                        return ready!!
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        val last = candidate ?: error("No decoded video frame")
                        candidate = null
                        return last
                    }
                }
            }
            error("Video decoder exceeded its bounded frame deadline")
        } finally {
            candidate?.bitmap?.recycle()
        }
    }

    override fun close() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        runCatching { extractor.release() }
    }

    private fun rgb(image: Image, maxEdge: Int, format: MediaFormat): Bitmap {
        require(image.planes.size == 3)
        val crop = image.cropRect
        val ratio = min(1.0, maxEdge.toDouble() / max(crop.width(), crop.height()))
        val w = max(1, (crop.width() * ratio).toInt())
        val h = max(1, (crop.height() * ratio).toInt())
        val pixels = IntArray(w * h)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val full =
            format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
                format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        val hd =
            format.containsKey(MediaFormat.KEY_COLOR_STANDARD) &&
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD) ==
                    MediaFormat.COLOR_STANDARD_BT709
        for (row in 0 until h) {
            val sy = crop.top + row * crop.height() / h
            for (col in 0 until w) {
                val sx = crop.left + col * crop.width() / w
                val yy = at(y.buffer, y.rowStride, y.pixelStride, sx, sy)
                val uu = at(u.buffer, u.rowStride, u.pixelStride, sx / 2, sy / 2) - 128
                val vv = at(v.buffer, v.rowStride, v.pixelStride, sx / 2, sy / 2) - 128
                val luminance = if (full) yy.toDouble() else (yy - 16) * 1.164
                val r = (luminance + (if (hd) 1.793 else 1.596) * vv).toInt().coerceIn(0, 255)
                val g =
                    (luminance - (if (hd) .213 else .392) * uu - (if (hd) .533 else .813) * vv)
                        .toInt()
                        .coerceIn(0, 255)
                val b = (luminance + (if (hd) 2.112 else 2.017) * uu).toInt().coerceIn(0, 255)
                pixels[row * w + col] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (rotation % 360 == 0) return bitmap
        return Bitmap.createBitmap(
                bitmap,
                0,
                0,
                w,
                h,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun at(buffer: ByteBuffer, row: Int, pixel: Int, x: Int, y: Int) =
        buffer.get(buffer.position() + y * row + x * pixel).toInt() and 255
}
