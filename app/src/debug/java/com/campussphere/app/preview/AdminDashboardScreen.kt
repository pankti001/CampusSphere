package com.campussphere.app.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DashboardScreen(count: Int, onDepartments: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.widthIn(max = 780.dp).fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(21.dp)
    ) {
        WelcomeCard()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("College overview", "Sample totals")
            DepartmentMetric(count, onDepartments)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 270.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Students", "1,248", Glyph.STUDENTS, BlueTint, Blue, Modifier.fillMaxWidth())
                        MetricCard("Faculty", "86", Glyph.FACULTY, GoldTint, Gold, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Students", "1,248", Glyph.STUDENTS, BlueTint, Blue, Modifier.weight(1f))
                        MetricCard("Faculty", "86", Glyph.FACULTY, GoldTint, Gold, Modifier.weight(1f))
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Quick actions")
            QuickAction("Manage departments", "Search and edit department details", Glyph.BUILDING, onDepartments)
            QuickAction("Add department", "Set up a name and department code", Glyph.PLUS, onAdd)
        }
        PreviewNote("Sample data for design review. Changes reset when you close the preview.")
    }
}

@Composable
private fun WelcomeCard() {
    val dark = Color(0xFF152C40)
    val bright = Color(0xFF165653)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp))
            .background(Brush.linearGradient(listOf(dark, bright)))
    ) {
        CampusDrawing(Modifier.align(Alignment.BottomEnd).size(158.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 23.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Tag("Sample college", Color(0xFF285559), Color(0xFFD6F5EC))
            Text("Welcome, College Admin", color = Color.White,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Northfield College", color = Color(0xFFC9E7E2), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CampusDrawing(modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 100
        val faint = Color(0xFFB6E9DA).copy(alpha = 0.20f)
        val circle = faint.copy(alpha = 0.10f)
        drawCircle(circle, radius = 45 * u, center = Offset(70 * u, 61 * u), style = Stroke(u))
        drawCircle(circle, radius = 58 * u, center = Offset(70 * u, 61 * u), style = Stroke(u))
        fun line(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(faint, Offset(x * u, y * u), Offset(dx * u, dy * u), 1.2f * u, cap = StrokeCap.Round)
        }
        line(34f, 49f, 66f, 33f)
        line(66f, 33f, 98f, 49f)
        line(34f, 49f, 98f, 49f)
        line(37f, 53f, 94f, 53f)
        for (x in listOf(43f, 54f, 65f, 76f, 87f)) {
            line(x, 57f, x, 84f)
            line(x + 3, 57f, x + 3, 84f)
        }
        line(37f, 88f, 94f, 88f)
        line(33f, 92f, 98f, 92f)
    }
}

@Composable
private fun SectionTitle(title: String, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = Ink)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
private fun DepartmentMetric(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(TealTint).clickable(onClick = onClick)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        IconTile(Glyph.BUILDING, Color.White, Teal)
        Column(Modifier.weight(1f)) {
            Text("Departments", color = Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Department directory", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = Teal, fontWeight = FontWeight.Bold)
        PreviewIcon(Glyph.CHEVRON, Teal, Modifier.size(18.dp))
    }
}

@Composable
private fun MetricCard(label: String, value: String, glyph: Glyph, tint: Color, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(18.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            IconTile(glyph, tint, color)
            Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
        }
        Text(value, color = Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickAction(title: String, detail: String, glyph: Glyph, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(17.dp)),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(glyph, if (glyph == Glyph.PLUS) TealTint else BlueTint, if (glyph == Glyph.PLUS) Teal else Blue)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            PreviewIcon(Glyph.CHEVRON, Muted, Modifier.size(17.dp))
        }
    }
}
