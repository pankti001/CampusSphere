package com.campussphere.app.ui.brand

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.campussphere.app.ui.theme.Brand
import kotlinx.coroutines.delay

/**
 * Fades and lifts content into place the first time it appears. [order] staggers items in a list
 * (each one 60 ms after the previous), so a dashboard builds up smoothly instead of popping in at once.
 */
fun Modifier.appear(order: Int = 0): Modifier = this.composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order.coerceIn(0, 10) * 60L)
        progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * lift
    }
}

/** Switches between pages with a short fade and lift, so moving around the app feels smooth. */
@Composable
fun <T> ScreenSwitch(target: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = target, modifier = modifier, label = "screen",
        transitionSpec = {
            (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(300)) { it / 24 } +
                scaleIn(tween(300), initialScale = 0.985f)) togetherWith fadeOut(tween(160))
        },
    ) { content(it) }
}

/** The navy-to-blue brand card with a faint orbit drawn across it, for the top of dashboards. */
@Composable
fun BrandHero(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Brand.Midnight, Brand.Navy, Brand.Blue)))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val ring = Stroke(width = 3.dp.toPx())
            // Kept to the right half, so it never crosses the text on the left.
            drawOval(Brand.AmberBright.copy(alpha = 0.35f), Offset(size.width * 0.6f, -size.height * 0.3f),
                Size(size.width * 0.7f, size.height * 0.7f), style = ring)
            drawCircle(Brand.AmberBright.copy(alpha = 0.55f), 5.dp.toPx(), Offset(size.width * 0.66f, size.height * 0.2f))
            drawCircle(Color.White.copy(alpha = 0.06f), size.height * 0.55f, Offset(size.width * 0.95f, size.height * 0.95f))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** A small round tinted badge holding an emoji or short symbol, used to mark sections. */
@Composable
fun IconBadge(symbol: String, tint: Color = MaterialTheme.colorScheme.primaryContainer, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
        Text(symbol, style = MaterialTheme.typography.titleMedium)
    }
}

/** A dashboard shortcut: tinted symbol, title and one line about it. Fades in with [order]. */
@Composable
fun ActionTile(symbol: String, title: String, subtitle: String, modifier: Modifier = Modifier, order: Int = 0,
               tint: Color = MaterialTheme.colorScheme.primaryContainer, enabled: Boolean = true, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier.appear(order), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBadge(symbol, tint)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, minLines = 2)
        }
    }
}

/** A friendly empty state: the logo, a short title and what to do next. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp).appear(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CampusLogo(size = 72.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        action?.invoke()
    }
}
