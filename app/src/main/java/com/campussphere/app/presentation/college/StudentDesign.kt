package com.campussphere.app.presentation.college

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object StudentColors {
    val Navy = Color(0xFF1E3A8A)
    val Indigo = Color(0xFF1D4ED8)
    val Ink = Color(0xFF0F172A)
    val Muted = Color(0xFF475569)
    val Canvas = Color(0xFFF6F8FD)
    val Paper = Color.White
    val Line = Color(0xFFD5DEEC)
    val FieldOutline = Color(0xFF818DAA)
    val Tint = Color(0xFFDBE6FF)
    val OnHero = Color(0xFFDBE6FF)
    val Green = Color(0xFF215C49)
    val GreenTint = Color(0xFFE8F6EF)
    val Amber = Color(0xFF78521A)
    val AmberTint = Color(0xFFFFF3DC)
    val Red = Color(0xFF932D42)
    val RedTint = Color(0xFFFFEEF1)
}

private val studentScheme = lightColorScheme(
    primary = StudentColors.Indigo,
    onPrimary = Color.White,
    primaryContainer = StudentColors.Tint,
    onPrimaryContainer = StudentColors.Indigo,
    secondary = StudentColors.Indigo,
    onSecondary = Color.White,
    background = StudentColors.Canvas,
    onBackground = StudentColors.Ink,
    surface = StudentColors.Paper,
    onSurface = StudentColors.Ink,
    onSurfaceVariant = StudentColors.Muted,
    surfaceVariant = StudentColors.Tint,
    outline = StudentColors.FieldOutline,
    outlineVariant = StudentColors.Line,
    error = StudentColors.Red,
    errorContainer = StudentColors.RedTint,
    onErrorContainer = StudentColors.Red
)

@Composable
internal fun StudentWorkspaceTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    MaterialTheme(
        colorScheme = studentScheme,
        typography = base.copy(
            headlineMedium = base.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp, lineHeight = 35.sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
            bodyLarge = base.bodyLarge.copy(letterSpacing = 0.sp),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides StudentColors.Ink, content = content)
    }
}

internal enum class StudentMark { Back, Next, Search, Refresh, College, Person, Study, Calendar, List, Check, Clock, Alert, Exit }

@Composable
internal fun StudentIcon(mark: StudentMark, tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        fun at(x: Float, y: Float) = Offset(size.width * x / 24, size.height * y / 24)
        val thickness = size.minDimension * 0.079f
        fun line(x: Float, y: Float, endX: Float, endY: Float) {
            drawLine(tint, at(x, y), at(endX, endY), thickness, StrokeCap.Round)
        }
        fun ring(x: Float, y: Float, radius: Float) {
            drawCircle(tint, size.minDimension * radius / 24, at(x, y), style = Stroke(thickness))
        }
        when (mark) {
            StudentMark.Back -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            StudentMark.Next -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            StudentMark.Search -> { ring(10f, 10f, 5.5f); line(14f, 14f, 20f, 20f) }
            StudentMark.Refresh -> {
                drawArc(tint, -45f, 295f, false, at(5f, 5f), Size(size.width * .58f, size.height * .58f),
                    style = Stroke(thickness, cap = StrokeCap.Round))
                line(19f, 5f, 19f, 10f); line(19f, 10f, 14f, 10f)
            }
            StudentMark.College -> {
                line(3f, 9f, 12f, 4f); line(12f, 4f, 21f, 9f); line(3f, 9f, 21f, 9f)
                for (x in listOf(6f, 12f, 18f)) line(x, 12f, x, 18f)
                line(3f, 20f, 21f, 20f)
            }
            StudentMark.Person -> {
                ring(12f, 8f, 3.4f)
                drawArc(tint, 190f, 160f, false, at(5f, 14f), Size(size.width * .58f, size.height * .42f),
                    style = Stroke(thickness, cap = StrokeCap.Round))
            }
            StudentMark.Study -> {
                line(2f, 9f, 12f, 4.5f); line(12f, 4.5f, 22f, 9f); line(22f, 9f, 12f, 13.5f)
                line(12f, 13.5f, 2f, 9f); line(6f, 11f, 6f, 16f); line(6f, 16f, 12f, 19f)
                line(12f, 19f, 18f, 16f); line(18f, 16f, 18f, 11f); line(22f, 9f, 22f, 15f)
            }
            StudentMark.Calendar -> {
                line(4f, 6f, 20f, 6f); line(20f, 6f, 20f, 20f); line(20f, 20f, 4f, 20f)
                line(4f, 20f, 4f, 6f); line(4f, 10f, 20f, 10f)
                line(8f, 4f, 8f, 7f); line(16f, 4f, 16f, 7f)
            }
            StudentMark.List -> for (y in listOf(6f, 12f, 18f)) {
                line(4f, y, 6f, y); line(10f, y, 20f, y)
            }
            StudentMark.Check -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 19f, 7f) }
            StudentMark.Clock -> { ring(12f, 12f, 8f); line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f) }
            StudentMark.Alert -> { ring(12f, 12f, 8f); line(12f, 7f, 12f, 12f); line(12f, 16f, 12f, 16.2f) }
            StudentMark.Exit -> {
                line(11f, 5f, 5f, 5f); line(5f, 5f, 5f, 19f); line(5f, 19f, 11f, 19f)
                line(9f, 12f, 20f, 12f); line(16f, 8f, 20f, 12f); line(20f, 12f, 16f, 16f)
            }
        }
    }
}

