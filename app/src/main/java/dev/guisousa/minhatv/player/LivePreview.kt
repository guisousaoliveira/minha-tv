package dev.guisousa.minhatv.player

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.SourceHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Player da prévia que está aberto agora (o player de tela cheia fecha ele antes de abrir: o servidor conta telas). */
object LivePreview {
    @Volatile internal var current: ExoPlayer? = null
    fun stop() { current?.let { it.stop(); it.clearMediaItems() } }
    /** Servidores que não deram a versão HLS: a prévia vai direto no .ts deles. */
    internal val noHls = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Endereço que já tocou para cada canal: na próxima vez ele é o primeiro a tentar. */
    internal val lastOk = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Servidores que já tocaram / que já travaram: os bons vão para a frente da fila, os ruins para o fim. */
    internal val goodHosts = java.util.Collections.synchronizedSet(HashSet<String>())
    internal val badHosts = java.util.Collections.synchronizedSet(HashSet<String>())
}

/** http://host/live/user/pass/123.ts ou http://host/user/pass/123 -> http://host/live/user/pass/123.m3u8 (servidor Xtream). */
internal fun xtreamHls(url: String): String? {
    val u = android.net.Uri.parse(url)
    val segs = u.pathSegments ?: return null
    val base = "${u.scheme}://${u.encodedAuthority}"
    val q = u.encodedQuery?.let { "?$it" } ?: ""
    val last = segs.lastOrNull() ?: return null
    return when {
        segs.size == 4 && segs[0] == "live" && last.endsWith(".ts") -> "$base/live/${segs[1]}/${segs[2]}/${last.removeSuffix(".ts")}.m3u8$q"
        segs.size == 3 && last.all { it.isDigit() } -> "$base/live/${segs[0]}/${segs[1]}/$last.m3u8$q"
        segs.size == 3 && last.endsWith(".ts") && last.removeSuffix(".ts").all { it.isDigit() } -> "$base/live/${segs[0]}/${segs[1]}/${last.removeSuffix(".ts")}.m3u8$q"
        else -> null
    }
}

/**
 * Vídeo do canal em foco, tocando dentro do quadro da prévia.
 * Só começa depois de você parar um pouco no canal ([dwellMs]) e para quando a tela sai da frente
 * (abriu o player, trocou de aba, foi para os detalhes). [onShowing] avisa quando aparece a imagem.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePreviewVideo(entry: Entry?, active: Boolean, modifier: Modifier = Modifier, dwellMs: Long = 800, onShowing: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember {
        // pouco buffer: a prévia começa rápido e não segura banda à toa
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 8_000, 800, 1_500).build()
        ExoPlayer.Builder(context).setLoadControl(load).build().apply { playWhenReady = true }
    }
    var firstFrame by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlayerError(error: PlaybackException) { failed = true; firstFrame = false }
        }
        player.addListener(l)
        LivePreview.current = player
        onDispose {
            player.removeListener(l)
            if (LivePreview.current === player) LivePreview.current = null
            player.release()
        }
    }
    // app em segundo plano ou player de tela cheia aberto: para a prévia
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val on = entry != null && active && resumed
    LaunchedEffect(entry?.url, on) {
        player.stop(); player.clearMediaItems()
        firstFrame = false; failed = false
        onShowing(false)
        if (!on || entry == null) return@LaunchedEffect
        delay(dwellMs)
        val sources = runCatching { Repository.sources(entry) }.getOrDefault(emptyList())
            .filter { !it.url.startsWith("v2:") }.ifEmpty { if (entry.url.startsWith("v2:")) emptyList() else listOf(entry) }
        fun hostOf(u: String) = android.net.Uri.parse(u).host.orEmpty()
        // primeiro os servidores que já tocaram, por último os que já travaram
        val ordered = sources.take(8).sortedBy { s ->
            val h = hostOf(s.url)
            when (h) { in LivePreview.goodHosts -> 0; in LivePreview.badHosts -> 2; else -> 1 }
        }
        val key = entry.name.lowercase()
        val remembered = LivePreview.lastOk[key]
        // o endereço que tocou da última vez vai na frente de tudo
        val attempts = buildList {
            ordered.forEach { src ->
                val host = hostOf(src.url)
                val hls = if (!SourceHealth.tsOnly(src.url) && host !in LivePreview.noHls) xtreamHls(src.url) else null
                listOfNotNull(hls, src.url).distinct().forEach { add(Triple(src, it, hls)) }
            }
        }.sortedBy { if (it.second == remembered) 0 else 1 }
        // as fontes que falham voltam em menos de 1 s: dá para tentar várias (o player de tela cheia faz igual)
        for ((src, url, hls) in attempts) {
            val host = hostOf(url)
            if (url == hls && host in LivePreview.noHls) continue
            run {
                firstFrame = false; failed = false
                val http = OkHttpDataSource.Factory(PlayerHttp.client)
                    .setUserAgent(src.userAgent ?: PlayerHttp.SAFARI_UA)
                    .setDefaultRequestProperties(buildMap { put("Accept", "*/*"); src.referrer?.let { put("Referer", it) } })
                val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, StableRedirectDataSource.Factory(http)))
                val item = MediaItem.Builder().setUri(url)
                    .apply { if (url.lowercase().substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
                    .build()
                player.setMediaSource(factory.createMediaSource(item))
                player.prepare()
                // servidor que não responde em 8 s: passa para o próximo (alguns levam ~4 s só para responder)
                val ok = withTimeoutOrNull(8_000) { snapshotFlow { firstFrame || failed }.first { it } } == true && firstFrame
                if (ok) {
                    LivePreview.lastOk[key] = url; LivePreview.goodHosts += host; LivePreview.badHosts -= host
                    onShowing(true)
                    // se cair depois, só esconde o vídeo (a logo volta a aparecer)
                    snapshotFlow { failed }.first { it }
                    onShowing(false)
                    return@LaunchedEffect
                }
                if (url == hls && failed) LivePreview.noHls += host
                else if (host !in LivePreview.goodHosts) LivePreview.badHosts += host
                if (LivePreview.lastOk[key] == url) LivePreview.lastOk.remove(key)
                player.stop()
            }
        }
    }
    val alpha by animateFloatAsState(if (firstFrame && on) 1f else 0f, tween(400), label = "preview")
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}
