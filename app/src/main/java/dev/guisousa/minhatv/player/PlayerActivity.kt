package dev.guisousa.minhatv.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TrackSelectionDialogBuilder
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista que o player vai tocar (fica em memória para não estourar o limite do Intent). */
object PlaybackQueue {
    var items: List<Entry> = emptyList()
    var index: Int = 0
    /** Muda quando a lista é trocada por uma mais completa enquanto o player já está aberto. */
    var version: Int = 0
        private set

    fun start(context: Context, items: List<Entry>, index: Int) {
        if (items.isEmpty()) return
        this.items = items
        this.index = index.coerceIn(0, items.size - 1)
        version++
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    /** Abre já com [partial] e troca pela categoria completa assim que carregar (para ▲▼ funcionar em tudo). */
    fun startLoadingGroup(context: Context, partial: List<Entry>, index: Int, loader: suspend () -> List<Entry>) {
        start(context, partial, index)
        val mine = version
        val url = partial.getOrNull(index)?.url
        MainScope().launch {
            val full = runCatching { loader() }.getOrNull() ?: return@launch
            val i = full.indexOfFirst { it.url == url }
            if (version == mine && i >= 0) {
                items = full
                this@PlaybackQueue.index = i
                version++
            }
        }
    }
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var infoPanel: LinearLayout
    private lateinit var infoNumber: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var loadingBox: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var toast: TextView
    private lateinit var controls: PlayerControls
    private var btnPlay: TextView? = null
    private var btnSource: TextView? = null
    private var btnFav: TextView? = null
    private var firstButton: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoPanel.visibility = View.GONE }
    private val hideToast = Runnable { toast.visibility = View.GONE }
    private val showLoading = Runnable { loadingBox.visibility = View.VISIBLE }
    private val stallCheck = Runnable { onStall() }

    private var items: List<Entry> = emptyList()
    private var index = 0
    private var queueVersion = 0

