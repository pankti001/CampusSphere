package com.campussphere.app.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val Paper = Color(0xFFF3F6F8)
internal val Ink = Color(0xFF172B40)
internal val Muted = Color(0xFF465D6E)
internal val Line = Color(0xFFDCE5E9)
internal val Teal = Color(0xFF006F64)
internal val TealTint = Color(0xFFE0F3EE)
internal val BlueTint = Color(0xFFE8EFF9)
internal val Blue = Color(0xFF2E5B95)
internal val GoldTint = Color(0xFFFFF0D6)
internal val Gold = Color(0xFF805420)

private val PreviewScheme = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = TealTint, onPrimaryContainer = Teal,
    background = Paper, onBackground = Ink, surface = Color.White, onSurface = Ink,
    onSurfaceVariant = Muted, outline = Color(0xFF768B96), outlineVariant = Line,
    error = Color(0xFFB3261E)
)

@Composable
internal fun CollegeAdminPreview(model: AdminPreviewViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val back: () -> Unit = when (state.page) {
        PreviewPage.DASHBOARD -> onClose
        PreviewPage.DEPARTMENTS -> model::dashboard
        PreviewPage.ADD, PreviewPage.EDIT -> model::departments
    }
    BackHandler(onBack = back)

    MaterialTheme(colorScheme = PreviewScheme, typography = MaterialTheme.typography) {
        Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            WorkspaceBar(state.page, back)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when {
                    state.isLoading -> LoadingPreview(Modifier.align(Alignment.Center))
                    state.page == PreviewPage.DASHBOARD -> DashboardScreen(
                        count = state.departments.size, onDepartments = model::departments, onAdd = model::add
                    )
                    state.page == PreviewPage.DEPARTMENTS -> DepartmentListScreen(
                        state = state, onSearch = model::search, onAdd = model::add, onEdit = model::edit
                    )
                    else -> DepartmentFormScreen(
                        editing = state.page == PreviewPage.EDIT,
                        draft = state.draft,
                        onName = model::name,
                        onCode = model::code,
                        onCancel = model::departments,
                        onSave = model::save
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBar(page: PreviewPage, onBack: () -> Unit) {
    Surface(color = Color.White) {
        Column {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (page == PreviewPage.DASHBOARD) {
                        Box(
                            Modifier.size(42.dp).background(Teal, RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("C", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                    } else {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "Back" }
                        ) {
                            PreviewIcon(Glyph.BACK, Ink)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("CampusSphere", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("College administration", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Tag("Preview only", GoldTint, Gold)
                    if (page == PreviewPage.DASHBOARD) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(44.dp).semantics { contentDescription = "Close preview" }
                        ) {
                            PreviewIcon(Glyph.CLOSE, Muted)
                        }
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
private fun LoadingPreview(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text("Loading preview data", color = Muted)
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Ink)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
internal fun Tag(text: String, background: Color, foreground: Color) {
    Text(
        text,
        modifier = Modifier.background(background, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun PreviewNote(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(TealTint, RoundedCornerShape(14.dp)).padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PreviewIcon(Glyph.INFO, Teal, Modifier.size(18.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Ink)
    }
}

@Composable
internal fun IconTile(glyph: Glyph, tint: Color, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).background(tint, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        PreviewIcon(glyph, color)
    }
}

internal enum class Glyph { BACK, CLOSE, CHEVRON, PLUS, SEARCH, BUILDING, STUDENTS, FACULTY, INFO, EDIT }

@Composable
internal fun PreviewIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, point(x1, y1), point(x2, y2), strokeWidth = 1.75f * unit, cap = StrokeCap.Round)
        }
        fun circle(x: Float, y: Float, radius: Float) {
            drawCircle(color, radius * unit, point(x, y), style = Stroke(1.7f * unit))
        }
        when (glyph) {
            Glyph.BACK -> {
                line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f)
            }
            Glyph.CLOSE -> {
                line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f)
            }
            Glyph.CHEVRON -> {
                line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f)
            }
            Glyph.PLUS -> {
                line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f)
            }
            Glyph.SEARCH -> {
                circle(10f, 10f, 5.7f); line(14.3f, 14.3f, 20f, 20f)
            }
            Glyph.BUILDING -> {
                line(3f, 10f, 12f, 4f); line(12f, 4f, 21f, 10f); line(3f, 10f, 21f, 10f)
                for (x in listOf(6f, 12f, 18f)) line(x, 12f, x, 18f)
                line(3f, 20f, 21f, 20f)
            }
            Glyph.STUDENTS -> {
                circle(9f, 8f, 3f); circle(17f, 9f, 2.2f)
                line(4f, 18f, 4f, 16f); line(4f, 16f, 6f, 14f)
                line(6f, 14f, 12f, 14f); line(12f, 14f, 14f, 16f); line(14f, 16f, 14f, 18f)
                line(16f, 14f, 19f, 14f); line(19f, 14f, 21f, 16f); line(21f, 16f, 21f, 18f)
            }
            Glyph.FACULTY -> {
                line(3f, 7f, 21f, 7f); line(3f, 7f, 3f, 17f)
                line(3f, 17f, 21f, 17f); line(21f, 17f, 21f, 7f)
                line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f)
                line(7f, 12f, 16f, 12f)
            }
            Glyph.INFO -> {
                circle(12f, 12f, 8.5f)
                drawCircle(color, unit, point(12f, 8f))
                line(12f, 11.6f, 12f, 16f)
            }
            Glyph.EDIT -> {
                line(4f, 20f, 9f, 18f); line(4f, 20f, 6f, 15f)
                line(6f, 15f, 16f, 5f); line(9f, 18f, 19f, 8f); line(16f, 5f, 19f, 8f)
            }
        }
    }
}
