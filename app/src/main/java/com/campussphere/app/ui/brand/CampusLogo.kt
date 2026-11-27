package com.campussphere.app.ui.brand

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campussphere.app.ui.theme.Brand
import kotlin.math.cos
import kotlin.math.sin

/** Colours of the mark; [onDark] uses the brighter versions made for dark backgrounds. */
data class LogoColors(val sphere: Color, val orbit: Color, val page: Color, val pageShade: Color, val lines: Color) {
    companion object {
        val Light = LogoColors(Brand.Blue, Brand.Amber, Color.White, Color(0xFFE0E7FF), Color(0xFF93C5FD))
        val OnDark = LogoColors(Brand.BlueBright, Brand.AmberBright, Color.White, Color(0xFFDBEAFE), Color(0xFF93C5FD))
    }
}

/**
 * The CampusSphere mark: a sphere (the campus) with an open book (learning), circled by an orbit that stays
 * open on the right like a "C", carrying three dots (students connecting). Drawn, so it is sharp at any size.
 * With [animate], the orbit draws itself, the book fades in and the front dot keeps gently gliding.
 */
@Composable
fun CampusLogo(modifier: Modifier = Modifier, size: Dp = 96.dp, colors: LogoColors = LogoColors.Light, animate: Boolean = false) {
    val draw = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) { if (animate) draw.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val glide = if (animate) {
        rememberInfiniteTransition(label = "orbit").animateFloat(
            initialValue = -7f, targetValue = 7f,
            animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dot",
        ).value
    } else 0f
    Canvas(modifier.size(size).semantics { contentDescription = "CampusSphere logo" }) {
        val unit = this.size.minDimension / 220f
        translate(this.size.width / 2 - 130f * unit, this.size.height / 2 - 140f * unit) {
            scale(unit, unit, pivot = Offset.Zero) { drawMark(colors, draw.value, glide) }
        }
    }
}

private val Orbit = Rect(130f - 92f, 140f - 30f, 130f + 92f, 140f + 30f)
private val Center = Offset(130f, 140f)

private fun orbitPoint(degrees: Float): Offset {
    val a = Math.toRadians(degrees.toDouble())
    return Offset(130f + 92f * cos(a).toFloat(), 140f + 30f * sin(a).toFloat())
}

private fun DrawScope.drawMark(c: LogoColors, progress: Float, glide: Float) {
    val ring = Stroke(width = 7f, cap = StrokeCap.Round)
    // Back half of the orbit (behind the sphere), from the left edge over the top towards the opening.
    rotate(-18f, Center) {
        drawPath(Path().apply { arcTo(Orbit, 180f, 155f * progress, true) }, c.orbit, style = ring)
        if (progress > 0.35f) drawCircle(c.orbit, 7f * minOf(1f, (progress - 0.35f) * 3f), orbitPoint(200f))
        if (progress > 0.8f) drawCircle(c.orbit, 7f * minOf(1f, (progress - 0.8f) * 5f), orbitPoint(325f))
    }
    drawCircle(c.sphere, 58f, Center)
    val alpha = ((progress - 0.25f) / 0.6f).coerceIn(0f, 1f)
    val left = Path().apply {
        moveTo(130f, 150f); cubicTo(116f, 141f, 100f, 140f, 88f, 143f); lineTo(88f, 104f)
        cubicTo(100f, 101f, 116f, 102f, 130f, 111f); close()
    }
    val right = Path().apply {
        moveTo(130f, 150f); cubicTo(144f, 141f, 160f, 140f, 172f, 143f); lineTo(172f, 104f)
        cubicTo(160f, 101f, 144f, 102f, 130f, 111f); close()
    }
    drawPath(left, c.page, alpha = alpha)
    drawPath(right, c.pageShade, alpha = alpha)
    val line = Stroke(width = 2f, cap = StrokeCap.Round)
    for (dy in listOf(0f, 11f)) {
        drawPath(Path().apply { moveTo(98f, 116f + dy); cubicTo(108f, 114f + dy, 117f, 116f + dy, 124f, 120f + dy) }, c.lines, alpha = alpha, style = line)
        drawPath(Path().apply { moveTo(136f, 120f + dy); cubicTo(143f, 116f + dy, 152f, 114f + dy, 162f, 116f + dy) }, c.lines, alpha = alpha, style = line)
    }
    // Front half of the orbit (over the sphere), from the left edge under the bottom towards the opening.
    rotate(-18f, Center) {
        drawPath(Path().apply { arcTo(Orbit, 180f, -140f * progress, true) }, c.orbit, style = ring)
        if (progress > 0.6f) drawCircle(c.orbit, 7f * minOf(1f, (progress - 0.6f) * 4f), orbitPoint(70f + glide))
    }
}

/** "Campus" bold in navy and "Sphere" lighter in blue; brighter shades on dark backgrounds. */
@Composable
fun CampusWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 28.sp, onDark: Boolean = false) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (onDark) Color.White else Brand.Navy)) { append("Campus") }
        withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = if (onDark) Brand.BlueSoft else Brand.BlueBright)) { append("Sphere") }
    }, modifier, fontSize = fontSize, letterSpacing = (-0.3).sp)
}

/** The mark and the wordmark side by side, for headers. */
@Composable
fun CampusBrandRow(modifier: Modifier = Modifier, logoSize: Dp = 40.dp, fontSize: TextUnit = 20.sp, onDark: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CampusLogo(size = logoSize, colors = if (onDark) LogoColors.OnDark else LogoColors.Light)
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        CampusWordmark(fontSize = fontSize, onDark = onDark)
    }
}
