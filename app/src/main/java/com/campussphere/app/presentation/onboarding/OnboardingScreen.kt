package com.campussphere.app.presentation.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campussphere.app.ui.brand.CampusIcons
import com.campussphere.app.ui.brand.CampusLogo
import com.campussphere.app.ui.brand.CampusWordmark
import com.campussphere.app.ui.brand.InfoCard
import com.campussphere.app.ui.brand.PrimaryButton
import com.campussphere.app.ui.brand.appear
import com.campussphere.app.ui.theme.Brand

private data class Feature(val icon: ImageVector, val tint: Color, val title: String, val body: String)

private val FEATURES = listOf(
    Feature(CampusIcons.Books, Color(0xFFDBE6FF), "Learn", "Timetables, study materials and assignments in one place."),
    Feature(CampusIcons.Chat, Color(0xFFFEF3C7), "Connect", "Your class group, study groups and private chats."),
    Feature(CampusIcons.Chart, Color(0xFFCCFBF1), "Grow", "Attendance, marks and feedback as they happen."),
)

/** First screen for a new install: what CampusSphere is, then on to sign in. */
@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Sky, Color.White)))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        CampusLogo(size = 150.dp, animate = true)
        CampusWordmark(Modifier.appear(1), fontSize = 34.sp)
        Text("Your campus. Your community.", Modifier.appear(2), style = MaterialTheme.typography.bodyLarge,
            color = Brand.Slate, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        FEATURES.forEachIndexed { i, f ->
            InfoCard(Modifier.appear(3 + i)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(f.tint), contentAlignment = Alignment.Center) {
                        Icon(f.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = Brand.Navy)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(f.title, style = MaterialTheme.typography.titleMedium, color = Brand.Navy)
                        Text(f.body, style = MaterialTheme.typography.bodyMedium, color = Brand.Slate)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Get started", onClick = onGetStarted, modifier = Modifier.appear(7))
    }
}
