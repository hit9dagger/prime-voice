package cz.primevoice.ui

import androidx.camera.view.PreviewView
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cz.primevoice.camera.CameraCaptureManager
import cz.primevoice.core.Region
import kotlin.math.*

@Composable fun SubtitleRegionSelector(manager: CameraCaptureManager, running: Boolean,
                                       region: Region, editing: Boolean, onRegion: (Region) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var draft by remember(region) { mutableStateOf(region) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(running, owner, view) {
        var attached = true
        if (running) view.doOnAttach {
            view.doOnLayout {
                if (attached && view.isAttachedToWindow && view.width > 0 && view.height > 0) manager.start(owner, view)
            }
        } else manager.stop()
        onDispose { attached = false; manager.stop() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF080C11))) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (!running) {
            Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(PrimeIcons.LiveTv, null, tint = Muted, modifier = Modifier.size(42.dp))
                Text("Namiřte telefon na televizi", style = MaterialTheme.typography.titleMedium)
                Text("Kamera se zapne po spuštění překladu", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Canvas(Modifier.fillMaxSize().pointerInput(editing, running) {
            if (editing && running) detectDragGestures(
                onDragStart = { origin = it },
                onDragEnd = { onRegion(draft.valid()) },
                onDragCancel = { draft = region },
                onDrag = { change, _ ->
                    change.consume()
                    val end = change.position
                    draft = Region(min(origin.x, end.x) / size.width, min(origin.y, end.y) / size.height,
                        max(origin.x, end.x) / size.width, max(origin.y, end.y) / size.height).valid()
                }
            ) else detectTapGestures { point -> if (running) manager.focus(view, point.x, point.y) }
        }) {
            if (running) {
                val r = if (editing) draft else region
                val x = r.left * size.width; val y = r.top * size.height
                val w = (r.right - r.left) * size.width; val h = (r.bottom - r.top) * size.height
                val shade = Color.Black.copy(alpha = .42f)
                drawRect(shade, Offset.Zero, Size(size.width, y))
                drawRect(shade, Offset(0f, y + h), Size(size.width, max(0f, size.height - y - h)))
                drawRect(shade, Offset(0f, y), Size(x, h))
                drawRect(shade, Offset(x + w, y), Size(max(0f, size.width - x - w), h))
                drawRect(Lime.copy(alpha = .7f), Offset(x, y), Size(w, h), style = Stroke(1.3.dp.toPx()))
                val corner = 16.dp.toPx(); val stroke = 3.dp.toPx()
                listOf(Offset(x, y), Offset(x + w, y), Offset(x, y + h), Offset(x + w, y + h)).forEachIndexed { i, o ->
                    drawLine(Lime, o, Offset(o.x + if (i % 2 == 0) corner else -corner, o.y), stroke)
                    drawLine(Lime, o, Offset(o.x, o.y + if (i < 2) corner else -corner), stroke)
                }
            }
        }
        if (running) Surface(Modifier.align(Alignment.TopStart).padding(12.dp), color = Color(0xDD0B1017), shape = RoundedCornerShape(8.dp)) {
            Text(if (editing) "TAŽENÍM OZNAČTE TITULKY" else "OBLAST TITULKŮ", Modifier.padding(8.dp),
                color = Lime, style = MaterialTheme.typography.labelSmall)
        }
    }
}
