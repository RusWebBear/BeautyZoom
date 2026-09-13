package com.example

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ui.CameraScreen
import com.example.ui.EditorScreen
import com.example.ui.EditorViewModel
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val editorViewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    BeautyZoomApp(viewModel = editorViewModel)
                }
            }
        }
    }
}

@Composable
fun BeautyZoomApp(viewModel: EditorViewModel) {
    var hasActivePhoto by remember { mutableStateOf(false) }

    if (!hasActivePhoto) {
        CameraScreen(
            onImageSelected = { bitmap ->
                viewModel.setImage(bitmap)
                hasActivePhoto = true
            }
        )
    } else {
        EditorScreen(
            viewModel = viewModel,
            onBack = {
                hasActivePhoto = false
            }
        )
    }
}