@Composable
internal fun StudentHero(page: StudentPage, mode: StudentMode, busy: Boolean, onBack: () -> Unit) {
    val step = when (page) {
        StudentPage.PROGRAM -> 1
        StudentPage.YEAR -> 2
        StudentPage.SEMESTER -> 3
        StudentPage.DIVISION -> 4
        StudentPage.REVIEW -> 5
        else -> null
    }
    val purpose = when (page) {
        StudentPage.DIRECTORY -> "Find students and manage their academic enrollment."
        StudentPage.QUEUE -> "Review requests from students who want to join your college."
        StudentPage.PROFILE -> null
        StudentPage.DEPARTMENT -> "Set the department before choosing an academic program."
        StudentPage.PROGRAM -> "Select a program in the student's department."
        StudentPage.YEAR -> "Select the academic year for this enrollment."
        StudentPage.SEMESTER -> "Select a semester in the chosen program and year."
        StudentPage.DIVISION -> "Select the student's class division."
        StudentPage.REVIEW -> "Confirm the academic details before saving."
        StudentPage.DASHBOARD -> null
        StudentPage.REQUEST -> "Ask your college to confirm your admission."
    }
    val background = Brush.horizontalGradient(listOf(StudentColors.Navy, Color(0xFF2D3F83)))
    Box(Modifier.fillMaxWidth().clipToBounds().background(background), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.matchParentSize()) {
            val radius = 96.dp.toPx()
            val center = Offset(size.width - 20.dp.toPx(), size.height + 20.dp.toPx())
            repeat(4) { index ->
                drawCircle(Color.White.copy(alpha = .075f), radius + index * 27.dp.toPx(), center,
                    style = Stroke(1.dp.toPx()))
            }
        }
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onBack,
                    enabled = !busy || mode == StudentMode.STUDENT,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White,
                        disabledContentColor = StudentColors.OnHero),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)
                ) {
                    val signOut = mode == StudentMode.STUDENT
                    StudentIcon(if (signOut) StudentMark.Exit else StudentMark.Back, StudentColors.OnHero)
                    Spacer(Modifier.width(7.dp))
                    Text(if (signOut) "Sign out" else "Back")
                }
                Spacer(Modifier.weight(1f))
                StudentIcon(StudentMark.College, StudentColors.OnHero, Modifier.size(18.dp))
                Text("CampusSphere", color = StudentColors.OnHero, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(13.dp))
            Text(page.title, color = Color.White, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            purpose?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = StudentColors.OnHero, style = MaterialTheme.typography.bodyMedium)
            }
            if (step != null) {
                Spacer(Modifier.height(14.dp))
                Text("Enrollment · Step $step of 5", color = StudentColors.OnHero, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(5) { index ->
                        Box(Modifier.weight(1f).height(3.dp)
                            .background(if (index < step) StudentColors.OnHero else Color.White.copy(alpha = .20f),
                                CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
internal fun StudentPanel(
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = CardDefaults.cardColors(containerColor = StudentColors.Paper, contentColor = StudentColors.Ink)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val border = BorderStroke(1.dp, StudentColors.Line)
    if (onClick == null) {
        Card(Modifier.fillMaxWidth(), shape, colors, elevation, border, content)
    } else {
        Card(onClick, Modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation,
            border = border, content = content)
    }
}

@Composable
internal fun StudentTile(title: String, detail: String, mark: StudentMark, initials: String? = null, onClick: () -> Unit) {
    StudentPanel(onClick) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            StudentIconTile(mark, initials)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = StudentColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            StudentIcon(StudentMark.Next, StudentColors.Indigo)
        }
    }
}

@Composable
internal fun StudentIconTile(mark: StudentMark, initials: String? = null) {
    Box(Modifier.size(44.dp).background(StudentColors.Tint, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center) {
        if (initials.isNullOrBlank()) StudentIcon(mark, StudentColors.Indigo, Modifier.size(23.dp))
        else Text(initials, color = StudentColors.Indigo, style = MaterialTheme.typography.labelLarge)
    }
}

internal fun studentInitials(name: String) =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }

@Composable
internal fun StudentPill(label: String, background: Color, foreground: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = background, contentColor = foreground) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun StudentField(value: String, onValue: (String) -> Unit, label: String, mark: StudentMark) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, shape = RoundedCornerShape(13.dp),
        leadingIcon = { StudentIcon(mark, StudentColors.Indigo) })
}

@Composable
internal fun StudentEmpty(title: String, detail: String, mark: StudentMark) {
    StudentPanel {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudentIconTile(mark)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = StudentColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun StudentNotice(message: String, success: Boolean, action: String? = null, onAction: () -> Unit = {}) {
    val ink = if (success) StudentColors.Green else StudentColors.Red
    val background = if (success) StudentColors.GreenTint else StudentColors.RedTint
    Surface(shape = RoundedCornerShape(16.dp), color = background, contentColor = ink) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StudentIcon(if (success) StudentMark.Check else StudentMark.Alert, ink, Modifier.size(21.dp))
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                action?.let {
                    TextButton(onAction, colors = ButtonDefaults.textButtonColors(contentColor = ink)) { Text(it) }
                }
            }
        }
    }
}

@Composable
internal fun StudentRefresh(onClick: () -> Unit) {
    TextButton(onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)) {
        StudentIcon(StudentMark.Refresh, StudentColors.Indigo, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Refresh")
    }
}

@Composable
internal fun StudentSection(title: String, onRefresh: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        onRefresh?.let { StudentRefresh(it) }
    }
}
