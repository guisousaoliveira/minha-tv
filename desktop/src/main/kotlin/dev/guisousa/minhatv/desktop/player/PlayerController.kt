package dev.guisousa.minhatv.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Log
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Settings
import dev.guisousa.minhatv.data.SourceHealth
import dev.guisousa.minhatv.data.UserLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/** UA padrão para os vídeos (o mesmo do app da TV). */
const val SAFARI_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

/**
 * Versão HLS de um link .ts do Xtream (mesma regra do app da TV): o HLS costuma abrir mais rápido e
 * aguentar melhor quedas; se falhar, volta para o .ts.
 */
fun isHls(url: String): Boolean =
    runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("").let { it.endsWith(".m3u8") || it.endsWith(".m3u") }

fun hlsVariant(url: String): String? {
    val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val path = u.path ?: return null
    val seg = path.trim('/').split('/')
    val base = "${u.scheme}://${u.authority}"
    return when {
        seg.size >= 4 && seg[seg.size - 4] == "live" && seg.last().endsWith(".ts") ->
            base + "/" + seg.dropLast(1).joinToString("/") + "/" + seg.last().removeSuffix(".ts") + ".m3u8"
        seg.size == 3 && seg.last().all { it.isDigit() } -> "$base/live/${seg[0]}/${seg[1]}/${seg[2]}.m3u8"
        seg.size == 3 && seg.last().endsWith(".ts") && seg.last().removeSuffix(".ts").all { it.isDigit() } ->
            "$base/live/${seg[0]}/${seg[1]}/${seg[2].removeSuffix(".ts")}.m3u8"
        else -> null
    }
}

/**
 * Estado do player e a lógica de tentar as fontes uma a uma (mesma ideia do app da TV):
 * sem imagem depois de um tempo ou erro → próxima fonte; ao vivo que acabou → reabre.
 */
