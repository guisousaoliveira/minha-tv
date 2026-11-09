package dev.guisousa.minhatv.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.SamplingMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.desktop.ui.Ic
import dev.guisousa.minhatv.desktop.ui.IconBtn
import dev.guisousa.minhatv.desktop.ui.LocalNav
import dev.guisousa.minhatv.desktop.ui.Palette
import dev.guisousa.minhatv.desktop.ui.PillButton
import dev.guisousa.minhatv.desktop.ui.relogio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

/** Desenha o vídeo do player com faixas pretas para manter a proporção. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoView(player: PlayerController, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onDoubleClick: (() -> Unit)? = null) {
    val tick = player.frameTick
    Box(
        modifier.background(Color.Black).then(
            if (onClick != null || onDoubleClick != null)
                Modifier.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onClick?.invoke() }, onDoubleClick = onDoubleClick)
            else Modifier
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick // recompõe a cada quadro novo
            player.frames.withFront { bmp ->
                val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
                if (w <= 0 || h <= 0) return@withFront
                val s = minOf(size.width / w, size.height / h)
                val dw = w * s; val dh = h * s
                val x = (size.width - dw) / 2; val y = (size.height - dh) / 2
                val img = Image.makeFromBitmap(bmp)
                // sem dizer a amostragem o Skia usa "vizinho mais próximo" e o vídeo fica quadriculado:
                // ampliando usa cúbica (Mitchell), reduzindo usa linear
                val sampling = if (s > 1f) SamplingMode.MITCHELL else FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
                drawIntoCanvas {
                    it.nativeCanvas.drawImageRect(img, Rect.makeWH(w, h), Rect.makeXYWH(x, y, dw, dh), sampling, null, true)
                }
                img.close()
            }
        }
        if (player.item != null && player.error == null && (!player.hasVideo || player.buffering)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Palette.Brand, strokeWidth = 3.dp)
        }
        if (player.error != null && onDoubleClick != null) {
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(player.error ?: "", color = Palette.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                PillButton("Tentar de novo", { player.retry() }, Ic.Restart)
            }
        }
    }
}

/** Player em tela cheia com os controles por cima. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen() {
    val nav = LocalNav.current
    val p = nav.player
    val e = p.item
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    var visible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(lastMove, menuOpen, p.playing) {
        visible = true
        if (menuOpen || !p.playing) return@LaunchedEffect
        delay(3000); visible = false
    }
    LaunchedEffect(Unit) {
        p.onFinished = { nav.back() }
        p.onPlayItem = { next -> p.open(next) }
    }
    val scope = rememberCoroutineScope()
    fun zap(dir: Int) {
        val cur = p.item ?: return
        scope.launch {
            val (list, i) = Repository.channelQueue(cur.url) ?: return@launch
            if (list.isEmpty()) return@launch
            p.open(list[(i + dir + list.size) % list.size])
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .volumeNaRodinha(p) { lastMove = System.currentTimeMillis() }
            .pointerHoverIcon(if (visible) PointerIcon.Default else PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "vazio")))
    ) {
        VideoView(p, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { if (!p.live) p.togglePause(); lastMove = System.currentTimeMillis() }, onDoubleTap = { nav.fullscreen = !nav.fullscreen })
        })

        if (p.error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 520.dp).background(Color(0xCC111114), RoundedCornerShape(10.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(p.error ?: "", color = Palette.Text, fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Tentar de novo", { p.retry() }, Ic.Restart, primary = true)
                    PillButton("Voltar", { nav.back() }, Ic.Back)
                }
            }
        } else if (p.status != null) {
            Text(p.status ?: "", Modifier.align(Alignment.Center).offset(y = 48.dp), color = Palette.TextDim, fontSize = 14.sp)
        }

        AnimatedVisibility(visible || p.error != null, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // topo
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBtn(Ic.Back, { nav.back() }, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val title = when (e?.type) {
                            ContentType.SERIES -> "${e.seriesName ?: e.name}"
                            else -> e?.name ?: ""
                        }
                        Text(title, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when (e?.type) {
                            ContentType.SERIES -> "Temporada ${e.season} · Episódio ${e.episode}" + (e.name.takeIf { it != e.seriesName }?.let { "  —  $it" } ?: "")
                            ContentType.LIVE -> "AO VIVO · ${e.group}"
                            else -> e?.group
                        }
                        if (sub != null) Text(sub, color = Palette.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (p.live) {
                        IconBtn(Ic.Up, { zap(-1) }, size = 40.dp)
                        IconBtn(Ic.Down, { zap(1) }, size = 40.dp)
                    }
                }
                // base
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp)
                ) {
                    if (!p.live && p.length > 0) {
                        SeekBar(p.time, p.length, { p.seekTo(it); lastMove = System.currentTimeMillis() })
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(relogio(p.time), color = Palette.TextDim, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text("-" + relogio((p.length - p.time).coerceAtLeast(0)), color = Palette.TextDim, fontSize = 13.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!p.live) {
                            IconBtn(if (p.playing) Ic.Pause else Ic.Play, { p.togglePause() }, size = 48.dp)
                            IconBtn(Ic.Replay10, { p.seekBy(-10_000) }, size = 44.dp)
                            IconBtn(Ic.Forward10, { p.seekBy(10_000) }, size = 44.dp)
                        } else {
                            Box(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Live).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Text("AO VIVO", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        IconBtn(if (p.muted || p.volume == 0) Ic.VolumeOff else Ic.Volume, { p.toggleMute() }, size = 40.dp)
                        VolumeBar(if (p.muted) 0 else p.volume) { p.changeVolume(it); lastMove = System.currentTimeMillis() }
                        Text(if (p.muted) "Mudo" else "${p.volume}%", Modifier.padding(start = 10.dp).width(44.dp), color = Palette.TextDim, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        if (!p.live && p.resumedFrom > 0 && p.time < p.resumedFrom + 60_000) {
                            PillButton("Do início", { p.seekTo(0) }, Ic.Restart)
                            Spacer(Modifier.width(8.dp))
                        }
                        p.nextEpisode?.let { n ->
                            PillButton("Próximo episódio", { p.open(n) }, Ic.SkipNext)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (p.sources.size > 1) {
                            PillButton("Fonte ${p.sourceIndex + 1}/${p.sources.size}", { p.nextSource() }, Ic.Source)
                            Spacer(Modifier.width(8.dp))
                        }
                        TracksMenu(p, onOpen = { menuOpen = it })
                        IconBtn(if (nav.fullscreen) Ic.FullscreenExit else Ic.Fullscreen, { nav.fullscreen = !nav.fullscreen }, size = 44.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(time: Long, length: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var hover by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(20.dp).pointerHoverIcon(PointerIcon.Hand)
    ) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val fullW = maxWidth
        val frac = dragging ?: (time.toFloat() / length).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(length) {
                    detectTapGestures { o -> onSeek(((o.x / wPx).coerceIn(0f, 1f) * length).toLong()) }
                }
                .pointerInput(length) {
                    detectDragGestures(
                        onDragStart = { o -> dragging = (o.x / wPx).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let { onSeek((it * length).toLong()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> dragging = (change.position.x / wPx).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF)))
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Brand))
            Box(Modifier.offset(x = fullW * frac - 7.dp).size(14.dp).clip(CircleShape).background(Color.White))
        }
        if (dragging != null) {
            Text(relogio(((dragging ?: 0f) * length).toLong()), Modifier.offset(x = (fullW * (dragging ?: 0f) - 24.dp).coerceAtLeast(0.dp), y = (-24).dp), color = Palette.Text, fontSize = 13.sp)
        }
        @Suppress("UNUSED_EXPRESSION") hover
    }
}

@Composable
internal fun VolumeBar(volume: Int, onChange: (Int) -> Unit) {
    BoxWithConstraints(Modifier.width(110.dp).height(20.dp).pointerHoverIcon(PointerIcon.Hand)) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun v(x: Float) = ((x / wPx).coerceIn(0f, 1f) * 100).toInt()
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onChange(v(it.x)) } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> onChange(v(change.position.x)) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            val f = (volume.coerceAtMost(100) / 100f)
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x44FFFFFF)))
            Box(Modifier.fillMaxWidth(f).height(3.dp).background(Color.White))
        }
    }
}

/** Menu de áudio e legendas (quando o vídeo tem mais de uma opção). */
@Composable
internal fun TracksMenu(p: PlayerController, onOpen: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var audios by remember { mutableStateOf<List<PlayerController.Track>>(emptyList()) }
    var subs by remember { mutableStateOf<List<PlayerController.Track>>(emptyList()) }
    var curA by remember { mutableIntStateOf(-1) }
    var curS by remember { mutableIntStateOf(-1) }
    Box {
        IconBtn(Ic.Subtitles, {
            audios = p.audioTracks(); subs = p.subtitleTracks(); curA = p.audioTrack(); curS = p.subtitleTrack()
            open = true; onOpen(true)
        }, size = 44.dp)
        DropdownMenu(open, { open = false; onOpen(false) }, Modifier.background(Palette.Surface)) {
            Text("ÁUDIO", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Palette.Brand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            val a = audios.filter { it.id >= 0 }
            if (a.isEmpty()) Text("Só uma faixa", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Palette.TextFaint, fontSize = 13.sp)
            a.forEach { t ->
                DropdownMenuItem({ Text(t.name, color = if (t.id == curA) Palette.Brand else Palette.Text) }, { p.setAudioTrack(t.id); open = false; onOpen(false) })
            }
            Text("LEGENDAS", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Palette.Brand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (subs.isEmpty()) Text("Sem legendas", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Palette.TextFaint, fontSize = 13.sp)
            subs.forEach { t ->
                val name = if (t.id < 0) "Desligadas" else t.name
                DropdownMenuItem({ Text(name, color = if (t.id == curS) Palette.Brand else Palette.Text) }, { p.setSubtitleTrack(t.id); open = false; onOpen(false) })
            }
        }
    }
}

/** Rodinha do mouse em cima do vídeo muda o volume (de 5 em 5). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.volumeNaRodinha(p: PlayerController, onChange: () -> Unit = {}): Modifier =
    onPointerEvent(PointerEventType.Scroll) { ev ->
        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
        if (dy != 0f) {
            val base = if (p.muted) 0 else p.volume
            p.changeVolume(base + if (dy < 0) 5 else -5)
            onChange()
        }
    }

/**
 * Prévia do ao vivo: vídeo com uma barra de controles que aparece ao passar o mouse
 * (pausar, mudo, volume, áudio/legendas, fonte e tela cheia). A rodinha do mouse muda o volume.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PreviewPlayer(p: PlayerController, onFullscreen: () -> Unit, modifier: Modifier = Modifier) {
    var hover by remember { mutableStateOf(false) }
    var lastMove by remember { mutableStateOf(0L) }
    var menuOpen by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hover, lastMove, menuOpen, p.playing) {
        visible = hover || menuOpen || !p.playing
        if (!hover || menuOpen || !p.playing) return@LaunchedEffect
        delay(2500); visible = false
    }
    Box(
        modifier
            .onPointerEvent(PointerEventType.Enter) { hover = true; lastMove = System.currentTimeMillis() }
            .onPointerEvent(PointerEventType.Exit) { hover = false }
            .onPointerEvent(PointerEventType.Move) { hover = true; lastMove = System.currentTimeMillis() }
            .volumeNaRodinha(p) { lastMove = System.currentTimeMillis() }
    ) {
        VideoView(p, Modifier.fillMaxSize(), onDoubleClick = onFullscreen)
        if (p.status != null && p.error == null) {
            Text(p.status ?: "", Modifier.align(Alignment.Center).offset(y = 40.dp), color = Palette.TextDim, fontSize = 12.sp)
        }
        AnimatedVisibility(visible && p.error == null, Modifier.align(Alignment.BottomStart).fillMaxWidth(), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(if (p.playing) Ic.Pause else Ic.Play, { p.togglePause() }, size = 36.dp)
                IconBtn(if (p.muted || p.volume == 0) Ic.VolumeOff else Ic.Volume, { p.toggleMute() }, size = 36.dp)
                VolumeBar(if (p.muted) 0 else p.volume) { p.changeVolume(it); lastMove = System.currentTimeMillis() }
                Text(if (p.muted) "Mudo" else "${p.volume}%", Modifier.padding(start = 8.dp), color = Palette.TextDim, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (p.sources.size > 1) IconBtn(Ic.Source, { p.nextSource() }, size = 36.dp)
                TracksMenu(p, onOpen = { menuOpen = it })
                IconBtn(Ic.Fullscreen, onFullscreen, size = 36.dp)
            }
        }
    }
}
