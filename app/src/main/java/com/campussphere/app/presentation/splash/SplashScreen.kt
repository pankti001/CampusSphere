package com.campussphere.app.presentation.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SplashScreen() {

    val primaryBlue = Color(0xFF2563EB)
    val darkBlue = Color(0xFF0F172A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        darkBlue,
                        Color(0xFF1E40AF),
                        primaryBlue
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // App Logo
            Surface(
                modifier = Modifier.size(105.dp),
                shape = RoundedCornerShape(30.dp),
                color = Color.White
            ) {

                Box(
                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        text = "C",
                        fontSize = 65.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(25.dp))

            // App Name
            Text(
                text = "CampusSphere",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Tagline
            Text(
                text = "Your Campus. Your Community.",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
        }

        // Bottom Text
        Text(
            text = "LEARN  •  CONNECT  •  GROW",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 45.dp),
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}