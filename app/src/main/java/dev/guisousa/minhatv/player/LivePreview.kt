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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.SourceHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Memória da prévia do ao vivo (o player em si é o [LiveSession], dividido com a tela cheia). */
object LivePreview {
    /** Para o vídeo do ao vivo (o servidor conta cada conexão como uma tela). */
    fun stop() = LiveSession.stop()
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
 * Vídeo do canal escolhido (OK na lista), tocando dentro do quadro da prévia. Usa o mesmo player da tela cheia
 * ([LiveSession]): abrir o canal amplia o vídeo que já está tocando, e voltar devolve ele para cá, sem carregar
 * de novo. Para quando a tela sai da frente (trocou de aba, foi para os detalhes, app em segundo plano).
 * [dwellMs]: espera antes de trocar de canal (voltando da tela cheia num canal trocado com ▲▼, a prévia
 * passa para ele nesse meio-tempo e nada é recarregado). [onShowing] avisa quando aparece a imagem.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePreviewVideo(entry: Entry?, active: Boolean, modifier: Modifier = Modifier, dwellMs: Long = 300, onShowing: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { LiveSession.player(context) }
    var firstFrame by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlayerError(error: PlaybackException) {
                if (LiveSession.fullscreen) return // na tela cheia quem cuida dos erros é o player de lá
                // ficou para trás da janela ao vivo (pausa, rede lenta): volta para o ao vivo em vez de esconder o vídeo
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                failed = true; firstFrame = false
            }
        }
        player.addListener(l)
        LiveSession.previews++
        onDispose {
            LiveSession.previews--
            player.removeListener(l)
            texture?.let {
                player.clearVideoTextureView(it)
                if (LiveSession.previewTexture?.get() === it) { LiveSession.previewTexture = null; LiveSession.onPreview = false }
            }
            // saiu da tela de canais (outra aba, detalhes): para o vídeo, a não ser que a tela cheia esteja com ele
            if (!LiveSession.fullscreen) LiveSession.stop()
        }
    }
    // app em segundo plano ou player de tela cheia aberto
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // voltou da tela cheia: o vídeo volta a ser desenhado aqui (normalmente a tela cheia já devolveu ao fechar)
    LaunchedEffect(resumed, texture) {
        val tv = texture
        if (resumed && tv != null) {
            LiveSession.previewTexture = java.lang.ref.WeakReference(tv)
            LiveSession.showOnPreview()
        }
    }
    val on = entry != null && active && resumed
    LaunchedEffect(entry?.url, on) {
        onShowing(false)
        if (!on || entry == null) {
            firstFrame = false
            // tela cheia aberta por cima: o vídeo continua lá, não mexe. Senão (outra aba, segundo plano): para.
            if (!LiveSession.fullscreen) LiveSession.stop()
            return@LaunchedEffect
        }
        // o canal já está tocando (voltou da tela cheia, ou o foco voltou para ele): só mostra, sem recarregar
        if (LiveSession.isPlaying(entry.url)) {
            failed = false
            if (player.playbackState == Player.STATE_READY) firstFrame = true
            player.playWhenReady = true
            if (vigiar(player, { firstFrame }, { failed }, onShowing)) return@LaunchedEffect
        }
        // outro canal: espera um pouco antes de desligar o anterior (ver [dwellMs])
        firstFrame = false; failed = false
        delay(dwellMs)
        player.playWhenReady = false
        player.stop(); player.clearMediaItems()
        LiveSession.channelUrl = null
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
                    // igual à tela cheia: nunca desacelera e acelera no máximo 4% para alcançar o vivo
                    .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.04f).build())
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(src.name).build())
                    .build()
                player.setMediaSource(factory.createMediaSource(item))
                player.prepare()
                player.playWhenReady = true
                LiveSession.loaded(entry.url, src.url, url)
                // servidor que não responde em 8 s: passa para o próximo (alguns levam ~4 s só para responder)
                val ok = withTimeoutOrNull(8_000) { snapshotFlow { firstFrame || failed }.first { it } } == true && firstFrame
                if (ok) {
                    LivePreview.lastOk[key] = url; LivePreview.goodHosts += host; LivePreview.badHosts -= host
                    if (vigiar(player, { firstFrame }, { failed }, onShowing)) return@LaunchedEffect
                    // caiu de vez: tenta a próxima fonte
                }
                if (url == hls && failed) LivePreview.noHls += host
                else if (host !in LivePreview.goodHosts) LivePreview.badHosts += host
                if (LivePreview.lastOk[key] == url) LivePreview.lastOk.remove(key)
                player.stop()
                LiveSession.channelUrl = null
            }
        }
    }
    val alpha by animateFloatAsState(if (firstFrame && on) 1f else 0f, tween(400), label = "preview")
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also {
                texture = it
                LiveSession.previewTexture = java.lang.ref.WeakReference(it)
                LiveSession.onPreview = false
                if (!LiveSession.fullscreen) LiveSession.showOnPreview()
            }
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/**
 * Vigia o vídeo da prévia enquanto toca: imagem parada (o relógio do vídeo não anda) por 8 s, ou carregando
 * há mais de 10 s, reconecta no mesmo canal (até 2 vezes). Devolve true se ficou tocando até a prévia sair
 * (coroutine cancelada) e false se caiu de vez (aí quem chamou tenta outra fonte).
 */
private suspend fun vigiar(player: ExoPlayer, firstFrame: () -> Boolean, failed: () -> Boolean, onShowing: (Boolean) -> Unit): Boolean {
    var reconexoes = 0
    var ultimaPos = -1L
    var paradoDesde = 0L
    while (true) {
        onShowing(firstFrame())
        delay(1_000)
        if (failed()) { onShowing(false); return false }
        if (LiveSession.fullscreen) continue
        val agora = android.os.SystemClock.uptimeMillis()
        val pos = player.currentPosition
        val andando = player.playbackState == Player.STATE_READY && player.isPlaying && pos != ultimaPos
        if (andando || ultimaPos < 0) { ultimaPos = pos; paradoDesde = agora; continue }
        val limite = if (player.playbackState == Player.STATE_BUFFERING) 10_000 else 8_000
        if (agora - paradoDesde < limite) continue
        if (reconexoes >= 2) { onShowing(false); return false }
        reconexoes++
        android.util.Log.w("MinhaTV", "prévia: imagem parada, reconectando ($reconexoes de 2)")
        player.seekToDefaultPosition(); player.prepare(); player.playWhenReady = true
        ultimaPos = -1L; paradoDesde = agora
    }
}
