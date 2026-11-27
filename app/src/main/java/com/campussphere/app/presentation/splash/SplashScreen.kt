package com.campussphere.app.presentation.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campussphere.app.ui.brand.CampusLogo
import com.campussphere.app.ui.brand.CampusWordmark
import com.campussphere.app.ui.brand.LogoColors
import com.campussphere.app.ui.theme.Brand

/** Shown while the saved session is checked: the logo springs in and its orbit draws itself. */
@Composable
fun SplashScreen() {
    val pop = remember { Animatable(0.6f) }
    val words = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) { words.animateTo(1f, tween(700, delayMillis = 450)) }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Brand.Midnight, Brand.Navy, Brand.Blue))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CampusLogo(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, size = 170.dp,
                colors = LogoColors.OnDark, animate = true)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.graphicsLayer { alpha = words.value; translationY = (1 - words.value) * 30f },
                horizontalAlignment = Alignment.CenterHorizontally) {
                CampusWordmark(fontSize = 36.sp, onDark = true)
                Spacer(Modifier.height(8.dp))
                Text("Your campus. Your community.", fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }
        Text("LEARN  ·  CONNECT  ·  GROW", Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
            .graphicsLayer { alpha = words.value },
            fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium, color = Brand.AmberBright)
    }
}