class PlayerController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    var item by mutableStateOf<Entry?>(null); private set
    var sources by mutableStateOf<List<Entry>>(emptyList()); private set
    var sourceIndex by mutableStateOf(0); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(true); private set
    var hasVideo by mutableStateOf(false); private set
    var time by mutableLongStateOf(0L); private set
    var length by mutableLongStateOf(0L); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var frameTick by mutableLongStateOf(0L); private set
    var volume by mutableStateOf(Settings.value.volume); private set
    var muted by mutableStateOf(false); private set
    /** Posição em que retomou (para o botão "Do início"). */
    var resumedFrom by mutableLongStateOf(0L); private set
    var nextEpisode by mutableStateOf<Entry?>(null); private set

    val live get() = item?.type == ContentType.LIVE

    val frames = VideoFrames { scope.launch { frameTick++ } }
    private var mp: EmbeddedMediaPlayer? = null
    private var attempt: Job? = null
    private var saver: Job? = null
    /** Links da fonte atual a tentar (HLS e depois .ts). */
    private var variants: List<String> = emptyList()
    private var variantIndex = 0
    private var currentUrl: String? = null
    private var gotFrame = false
    private var liveRetries = 0

    var onFinished: (() -> Unit)? = null
    var onPlayItem: ((Entry) -> Unit)? = null

    private fun player(): EmbeddedMediaPlayer? {
        mp?.let { return it }
        val f = Vlc.factory ?: run { error = Vlc.erro ?: "Player indisponível"; return null }
        val p = f.mediaPlayers().newEmbeddedMediaPlayer()
        p.videoSurface().set(f.videoSurfaces().newVideoSurface(frames.formatCallback, frames.renderCallback, true))
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = ui { playing = true; buffering = false }
            override fun paused(mediaPlayer: MediaPlayer) = ui { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) = ui { playing = false }
            override fun finished(mediaPlayer: MediaPlayer) = ui { onEnd() }
            override fun error(mediaPlayer: MediaPlayer) = ui { onError("erro do VLC") }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = ui { time = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = ui { length = newLength }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui { buffering = newCache < 100f }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) = ui { if (newCount > 0) onFirstFrame() }
        })
        p.audio().setVolume(volume)
        mp = p
        return p
    }

    private fun ui(f: () -> Unit) { scope.launch { f() } }

    /** Abre um item (filme, episódio ou canal). */
    fun open(e: Entry) {
        saveProgress()
        item = e
        error = null
        status = "Abrindo…"
        sources = emptyList()
        nextEpisode = null
        liveRetries = 0
        Repository.playbackActive = true
        UserLibrary.adicionarRecente(e)
        if (e.type == ContentType.LIVE) Settings.edit { it.copy(ultimoCanal = e.url) }
        scope.launch {
            val list = runCatching { withContext(Dispatchers.IO) { Repository.sources(e) } }.getOrDefault(emptyList())
            if (item !== e) return@launch
            if (list.isEmpty()) { error = "Nenhuma fonte para este título"; status = null; return@launch }
            sources = list
            val start = if (e.type == ContentType.LIVE) 0L else UserLibrary.posicao(e.url).let { p ->
                val d = UserLibrary.duracao(e.url)
                if (p > 0 && (d <= 0 || p < d - 120_000)) p else 0L
            }
            resumedFrom = start
            playSource(0, start)
            if (e.type == ContentType.SERIES) launch { nextEpisode = findNext(e) }
        }
        saver?.cancel()
        saver = scope.launch { while (isActive) { delay(5_000); saveProgress() } }
    }

    private suspend fun findNext(e: Entry): Entry? = withContext(Dispatchers.IO) {
        val show = Repository.showOf(e) ?: return@withContext null
        val eps = Repository.episodes(show).list
        val i = eps.indexOfFirst { it.url == e.url }
        if (i >= 0) eps.getOrNull(i + 1) else null
    }

    /** Troca para a fonte [i] (botão "Fonte" ou porque a anterior falhou). */
    fun playSource(i: Int, startMs: Long = if (live) 0 else time) {
        val src = sources.getOrNull(i) ?: run { giveUp(); return }
        sourceIndex = i
        val url = src.url
        val hls = if (live && !SourceHealth.tsOnly(url)) hlsVariant(url) else null
        variants = listOfNotNull(hls, url).distinct()
        variantIndex = 0
        start(src, startMs)
    }

    private fun start(src: Entry, startMs: Long) {
        val p = player() ?: return
        val url = variants[variantIndex]
        currentUrl = url
        gotFrame = false
        hasVideo = false
        buffering = true
        length = 0
        frames.clear()
        status = if (sources.size > 1) "Abrindo fonte ${sourceIndex + 1} de ${sources.size}…" else "Abrindo…"
        val opts = buildList {
            add(":http-user-agent=${src.userAgent ?: SAFARI_UA}")
            src.referrer?.let { add(":http-referrer=$it") }
            add(":network-caching=${if (live) 3000 else 2500}")
            add(":http-reconnect")
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
        }
        Log.i("tocando ${item?.name} fonte ${sourceIndex + 1}/${sources.size}: $url")
        attempt?.cancel()
        val myUrl = url
        // HLS ao vivo passa pelo proxy local, que atualiza a lista sempre pelo link original
        val playUrl = if (live && isHls(url)) LiveProxy.endereco(url, src.userAgent ?: SAFARI_UA, src.referrer) else url
        p.submit { p.media().play(playUrl, *opts.toTypedArray()) }
        attempt = scope.launch {
            delay(if (live) 12_000 else 25_000)
            if (currentUrl == myUrl && !gotFrame) onError("sem imagem depois do tempo limite")
        }
    }

    private fun onFirstFrame() {
        if (gotFrame) return
        gotFrame = true
        hasVideo = true
        status = null
        liveRetries = 0
        attempt?.cancel()
        val url = sources.getOrNull(sourceIndex)?.url ?: return
        SourceHealth.markWorking(url)
        if (variants.size > 1 && variantIndex == variants.size - 1) SourceHealth.markTsOnly(url)
    }

    private fun onError(why: String) {
        val e = item ?: return
        Log.w("falhou ${e.name} fonte ${sourceIndex + 1} (${currentUrl}): $why")
        val src = sources.getOrNull(sourceIndex) ?: return
        if (gotFrame && live && liveRetries < 8) {
            // canal que já estava tocando e caiu: reabre a mesma fonte
            liveRetries++
            start(src, 0)
            return
        }
        if (variantIndex + 1 < variants.size) { variantIndex++; start(src, 0); return }
        SourceHealth.markFailed(src.url)
        if (sourceIndex + 1 < sources.size) playSource(sourceIndex + 1, if (live) 0 else time) else giveUp()
    }

    private fun giveUp() {
        status = null
        error = if (sources.size > 1) "Nenhuma das ${sources.size} fontes abriu agora. Tente de novo daqui a pouco." else "Este vídeo não abriu agora. Tente de novo daqui a pouco."
    }

    private fun onEnd() {
        val e = item ?: return
        if (live) { onError("transmissão terminou"); return }
        // servidor devolveu página em vez de vídeo (ou caiu antes da 1ª imagem): é erro, não fim
        if (!gotFrame) { onError("terminou sem imagem"); return }
        if (length > 0) UserLibrary.salvarPosicao(e.url, length, length)
        val next = nextEpisode
        if (next != null && Settings.value.proximoEpisodio) onPlayItem?.invoke(next) else onFinished?.invoke()
    }

    fun retry() { if (sources.isEmpty()) item?.let { open(it) } else playSource(0, if (live) 0 else time) }
    fun nextSource() { if (sources.size > 1) playSource((sourceIndex + 1) % sources.size) }

    fun togglePause() { mp?.let { p -> p.submit { if (p.status().isPlaying) p.controls().pause() else p.controls().play() } } }
    fun seekTo(ms: Long) {
        if (live) return
        val t = ms.coerceIn(0, (length - 1000).coerceAtLeast(0))
        time = t
        mp?.let { p -> p.submit { p.controls().setTime(t) } }
    }
    fun seekBy(ms: Long) = seekTo(time + ms)

    fun changeVolume(v: Int) {
        volume = v.coerceIn(0, 150)
        muted = false
        mp?.audio()?.setVolume(volume)
        mp?.audio()?.isMute = false
        Settings.edit { it.copy(volume = volume) }
    }
    fun toggleMute() { muted = !muted; mp?.audio()?.isMute = muted }

    class Track(val id: Int, val name: String)
    fun audioTracks(): List<Track> = mp?.audio()?.trackDescriptions().orEmpty().map { Track(it.id(), it.description()) }
    fun audioTrack(): Int = mp?.audio()?.track() ?: -1
    fun setAudioTrack(id: Int) { mp?.audio()?.setTrack(id) }
    fun subtitleTracks(): List<Track> = mp?.subpictures()?.trackDescriptions().orEmpty().map { Track(it.id(), it.description()) }
    fun subtitleTrack(): Int = mp?.subpictures()?.track() ?: -1
    fun setSubtitleTrack(id: Int) { mp?.subpictures()?.setTrack(id) }

    fun saveProgress() {
        val e = item ?: return
        if (e.type == ContentType.LIVE || !gotFrame) return
        if (time > 0 && length > 0) UserLibrary.salvarPosicao(e.url, time, length)
    }

    /** Sai do player (para o vídeo, guarda onde parou). */
    fun stop() {
        saveProgress()
        saver?.cancel()
        attempt?.cancel()
        item = null
        currentUrl = null
        playing = false
        Repository.playbackActive = false
        mp?.let { p -> p.submit { p.controls().stop() } }
        frames.clear()
    }

    fun release() {
        stop()
        scope.cancel()
        runCatching { mp?.release() }
        mp = null
    }
}
