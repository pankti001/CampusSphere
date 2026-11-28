package com.campussphere.app.ui.brand

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * CampusSphere's own line icons (24 × 24, 2 px rounded strokes), drawn from simple path data so the app needs no
 * icon library. `Icon(CampusIcons.Back, "Back")` tints them with the current content colour.
 */
object CampusIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach {
            builder.addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return builder.build()
    }

    private const val CIRCLE_9 = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0"

    val Back by lazy { icon("Back", "M19 12H5", "M12 19l-7-7 7-7") }
    val ChevronRight by lazy { icon("ChevronRight", "M9 6l6 6-6 6") }
    val ChevronDown by lazy { icon("ChevronDown", "M6 9l6 6 6-6") }
    val Search by lazy { icon("Search", "M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M20 20l-3.5-3.5") }
    val Plus by lazy { icon("Plus", "M12 5v14", "M5 12h14") }
    val Close by lazy { icon("Close", "M6 6l12 12", "M18 6L6 18") }
    val Check by lazy { icon("Check", "M5 12l5 5 9-10") }
    val Download by lazy { icon("Download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14") }
    val Upload by lazy { icon("Upload", "M12 20V9", "M7 14l5-5 5 5", "M5 4h14") }
    val Eye by lazy { icon("Eye", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0") }
    val Refresh by lazy { icon("Refresh", "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v5h-5") }
    val Edit by lazy { icon("Edit", "M4 20h4L19 9l-4-4L4 16v4z", "M13.5 6.5l4 4") }
    val File by lazy { icon("File", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M9 13h6", "M9 17h4") }
    val Book by lazy { icon("Book", "M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2V5z", "M4 19a2 2 0 0 1 2-2h13", "M9 7h6") }
    val Books by lazy { icon("Books", "M4 4h4v16H4z", "M10 4h4v16h-4z", "M16 5l3.5-1 3 15.5-3.5 1z") }
    val Calendar by lazy { icon("Calendar", "M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", "M4 10h16", "M8 2v4", "M16 2v4") }
    val Clock by lazy { icon("Clock", CIRCLE_9, "M12 7v5l3 2") }
    val Chat by lazy { icon("Chat", "M4 5h16v11H9l-5 4z", "M8 9h8", "M8 12h5") }
    val Megaphone by lazy { icon("Megaphone", "M3 10v4h4l7 5V5l-7 5H3z", "M17 9a4 4 0 0 1 0 6") }
    val Bell by lazy { icon("Bell", "M6 16V11a6 6 0 1 1 12 0v5l2 2H4z", "M10 20a2 2 0 0 0 4 0") }
    val Checklist by lazy { icon("Checklist", "M4 6l2 2 3-3", "M4 13l2 2 3-3", "M12 7h8", "M12 14h8", "M12 19h8") }
    val Homework by lazy { icon("Homework", "M6 3h9l4 4v14H6z", "M9 12l2 2 4-4", "M9 17h6") }
    val Users by lazy { icon("Users", "M5 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M2 20a7 7 0 0 1 14 0", "M16 4a4 4 0 0 1 0 8", "M18 14a6 6 0 0 1 4 6") }
    val Handshake by lazy { icon("Handshake", "M3 11l4-4 5 3 5-3 4 4", "M7 13l4 4a2 2 0 0 0 3 0l4-4", "M3 11l4 4", "M21 11l-4 4") }
    val Chart by lazy { icon("Chart", "M4 20h16", "M7 16V10", "M12 16V5", "M17 16v-4") }
    val Building by lazy { icon("Building", "M4 21V7l8-4 8 4v14", "M9 21v-5h6v5", "M8 10h.01", "M12 10h.01", "M16 10h.01") }
    val Settings by lazy { icon("Settings", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3", "M5 5l2 2", "M17 17l2 2", "M5 19l2-2", "M17 7l2-2") }
    val Logout by lazy { icon("Logout", "M14 4H6v16h8", "M10 12h11", "M17 8l4 4-4 4") }
    val Archive by lazy { icon("Archive", "M3 4h18v4H3z", "M5 8v12h14V8", "M10 12h4") }
    val Hide by lazy { icon("Hide", "M3 3l18 18", "M10.6 6.1A9 9 0 0 1 12 6c6 0 10 6 10 6a17 17 0 0 1-3 3.5", "M6.6 6.6C3.7 8.3 2 12 2 12s4 7 10 7a9 9 0 0 0 4-1") }
    val Trash by lazy { icon("Trash", "M4 7h16", "M10 11v6", "M14 11v6", "M5 7l1 13h12l1-13", "M9 7V4h6v3") }
    val Info by lazy { icon("Info", CIRCLE_9, "M12 11v5", "M12 8h.01") }
    val Warning by lazy { icon("Warning", "M12 3l10 18H2z", "M12 10v4", "M12 17h.01") }
    val Filter by lazy { icon("Filter", "M4 5h16l-6 8v6l-4-2v-4z") }
}