    /** Fonte tocando agora: o próprio item ou uma alternativa (mesmo nome em outra lista). */
    private var playing: Entry? = null
    private var sources: List<Entry> = emptyList()
    private var sourceIndex = 0
    private var sourcesJob: Job? = null
    private var retries = 0

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val current get() = items[index]
    private val isLive get() = current.type == ContentType.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        items = PlaybackQueue.items
        index = PlaybackQueue.index
        queueVersion = PlaybackQueue.version
        if (items.isEmpty()) { finish(); return }
        buildViews()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()
        val exo = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("pt", "por", "pt-BR")
            .build()
        // TVs que trocam a taxa de quadros sem piscar passam a mostrar filme 24 fps sem "tremidas"
        exo.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) {
                android.util.Log.i("MinhaTV", "player: $dropped quadros perdidos em ${elapsedMs}ms")
            }
            override fun onVideoInputFormatChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, f: androidx.media3.common.Format, d: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                android.util.Log.i("MinhaTV", "player: vídeo ${f.width}x${f.height} ${f.frameRate} fps ${f.sampleMimeType}")
            }
            override fun onPlaybackStateChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) {
                if (state == Player.STATE_BUFFERING) android.util.Log.i("MinhaTV", "player: carregando (buffer)")
            }
        })
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        handler.postDelayed(showLoading, 400)
                        handler.removeCallbacks(stallCheck)
                        handler.postDelayed(stallCheck, if (isLive) 15_000 else 30_000)
                    }
                    Player.STATE_READY -> {
                        retries = 0
                        hideLoading()
                    }
                    Player.STATE_ENDED -> {
                        hideLoading()
                        if (!isLive && index < items.size - 1) play(index + 1)
                    }
                    Player.STATE_IDLE -> hideLoading()
                }
            }
        })
        play(index)
        playerView.requestFocus()
    }

    // ---------------- Interface ----------------

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            useController = false
            keepScreenOn = true
            // sempre começa no formato original do vídeo
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            subtitleView?.apply {
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
                setStyle(CaptionStyleCompat(Color.WHITE, 0x99000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT_BOLD))
            }
        }
        root.addView(playerView, match())

        // painel de informações do canal (parte de baixo)
        infoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF0000000.toInt(), 0x00000000))
            setPadding(dp(56), dp(80), dp(56), dp(40))
            visibility = View.GONE
        }
        infoNumber = text(14f, 0xFFA3A3A3.toInt())
        infoTitle = text(30f, Color.WHITE, bold = true)
        infoNow = text(17f, Color.WHITE)
        infoNext = text(15f, 0xFFA3A3A3.toInt())
        listOf(infoNumber, infoTitle, infoNow, infoNext).forEach { infoPanel.addView(it) }
        root.addView(infoPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        controls = PlayerControls(this, root)

        // "Carregando…" numa pílula no canto de cima: não fica por cima dos controles do vídeo
        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xE6141414.toInt()); cornerRadius = dp(24).toFloat() }
            setPadding(dp(16), dp(10), dp(20), dp(10))
            addView(ProgressBar(this@PlayerActivity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        loadingText = text(15f, Color.WHITE).apply {
            maxLines = 1
            setSingleLine(true)
            setPadding(dp(12), 0, 0, 0)
            text = "Carregando…"
        }
        loadingBox.addView(loadingText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(loadingBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(32), dp(40), 0)
        })

        // avisos curtos (canto superior)
        toast = text(15f, Color.WHITE).apply {
            background = GradientDrawable().apply { setColor(0xE6141414.toInt()); cornerRadius = dp(4).toFloat() }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            visibility = View.GONE
        }
        toast.maxLines = 3
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(320), dp(88), dp(40), 0)
        })
        setContentView(root)
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        textSizeUi(sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun showInfo(ms: Long = 5000) {
        val e = current
        val now = System.currentTimeMillis()
        val epg = Repository.epg.value
        infoNumber.text = if (isLive) "${index + 1}  ·  ${e.group}" else e.group
        infoTitle.text = e.name
        val cur = if (isLive) epg.current(e, now) else null
        val next = if (isLive) epg.next(e, now) else null
        infoNow.text = cur?.let { "Agora   ${timeFmt.format(Date(it.start))} – ${timeFmt.format(Date(it.stop))}   ${it.title}" } ?: ""
        infoNow.visibility = if (cur != null) View.VISIBLE else View.GONE
        val extra = mutableListOf<String>()
        next?.let { extra += "Depois   ${timeFmt.format(Date(it.start))}   ${it.title}" }
        if (sources.size > 1) extra += "Fonte ${sourceIndex + 1} de ${sources.size}"
        if (e.url in Repository.favorites.value) extra += "Na Minha lista"
        infoNext.text = extra.joinToString("\n")
        infoNext.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
        infoPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, ms)
    }

    private fun notice(text: String, ms: Long = 3000) {
        toast.text = text
        toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, ms)
    }

    private fun hideLoading() {
        handler.removeCallbacks(showLoading)
        handler.removeCallbacks(stallCheck)
        loadingBox.visibility = View.GONE
        loadingText.text = "Carregando…"
    }

    // ---------------- Reprodução ----------------

    private fun play(i: Int) {
        saveResume()
        syncQueue()
        index = ((i % items.size) + items.size) % items.size
        retries = 0
        val e = current
        Repository.addRecent(e)
        if (isLive) Repository.lastChannelUrl = e.url
        sources = listOf(e)
        sourceIndex = 0
        playSource(e, resume = true)
        setupControls()
        if (isLive) showInfo() else { infoPanel.visibility = View.GONE; showControls() }
        // procura outras fontes do mesmo canal/filme em segundo plano
        sourcesJob?.cancel()
        val wanted = index
        sourcesJob = lifecycleScope.launch {
            val alts = Repository.alternates(e)
            if (index == wanted && alts.isNotEmpty()) { sources = listOf(e) + alts; updateButtons() }
        }
    }

    private fun playSource(src: Entry, resume: Boolean) {
        val exo = player ?: return
        playing = src
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(src.userAgent ?: Repository.DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        src.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val path = src.url.lowercase().substringBefore('?')
        val item = MediaItem.Builder()
            .setUri(src.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(src.name).build())
            .apply {
                when {
                    path.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()
        exo.setMediaSource(factory.createMediaSource(item))
        exo.prepare()
        if (resume && !isLive) {
            val pos = Repository.getResume(current.url)
            if (pos > 0) exo.seekTo(pos)
        }
        exo.playWhenReady = true
        handler.postDelayed(showLoading, 400)
    }

    private fun handleError(error: PlaybackException) {
        val exo = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition(); exo.prepare(); return
        }
        if (retries < 2) {
            retries++
            loadingText.text = "Reconectando…"
            loadingBox.visibility = View.VISIBLE
            handler.postDelayed({ player?.prepare() }, 1200L * retries)
            return
        }
        if (nextSource()) return
        hideLoading()
        notice(if (isLive) "Canal fora do ar agora. Aperte OK e escolha outro em Canais." else "Não foi possível abrir este conteúdo.", 8000)
    }

    /** Pula para a próxima fonte do mesmo item. Devolve false se não houver mais. */
    private fun nextSource(): Boolean {
        if (sourceIndex >= sources.size - 1) return false
        handler.post { updateButtons() }
        sourceIndex++
        retries = 0
        val pos = player?.currentPosition ?: 0
        loadingText.text = "Tentando outra fonte · ${sourceIndex + 1} de ${sources.size}"
        loadingBox.visibility = View.VISIBLE
        playSource(sources[sourceIndex], resume = false)
        if (!isLive && pos > 0) player?.seekTo(pos)
        return true
    }

    /** Ficou carregando tempo demais: reconecta ou tenta outra fonte. */
    private fun onStall() {
        val exo = player ?: return
        if (exo.playbackState != Player.STATE_BUFFERING) return
        if (!nextSource()) {
            loadingText.text = "Reconectando…"
            if (isLive) { exo.seekToDefaultPosition() }
            exo.prepare()
            handler.postDelayed(stallCheck, 20_000)
        }
    }

    /** Se a lista foi completada depois de abrir (ver PlaybackQueue), passa a usar a completa. */
    private fun syncQueue() {
        if (PlaybackQueue.version == queueVersion || items.isEmpty()) return
        val url = items.getOrNull(index)?.url
        val full = PlaybackQueue.items
        val i = full.indexOfFirst { it.url == url }
        if (i >= 0) { items = full; index = i }
        queueVersion = PlaybackQueue.version
    }

    // ---------------- Menu de opções ----------------

    private fun showOptions() {
        val exo = player ?: return
        val fav = current.url in Repository.favorites.value
        val options = mutableListOf<Pair<PlayerControls.Choice, () -> Unit>>()
        if (isLive) options += PlayerControls.Choice("Canais", current.group) to { channelsPanel() }
        options += PlayerControls.Choice("Legendas") to { pickTrack(C.TRACK_TYPE_TEXT) }
        options += PlayerControls.Choice("Áudio") to { pickTrack(C.TRACK_TYPE_AUDIO) }
        options += PlayerControls.Choice("Formato da tela", resizeName(playerView.resizeMode)) to { resizePanel() }
        if (!isLive) options += PlayerControls.Choice("Velocidade", speedName(exo.playbackParameters.speed)) to { speedDialog() }
        options += PlayerControls.Choice("Trocar fonte", if (sources.size > 1) "Fonte ${sourceIndex + 1} de ${sources.size}" else "Só tem uma") to { sourceDialog() }
        options += PlayerControls.Choice(if (fav) "Tirar da Minha lista" else "Adicionar à Minha lista") to {
            val added = Repository.toggleFavorite(current.url)
            notice(if (added) "Adicionado à Minha lista" else "Removido da Minha lista")
        }
        if (isLive) options += PlayerControls.Choice("Recarregar canal") to { play(index) }
        controls.showPanel(current.name, options.map { it.first }) { options[it].second() }
    }

    /** Lista as faixas de legenda ou áudio num painel do lado (sem o diálogo padrão do Android). */
    private fun trackDialog(title: String, type: Int) {
        val exo = player ?: return
        data class Opt(val group: androidx.media3.common.Tracks.Group?, val track: Int)
        val opts = mutableListOf<Pair<PlayerControls.Choice, Opt>>()
        val disabled = exo.trackSelectionParameters.disabledTrackTypes.contains(type)
        if (type == C.TRACK_TYPE_TEXT) opts += PlayerControls.Choice("Desligadas", selected = disabled || exo.currentTracks.groups.none { it.type == type && it.isSelected }) to Opt(null, -1)
        exo.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val lang = f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("pt", "BR")).replaceFirstChar { c -> c.uppercase() } }
                val name = f.label ?: lang ?: "Faixa ${opts.size + 1}"
                val detail = listOfNotNull(
                    if (f.label != null) lang else null,
                    if (f.channelCount > 0 && type == C.TRACK_TYPE_AUDIO) (if (f.channelCount >= 6) "5.1" else "Estéreo") else null,
                ).joinToString(" · ").ifBlank { null }
                opts += PlayerControls.Choice(name, detail, selected = !disabled && g.isTrackSelected(i)) to Opt(g, i)
            }
        }
        controls.showPanel(title, opts.map { it.first }) { which ->
            val o = opts[which].second
            val b = exo.trackSelectionParameters.buildUpon()
            if (o.group == null) b.setTrackTypeDisabled(type, true)
            else b.setTrackTypeDisabled(type, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(o.group.mediaTrackGroup, o.track))
            exo.trackSelectionParameters = b.build()
        }
    }

    /** Canais da mesma categoria, com o que está passando agora. */
    private fun channelsPanel() {
        val epg = Repository.epg.value
        val now = System.currentTimeMillis()
        val choices = items.mapIndexed { i, e ->
            PlayerControls.Choice("${i + 1}   ${e.name}", epg.current(e, now)?.title, selected = i == index)
        }
        controls.showPanel(current.group, choices) { i -> if (i != index) play(i) }
    }

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )

    private fun resizeName(mode: Int) = when (mode) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Preencher (corta as bordas)"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Esticar"
        else -> "Original"
    }

    private fun resizePanel() {
        controls.showPanel("Formato da tela", resizeModes.map { PlayerControls.Choice(resizeName(it), selected = it == playerView.resizeMode) }) { i ->
            playerView.resizeMode = resizeModes[i]
        }
    }

    private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private fun speedName(s: Float) = if (s == 1f) "normal" else "${s}x"

    private fun speedDialog() {
        val cur = player?.playbackParameters?.speed ?: 1f
        controls.showPanel("Velocidade", speeds.map { PlayerControls.Choice(speedName(it), selected = it == cur) }) { i ->
            player?.playbackParameters = PlaybackParameters(speeds[i])
        }
    }

    private fun sourceDialog() {
        if (sources.size <= 1) { notice("Não achei outra fonte para este item."); return }
        val labels = sources.mapIndexed { i, s -> PlayerControls.Choice("Fonte ${i + 1}", s.name, selected = i == sourceIndex) }
        controls.showPanel("Trocar fonte", labels) { i ->
            sourceIndex = i
            retries = 0
            playSource(sources[i], resume = true)
            updateButtons()
        }
    }

    // ---------------- Controles na tela ----------------

    private val hideControlsTask = Runnable { controls.hide() }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            if (controls.visible) handler.postDelayed(this, 500)
        }
    }

    private fun setupControls() {
        val c = controls
        val left = mutableListOf<View>()
        if (isLive) {
            left += c.iconButton(IconDrawable.Kind.PREV, "Canal anterior") { play(index - 1) }
            left += c.iconButton(IconDrawable.Kind.NEXT, "Próximo canal") { play(index + 1) }
            left += c.textButton("Canais") { channelsPanel() }
            btnPlay = null
        } else {
            left += c.iconButton(IconDrawable.Kind.BACK10, "Voltar 10 segundos") { seekBy(-10_000) }
            btnPlay = c.iconButton(IconDrawable.Kind.PAUSE, "Pausar") { togglePlay() }.also { left += it }
            left += c.iconButton(IconDrawable.Kind.FWD10, "Avançar 10 segundos") { seekBy(10_000) }
        }
        val right = mutableListOf<View>()
        right += c.textButton("Legendas") { pickTrack(C.TRACK_TYPE_TEXT) }
        right += c.textButton("Áudio") { pickTrack(C.TRACK_TYPE_AUDIO) }
        btnSource = c.textButton("Fonte") { sourceDialog() }.also { right += it }
        right += c.textButton("Tela") { resizePanel() }
        if (!isLive) right += c.textButton("Velocidade") { speedDialog() }
        btnFav = c.textButton("Minha lista") {
            val added = Repository.toggleFavorite(current.url)
            notice(if (added) "Adicionado à Minha lista" else "Removido da Minha lista")
            updateButtons()
        }.also { right += it }
        c.setButtons(left, right)
        firstButton = btnPlay ?: left.firstOrNull()
        c.timeRow.visibility = if (isLive) View.GONE else View.VISIBLE
        updateButtons()
    }

    private fun updateButtons() {
        val exo = player
        btnPlay?.let { b ->
            val playing = exo?.playWhenReady == true
            val icon = IconDrawable(if (playing) IconDrawable.Kind.PAUSE else IconDrawable.Kind.PLAY, dp(20)).also { it.setBounds(0, 0, dp(20), dp(20)) }
            b.setCompoundDrawables(icon, null, null, null)
            b.contentDescription = if (playing) "Pausar" else "Continuar"
        }
        btnSource?.text = if (sources.size > 1) "Fonte ${sourceIndex + 1}/${sources.size}" else "Fonte"
        btnFav?.text = if (items.isNotEmpty() && current.url in Repository.favorites.value) "Na Minha lista" else "+ Minha lista"
        val e = items.getOrNull(index) ?: return
        controls.kicker.text = if (isLive) "${index + 1}  ·  ${e.group}" else e.group
        controls.title.text = e.name
        val cur = if (isLive) Repository.epg.value.current(e, System.currentTimeMillis()) else null
        controls.epgLine.text = cur?.let { "Agora · ${it.title}  (${timeFmt.format(Date(it.start))} – ${timeFmt.format(Date(it.stop))})" } ?: ""
        controls.epgLine.visibility = if (cur != null) View.VISIBLE else View.GONE
    }

    private fun updateTime() {
        val exo = player ?: return
        val dur = exo.duration
        val pos = exo.currentPosition
        controls.position.text = fmtTime(pos)
        controls.duration.text = if (dur > 0) fmtTime(dur) else "--:--"
        controls.progress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
    }

    private fun fmtTime(ms: Long): String {
        val t = ms / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun showControls(ms: Long = 5000) {
        infoPanel.visibility = View.GONE
        updateButtons()
        val wasVisible = controls.visible
        controls.show(firstButton)
        if (!wasVisible) { handler.removeCallbacks(tick); handler.post(tick) }
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, ms)
    }

    private fun togglePlay() {
        val exo = player ?: return
        exo.playWhenReady = !exo.playWhenReady
        updateButtons()
    }

    private fun seekBy(delta: Long) {
        val exo = player ?: return
        if (isLive) return
        val dur = exo.duration
        var target = exo.currentPosition + delta
        if (dur > 0) target = target.coerceAtMost(dur - 1000)
        exo.seekTo(target.coerceAtLeast(0))
        updateTime()
    }

    private fun pickTrack(type: Int) {
        val exo = player ?: return
        val has = exo.currentTracks.groups.any { it.type == type }
        if (!has) {
            notice(if (type == C.TRACK_TYPE_TEXT) "Este conteúdo não tem legendas embutidas." else "Sem outras faixas de áudio.")
            return
        }
        trackDialog(if (type == C.TRACK_TYPE_TEXT) "Legendas" else "Áudio", type)
    }

    // ---------------- Controle remoto ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (items.isEmpty()) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

        // teclas de mídia funcionam sempre
        if (down) when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); showControls(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { play(index + 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(index - 1); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showOptions(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { pickTrack(C.TRACK_TYPE_TEXT); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showControls(8000); return true }
        }

        if (controls.panelVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) controls.hidePanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (controls.visible) {
            // com a barra aberta, as setas andam entre os botões e o OK aperta
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) controls.hide()
                return true
            }
            if (down) { handler.removeCallbacks(hideControlsTask); handler.postDelayed(hideControlsTask, 5000) }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true
            return super.dispatchKeyEvent(event)
        }

        // barra fechada
        if (!down) return center || super.dispatchKeyEvent(event)
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { showControls(); return true }
            // ▲ ▼ nunca trocam de canal: só mostram o que está passando (ou a barra, no filme)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (isLive) showInfo(8000) else showControls(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (isLive) showInfo() else { seekBy(-10_000); showControls() }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isLive) showInfo() else { seekBy(10_000); showControls() }; return true }
            KeyEvent.KEYCODE_BACK -> if (infoPanel.visibility == View.VISIBLE) { infoPanel.visibility = View.GONE; return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun saveResume() {
        val exo = player ?: return
        if (items.isEmpty() || isLive) return
        Repository.saveResume(current.url, exo.currentPosition, exo.duration)
    }

    override fun onStart() {
        super.onStart()
        Repository.playbackActive = true
        player?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.playWhenReady = true }
    }

    override fun onStop() {
        super.onStop()
        Repository.playbackActive = false
        saveResume()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun dp(v: Int) = ui(v)

    @Suppress("unused")
    private fun unusedToast() = Toast.LENGTH_SHORT
}
