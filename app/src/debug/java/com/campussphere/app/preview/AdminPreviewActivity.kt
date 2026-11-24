package com.campussphere.app.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.ui.theme.CampusSphereTheme

class AdminPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        val scrim = 0xFFE9EEF1.toInt()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, scrim),
            navigationBarStyle = SystemBarStyle.light(transparent, scrim)
        )
        setContent {
            CampusSphereTheme(darkTheme = false, dynamicColor = false) {
                CollegeAdminPreview(model = viewModel(), onClose = ::finish)
            }
        }
    }
}
