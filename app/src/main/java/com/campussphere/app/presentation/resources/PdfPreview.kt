package com.campussphere.app.presentation.resources

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val ZOOMS = listOf(1f, 1.5f, 2f, 3f)

/** Opened PDF plus a lock: PdfRenderer can only have one page open at a time and is not thread-safe. */
private class OpenPdf(val descriptor: ParcelFileDescriptor, val renderer: PdfRenderer) {
    val lock = Mutex()
    var closed = false

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed) return null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val width = widthPx.coerceIn(200, 2400)
                val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 4000)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    suspend fun close() = lock.withLock {
        if (!closed) { closed = true; runCatching { renderer.close() }; runCatching { descriptor.close() } }
    }
}

/**
 * Shows the PDF inside the app, page by page, from the private cache copy. Pages are drawn only when they scroll into
 * view. A damaged or password-protected PDF calls [failed] so the screen can offer Download instead.
 */
@Composable
fun PdfPreview(file: File, title: String, close: () -> Unit, failed: () -> Unit, modifier: Modifier = Modifier) {
    var pdf by remember(file) { mutableStateOf<OpenPdf?>(null) }
    var zoom by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(file) {
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    OpenPdf(descriptor, PdfRenderer(descriptor))
                } catch (e: Exception) {
                    descriptor.close(); throw e
                }
            }.getOrNull()
        }
        if (opened == null || opened.renderer.pageCount == 0) { opened?.close(); failed(); return@LaunchedEffect }
        pdf = opened
        // Keep the PDF open while the preview is on screen; close it (after any page being drawn) when it leaves.
        try {
            awaitCancellation()
        } finally {
            pdf = null
            withContext(NonCancellable) { opened.close() }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(close) { Text("Close") }
            Text(title, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            TextButton({ zoom = (zoom - 1).coerceAtLeast(0) }, enabled = zoom > 0) { Text("−") }
            Text("${(ZOOMS[zoom] * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            TextButton({ zoom = (zoom + 1).coerceAtMost(ZOOMS.lastIndex) }, enabled = zoom < ZOOMS.lastIndex) { Text("+") }
        }
        val open = pdf
        if (open == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        val pages = open.renderer.pageCount
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val density = LocalDensity.current
            val pageWidth = maxWidth * ZOOMS[zoom] - 16.dp
            val widthPx = with(density) { pageWidth.roundToPx() }
            val sideways = rememberScrollState()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages, key = { it }) { index ->
                    val bitmap by produceState<Bitmap?>(null, open, index, widthPx) { value = open.render(index, widthPx) }
                    Column(Modifier.horizontalScroll(sideways)) {
                        val shown = bitmap
                        if (shown == null) {
                            Box(Modifier.width(pageWidth).height(pageWidth * 1.3f).background(Color.White), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            Image(shown.asImageBitmap(), null, Modifier.width(pageWidth)
                                .height(pageWidth * (shown.height.toFloat() / shown.width))
                                .semantics { contentDescription = "Page ${index + 1} of $pages" })
                        }
                        Text("Page ${index + 1} of $pages", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
