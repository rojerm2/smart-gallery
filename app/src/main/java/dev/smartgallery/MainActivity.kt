package dev.smartgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.smartgallery.ui.GalleryScreen
import dev.smartgallery.ui.GalleryViewModel

class MainActivity : ComponentActivity() {
    private val model: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GalleryScreen(model) }
    }

    override fun onStart() {
        super.onStart()
        model.refresh()
    }
}
