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
import dev.guisousa.minhatv.data.SourceHealth
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
        // fecha a prévia do ao vivo antes (o servidor conta cada conexão como uma tela)
        LivePreview.stop()
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
    /** Linha de baixo do "Carregando": qual fonte está sendo aberta. */
    private lateinit var loadingSource: TextView
    private lateinit var toast: TextView
    private lateinit var controls: PlayerControls
    private var btnPlay: TextView? = null
    private var btnSource: TextView? = null
    private var btnVersion: TextView? = null
    private var btnFav: TextView? = null
    private var firstButton: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoPanel.visibility = View.GONE }
    private val hideToast = Runnable { toast.visibility = View.GONE }
    // com um painel aberto (ex.: "Continuar de..."), o aviso de carregando não aparece por cima do título dele
    private val showLoading = Runnable { if (!controls.panelVisible) loadingBox.visibility = View.VISIBLE }
    private val stallCheck = Runnable { onStall() }

    /**
     * Vigia de imagem congelada: algumas fontes dizem que estão tocando, mas a imagem para e não volta.
     * A cada 2 s confere se o vídeo andou; se ficar 8 s parado, troca de fonte sozinho.
     */
    private var lastPos = -1L
    private var lastFrames = -1
    private var frozenFor = 0L
    private val freezeWatch = object : Runnable {
        override fun run() {
            val exo = player
            val state = exo?.playbackState
            if (exo != null && exo.playWhenReady && !reconnecting) {
                if (isLive) {
                    liveWatch(exo)
                } else if (state == Player.STATE_READY) {
                    // só a posição decide: o contador de quadros não é confiável em todas as TVs
                    val pos = exo.currentPosition
                    if (lastPos >= 0 && pos == lastPos) frozenFor += 2000 else if (frozenFor > 0 || lastPos >= 0) frozenFor = 0
                    lastPos = pos
                } else {
                    frozenFor = 0
                }
                if (!isLive && frozenFor >= 8000) {
                    frozenFor = 0
                    android.util.Log.w("MinhaTV", "player: imagem parada (estado $state), reconectando")
                    reconnect("O vídeo travou, reconectando…")
                }
            } else {
                frozenFor = 0
            }
            handler.postDelayed(this, 2000)
        }
    }

    /** Evita que o vigia e o fim da transmissão reconectem duas vezes ao mesmo tempo. */
    private var reconnecting = false
    /** Se a fonte atual já chegou a tocar (para saber se o erro foi na abertura). */
    private var readyOnce = false
    /** Alguma fonte deste canal já mostrou imagem (então erro depois é sessão vencida, não canal morto). */
    private var sourcePlayed = false
    private var markedWorking: String? = null
    private var reopenFails = 0
    private var wraps = 0
    @Volatile private var lastReopenAt = 0L
    private var lastReconnectAt = 0L
    private var limitRetried = false
    private val liveEnds = ArrayDeque<Long>()

    // ---- Ao vivo, regras copiadas do player do Saimo TV ----
    /** Reaberturas seguidas da mesma fonte (máx. 8). Zera quando fica pronto ou o vídeo anda. */
    private var sameReopens = 0
    /** Última vez que o canal estava tocando (pronto ou posição andando). */
    private var lastPlayingAt = 0L
    /** Já apareceu a primeira imagem desta fonte. */
    private var firstFrame = false
    /** Rodadas em que todas as fontes falharam (máx. 6, espera min(1,6^n s, 15 s)). */
    private var rounds = 0
    private var livePos = -1L
    private var liveStillMs = 0L
    private val tlWindow = androidx.media3.common.Timeline.Window()

    /** Bytes de vídeo que chegaram da internet (para saber se o servidor está respondendo, mesmo devagar). */
    private val bytesIn = java.util.concurrent.atomic.AtomicLong()
    private val transfer = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, net: Boolean) {}
        override fun onTransferStart(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, net: Boolean) {}
        override fun onBytesTransferred(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, net: Boolean, n: Int) { if (net) bytesIn.addAndGet(n.toLong()) }
        override fun onTransferEnd(s: androidx.media3.datasource.DataSource, d: androidx.media3.datasource.DataSpec, net: Boolean) {}
    }
    private var firstWaitStart = 0L
    private var stallBytes = 0L
    private var stallExtends = 0
    private var bytesAtCheck = 0L
    /** Filme/série: rodadas extras quando nenhuma fonte abriu sem erro claro (servidor lento, não morto). */
    private var vodRounds = 0

    /** Ao vivo: 12 s sem a primeira imagem vai para a próxima fonte. Filme: 20 s (35 s na 2ª rodada),
     *  e enquanto o vídeo estiver chegando continua esperando, até 45 s (60 s na 2ª rodada). */
    private val firstFrameWatch: Runnable = Runnable {
        if (firstFrame || isFinishing) return@Runnable
        if (isLive) {
            android.util.Log.w("MinhaTV", "player: 12 s sem imagem, próxima fonte")
            nextOrRound(frozen = true)
        } else {
            val waited = System.currentTimeMillis() - firstWaitStart
            val now = bytesIn.get()
            val arriving = now > bytesAtCheck
            bytesAtCheck = now
            val limit = if (vodRounds > 0) 60_000L else 45_000L
            if (arriving && waited < limit) {
                android.util.Log.w("MinhaTV", "player: filme ${waited / 1000} s sem imagem, mas o vídeo está chegando: espera mais")
                loadingText.text = "Servidor lento, esperando o vídeo… ${waited / 1000} s"
                loadingBox.visibility = View.VISIBLE
                handler.postDelayed(firstFrameWatch, 5_000L)
                return@Runnable
            }
            android.util.Log.w("MinhaTV", "player: filme ${waited / 1000} s sem imagem, próxima fonte")
            if (!nextSource(frozen = true)) vodRetryOrGiveUp("Não foi possível abrir este conteúdo.")
        }
    }

    /** Filme/série: acabaram as fontes sem erro claro. Tenta todas mais uma vez, com mais paciência, antes de desistir. */
    private fun vodRetryOrGiveUp(message: String) {
        if (vodRounds >= 1 || sources.isEmpty()) { hideLoading(); notice(message, 8000); return }
        vodRounds++
        android.util.Log.w("MinhaTV", "player: nenhuma fonte abriu, tentando todas de novo com mais tempo")
        loadingText.text = "Nenhuma fonte respondeu a tempo. Tentando de novo com mais calma…"
        loadingBox.visibility = View.VISIBLE
        val mine = index
        handler.postDelayed({
            if (index != mine || isFinishing) return@postDelayed
            sourceIndex = -1
            retries = 0
            if (!nextSource()) { hideLoading(); notice(message, 8000) }
        }, 2_000L)
    }
    /** Filme/série: quantas vezes já reabriu na mesma posição depois de ter tocado (máx. 3). */
    private var vodReprepares = 0

    /** A cada 2 s: se deveria estar tocando e a posição não andou mais de 0,5 s por 12 s, reabre a mesma fonte. */
    private fun liveWatch(exo: ExoPlayer) {
        if (!firstFrame) return // quem cuida da abertura é o vigia da primeira imagem
        val tl = exo.currentTimeline
        val offset = if (tl.isEmpty) 0L else tl.getWindow(exo.currentMediaItemIndex, tlWindow).positionInFirstPeriodMs.coerceAtLeast(0)
        val pos = exo.currentPosition + offset
        if (livePos < 0 || pos - livePos > 500) {
            if (livePos >= 0) { sameReopens = 0; lastPlayingAt = System.currentTimeMillis() }
            livePos = pos; liveStillMs = 0
            return
        }
        liveStillMs += 2000
        if (liveStillMs >= 12_000) {
            liveStillMs = 0
            android.util.Log.w("MinhaTV", "player: imagem parada 12 s, reabrindo a mesma fonte")
            if (!reopenSame("O vídeo travou, reconectando…")) nextOrRound(frozen = true)
        }
    }

    /** Reabre a mesma fonte do zero, na hora. Devolve false se já tentou 8 vezes seguidas. */
    private fun reopenSame(message: String): Boolean {
        if (sameReopens >= 8) return false
        sameReopens++
        android.util.Log.w("MinhaTV", "player: reabrindo a mesma fonte ($sameReopens de 8)")
        loadingText.text = message
        loadingBox.visibility = View.VISIBLE
        val src = playing ?: current
        handler.post { if (!isFinishing) playSource(src, resume = false) }
        return true
    }

    /** Próxima fonte; se acabaram, nova rodada desde a primeira com espera min(1,6^n s, 15 s), até 6 rodadas. */
    private fun nextOrRound(frozen: Boolean) {
        sameReopens = 0
        if (nextSource(frozen)) return
        if (rounds >= 6) {
            hideLoading()
            player?.stop()
            notice("Este canal não abriu em nenhuma fonte. Aperte OK e escolha outro em Canais.", 15_000)
            return
        }
        rounds++
        val wait = (Math.pow(1.6, rounds.toDouble()) * 1000).toLong().coerceAtMost(15_000L)
        android.util.Log.w("MinhaTV", "player: todas as fontes falharam, rodada $rounds de 6 em ${wait}ms")
        loadingText.text = "Canal instável, tentando de novo em ${(wait + 999) / 1000} s"
        loadingBox.visibility = View.VISIBLE
        val mine = index
        handler.postDelayed({
            if (index != mine || isFinishing) return@postDelayed
            sourceIndex = -1
            if (!nextSource(frozen)) playSource(current, resume = false)
        }, wait)
    }

    /**
     * Abre a fonte de novo do zero (conexão nova). Se a mesma fonte cair 3 vezes em 1 minuto,
     * passa para a próxima; se não houver outra, volta para a primeira.
     */
    private fun reconnect(message: String) {
        val now = System.currentTimeMillis()
        liveEnds.addLast(now)
        while (liveEnds.isNotEmpty() && now - liveEnds.first() > 60_000) liveEnds.removeFirst()
        if (liveEnds.size >= 3) {
            liveEnds.clear()
            if (nextSource(frozen = true)) return
            if (sources.size > 1) { sourceIndex = -1; if (nextSource(frozen = true)) return }
        }
        reconnecting = true
        lastReconnectAt = now
        loadingText.text = message
        loadingBox.visibility = View.VISIBLE
        val src = playing ?: current
        handler.postDelayed({ reconnecting = false; playSource(src, resume = !isLive) }, 300)
    }

    private var items: List<Entry> = emptyList()
    private var index = 0
    private var queueVersion = 0

    /** Fonte tocando agora: o próprio item ou uma alternativa (mesmo nome em outra lista). */
    private var playing: Entry? = null
    private var sources: List<Entry> = emptyList()
    private var sourceIndex = 0
    /** Última fonte que já foi marcada como boa (para marcar uma vez só). */
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

        // mínimo = máximo: o player nunca para de puxar o sinal. Se parasse (como antes),
        // a conexão ficava ociosa uns 40 s e o servidor do canal derrubava, fazendo a imagem piscar.
        // ao vivo: reserva pequena (10 s) e começa rápido, como os apps de IPTV; filme: reserva grande
        val live = items[index].type == ContentType.LIVE
        // ao vivo igual ao Saimo: 32 s / 120 s / 1,5 s / 5 s e 96 MB; aparelho com pouca memória: 15/30/1,5/3 e 32 MB
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val lowRam = am.isLowRamDevice || mem.totalMem <= 1_610_612_736L
        val loadControl = DefaultLoadControl.Builder()
            .apply {
                when {
                    live && lowRam -> setBufferDurationsMs(15_000, 30_000, 1_500, 3_000).setTargetBufferBytes(32 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false)
                    live -> setBufferDurationsMs(32_000, 120_000, 1_500, 5_000).setTargetBufferBytes(96 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true)
                    lowRam -> setBufferDurationsMs(15_000, 30_000, 1_500, 3_000).setTargetBufferBytes(32 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false)
                    else -> setBufferDurationsMs(30_000, 50_000, 1_500, 3_000).setTargetBufferBytes(96 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true)
                }
            }
            .build()
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setBandwidthMeter(androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(8_000_000L).build())
            .setLivePlaybackSpeedControl(
                androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(1.0f).setFallbackMaxPlaybackSpeed(1.04f).build(),
            )
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("pt", "por", "pt-BR")
            .build()
        // TVs que trocam a taxa de quadros sem piscar passam a mostrar filme 24 fps sem "tremidas"
        exo.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) {
                android.util.Log.w("MinhaTV", "player: $dropped quadros perdidos em ${elapsedMs}ms")
            }
            override fun onVideoInputFormatChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, f: androidx.media3.common.Format, d: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                matchFrameRate(f.frameRate)
                android.util.Log.w("MinhaTV", "player: vídeo ${f.width}x${f.height} ${f.frameRate} fps ${f.sampleMimeType}")
            }
            override fun onLoadError(
                t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                info: androidx.media3.exoplayer.source.LoadEventInfo,
                data: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                val kind = when (data.dataType) {
                    C.DATA_TYPE_MANIFEST -> "playlist"
                    C.DATA_TYPE_MEDIA -> "pedaço de vídeo"
                    C.DATA_TYPE_MEDIA_INITIALIZATION -> "início do vídeo"
                    else -> "tipo ${data.dataType}"
                }
                val code = (error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                android.util.Log.w("MinhaTV", "player: falha ao baixar $kind (http=$code ${error.javaClass.simpleName})")
            }
            override fun onTracksChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
                fun count(type: Int) = tracks.groups.filter { it.type == type }.sumOf { it.length }
                val p = playing ?: current
                android.util.Log.i("MinhaTV", "player: faixas de áudio ${count(C.TRACK_TYPE_AUDIO)}, legendas ${count(C.TRACK_TYPE_TEXT)} · versão escolhida ${Repository.versionFor(current)}, versão do item ${Repository.versionOf(p) ?: "?"}")
            }

            override fun onPlaybackStateChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) {
                if (state == Player.STATE_BUFFERING) android.util.Log.w("MinhaTV", "player: carregando (buffer)")
            }
        })
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onRenderedFirstFrame() {
                firstFrame = true
                handler.removeCallbacks(firstFrameWatch)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        handler.postDelayed(showLoading, 400)
                        handler.removeCallbacks(stallCheck)
                        if (!isLive) { stallBytes = bytesIn.get(); handler.postDelayed(stallCheck, 12_000) }
                    }
                    Player.STATE_READY -> {
                        retries = 0
                        limitRetried = false
                        readyOnce = true
                        stallExtends = 0
                        sourcePlayed = true
                        playing?.url?.let { if (it != markedWorking) { markedWorking = it; SourceHealth.markWorking(it); if (!usingHlsVariant && it in hlsFailed) SourceHealth.markTsOnly(it) } }
                        reopenFails = 0
                        wraps = 0
                        sameReopens = 0
                        rounds = 0
                        lastPlayingAt = System.currentTimeMillis()
                        hideLoading()
                    }
                    Player.STATE_ENDED -> {
                        if (exo.mediaItemCount == 0) return
                        if (isLive) {
                            // o servidor encerrou a transmissão: ao vivo não tem fim, então reconecta na hora
                            android.util.Log.w("MinhaTV", "player: transmissão ao vivo encerrada pelo servidor, reconectando")
                            if (!reopenSame("Reconectando…")) nextOrRound(frozen = true)
                        } else {
                            hideLoading()
                            saveResume()
                            onVodEnded()
                        }
                    }
                    Player.STATE_IDLE -> hideLoading()
                }
            }
        })
        play(index)
        playerView.requestFocus()
        handler.postDelayed(freezeWatch, 4000)
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

        // "Carregando…" no meio da tela, com a fonte que está sendo aberta embaixo
        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xD9141414.toInt()); cornerRadius = dp(16).toFloat() }
            setPadding(dp(36), dp(24), dp(36), dp(22))
            addView(SpinnerView(this@PlayerActivity), LinearLayout.LayoutParams(dp(52), dp(52)))
        }
        loadingText = text(20f, Color.WHITE).apply {
            maxLines = 1
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            text = "Carregando…"
        }
        loadingSource = text(14f, 0xFFB3B3B3.toInt()).apply {
            maxLines = 1
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        loadingBox.addView(loadingText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        loadingBox.addView(loadingSource, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(loadingBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

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
        buildNextCard(root)
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
        extra += (if (sources.size > 1) "Fonte ${sourceIndex + 1} de ${sources.size}" else "Fonte única") + " · " + formatName()
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
        sourcePlayed = false; reopenFails = 0; wraps = 0; sameReopens = 0; rounds = 0; lastPlayingAt = 0L; vodReprepares = 0; vodRounds = 0
        hideNextCard()
        val e = current
        Repository.addRecent(e)
        if (isLive) Repository.lastChannelUrl = e.url
        sourcesJob?.cancel()
        player?.stop()
        if (!e.url.startsWith("v2:")) {
            sources = listOf(e)
            sourceIndex = 0
            playSource(e, resume = true)
        } else {
            // as fontes (links reais, já em ordem e com a versão escolhida primeiro) vêm do catálogo
            sources = emptyList()
            sourceIndex = 0
            playing = null
            loadingText.text = "Carregando…"
            loadingBox.visibility = View.VISIBLE
        }
        setupControls()
        if (isLive) showInfo() else { infoPanel.visibility = View.GONE; showControls() }
        if (!e.url.startsWith("v2:")) return
        val wanted = index
        sourcesJob = lifecycleScope.launch {
            val list = runCatching { Repository.sources(e) }.getOrDefault(emptyList())
            if (index != wanted) return@launch
            if (list.isEmpty()) {
                hideLoading()
                notice("Não achei fonte para este item (sem internet ou saiu do catálogo).", 8000)
                return@launch
            }
            sources = list
            sourceIndex = 0
            playSource(list[0], resume = true)
            updateButtons()
            loadingSource.text = sourceLine()
            // filme/série: a versão escolhida (Dublado/Legendado) não existe: avisa em vez de trocar calado
            if (!isLive) {
                val want = Repository.versionFor(e)
                val have = list.map { Repository.versionOrDub(it) }.toSet()
                if (want !in have) {
                    notice("Não tem a versão ${Repository.versionName(want)} deste título. Está tocando ${Repository.versionName(have.first())}.", 7000)
                }
            }
        }
    }

    /** "Fonte 2 de 5 · FHD · HLS · servidor.com" (embaixo do Carregando). */
    private fun sourceLine(): String {
        val src = playing ?: return ""
        val parts = mutableListOf(if (sources.size > 1) "Fonte ${sourceIndex + 1} de ${sources.size}" else "Fonte única")
        src.quality?.takeIf { it.isNotBlank() }?.let { parts += it }
        parts += formatName()
        runCatching { android.net.Uri.parse(src.url).host }.getOrNull()?.removePrefix("www.")?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** Formato que está tocando agora, para mostrar no botão da fonte. */
    private fun formatName(): String {
        if (usingHlsVariant) return "HLS"
        val p = (playing?.url ?: "").lowercase().substringBefore('?')
        return when {
            p.endsWith(".m3u8") || p.contains("/hls/") -> "HLS"
            p.endsWith(".mpd") -> "DASH"
            p.endsWith(".mp4") -> "MP4"
            p.endsWith(".mkv") -> "MKV"
            else -> "TS"
        }
    }

    /** Links em que a versão .m3u8 não abriu: usa o original direto. */
    private val hlsFailed = HashSet<String>()
    private var usingHlsVariant = false

    /** http://host/live/user/pass/123.ts ou http://host/user/pass/123 -> http://host/live/user/pass/123.m3u8 */
    private fun hlsVariant(url: String): String? {
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

    private fun playSource(src: Entry, resume: Boolean) {
        // item do catálogo (v2:...) não é link: toca a primeira fonte dele (ou busca as fontes de novo)
        if (src.url.startsWith("v2:")) {
            val first = sources.firstOrNull()
            if (first == null) play(index) else playSource(first, resume)
            return
        }
        val exo = player ?: return
        playing = src
        lastPos = -1L; lastFrames = -1; frozenFor = -4000 // dá uns segundos para começar
        readyOnce = false
        firstFrame = false
        livePos = -1L; liveStillMs = 0
        handler.removeCallbacks(firstFrameWatch)
        firstWaitStart = System.currentTimeMillis()
        bytesAtCheck = bytesIn.get()
        handler.postDelayed(firstFrameWatch, if (isLive) 12_000L else if (vodRounds > 0) 35_000L else 20_000L)
        // conexão igual à do Saimo: OkHttp, UA do Safari (se a lista não definir outro) e Accept */*
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PlayerHttp.client)
            .setUserAgent(src.userAgent ?: PlayerHttp.SAFARI_UA)
            .setDefaultRequestProperties(buildMap {
                put("Accept", "*/*")
                src.referrer?.let { put("Referer", it) }
            })
        // ao vivo de servidor Xtream: toca a versão HLS (.m3u8) do mesmo canal, como os apps de IPTV.
        // O .ts direto é cortado pelo servidor a cada poucos segundos; se o HLS não abrir, volta para o .ts.
        val hls = if (isLive && !hlsFailed.contains(src.url) && !SourceHealth.tsOnly(src.url)) hlsVariant(src.url) else null
        usingHlsVariant = hls != null
        val url = hls ?: src.url
        val path = url.lowercase().substringBefore('?')
        val base = DefaultDataSource.Factory(this, StableRedirectDataSource.Factory(http)).setTransferListener(transfer)
        // ao vivo: playlist que dá 404/403/410 não volta repetindo o mesmo endereço (é link com prazo).
        // Desiste na hora para reabrir o canal pelo link original, em vez de ficar ~24 s tentando com a tela parada.
        val retry404 = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val code = (info.exception as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (isLive && code != null && code in 400..499 && info.mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) return C.TIME_UNSET
                // versão HLS que o servidor não consegue montar (502/503 do outro lado): volta logo para o .ts, sem ~14 s de tentativas
                if (hls != null && !firstFrame && code != null && code >= 500 && info.mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) return C.TIME_UNSET
                // filme/série: link que dá 404/403 não volta; desiste na hora para ir à próxima fonte (antes: ~15 s de tela preta)
                if (!isLive && code != null && code in 400..499) return C.TIME_UNSET
                return super.getRetryDelayMsFor(info)
            }
        }
        val factory = DefaultMediaSourceFactory(base).setLoadErrorHandlingPolicy(retry404)
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                // ao vivo: sem atraso fixo (igual ao Saimo), quem decide é a própria playlist;
                // só nunca desacelera e acelera no máximo 4% para alcançar o vivo
                if (isLive) setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.04f).build(),
                )
            }

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
        var ask = false
        if (resume && !isLive) {
            val pos = Repository.getResume(current.url)
            if (pos > 0) { exo.seekTo(pos); ask = true }
        }
        exo.playWhenReady = !ask
        if (ask) askResume(Repository.getResume(current.url))
        loadingSource.text = sourceLine()
        handler.postDelayed(showLoading, 400)
        updateButtons()
    }

    private fun handleError(error: PlaybackException) {
        val exo = player ?: return
        if (isLive) { handleLiveError(error); return }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition(); exo.prepare(); return
        }
        // link morto (403/404/410, arquivo não existe, formato inválido): vai direto para a próxima fonte
        val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        val dead = http in setOf(404, 410) ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && (http == null || http in 400..499) ||
            error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        android.util.Log.w("MinhaTV", "player: erro ${error.errorCodeName} http=$http morto=$dead")
        // já estava tocando: reabre na mesma posição até 3 vezes, depois tenta outra fonte (igual ao Saimo)
        if (readyOnce) {
            if (vodReprepares < 3) {
                vodReprepares++
                android.util.Log.w("MinhaTV", "player: erro no meio do filme, reabrindo na mesma posição ($vodReprepares de 3)")
                loadingText.text = "Reconectando…"
                loadingBox.visibility = View.VISIBLE
                handler.postDelayed({ player?.prepare() }, 1000)
                return
            }
            if (nextSource()) return
            hideLoading()
            notice("Não foi possível continuar este conteúdo.", 8000)
            return
        }
        // ao vivo que já estava tocando e dá qualquer erro (404, playlist estranha, rede): é sessão vencida
        // no servidor, não canal morto. Reabre a MESMA fonte do zero. Só troca de fonte se a reabertura
        // falhar duas vezes seguidas antes de mostrar imagem.
        if (isLive && (readyOnce || (sourcePlayed && reopenFails < 2))) {
            if (!readyOnce) reopenFails++
            android.util.Log.w("MinhaTV", "player: erro no meio do ao vivo (${error.errorCodeName}), reabrindo a mesma fonte (tentativa ${reopenFails + 1})")
            loadingText.text = "Reconectando…"
            loadingBox.visibility = View.VISIBLE
            val src = playing ?: current
            handler.postDelayed({ playSource(src, resume = false) }, if (reopenFails == 0) 300L else 1500L)
            return
        }
        // a versão HLS não abriu: tenta o link original (.ts) antes de qualquer outra coisa
        if (usingHlsVariant && !readyOnce) {
            playing?.let { hlsFailed += it.url; android.util.Log.w("MinhaTV", "player: HLS não abriu (${error.errorCodeName}), usando o .ts"); playSource(it, resume = false) }
            return
        }
        // recusa (401/403) logo depois de reconectar: é o limite de telas do servidor contando a conexão velha.
        // Espera e tenta de novo, sem marcar a fonte como quebrada.
        val justReconnected = System.currentTimeMillis() - maxOf(lastReopenAt, lastReconnectAt) < 15_000
        if (http in setOf(401, 403) && justReconnected && !limitRetried) {
            limitRetried = true
            loadingText.text = "Reconectando…"
            loadingBox.visibility = View.VISIBLE
            handler.postDelayed({ playing?.let { playSource(it, resume = false) } }, 2500)
            return
        }
        if (dead) {
            if (nextSource()) return
            if (isLive) { retryFromStart(); return }
            hideLoading()
            notice("Este conteúdo está fora do ar em todas as fontes.", 8000)
            return
        }
        if (retries < 2) {
            retries++
            loadingText.text = "Reconectando…"
            loadingBox.visibility = View.VISIBLE
            handler.postDelayed({ player?.prepare() }, 1200L * retries)
            return
        }
        if (nextSource()) return
        if (isLive) { retryFromStart(); return }
        vodRetryOrGiveUp("Não foi possível abrir este conteúdo.")
    }

    /**
     * Erro no ao vivo, regra do Saimo: se o canal estava tocando há menos de 30 s (ou ficou para trás
     * da janela ao vivo), reabre a MESMA fonte na hora, até 8 vezes. Senão, próxima fonte.
     */
    private fun handleLiveError(error: PlaybackException) {
        val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        android.util.Log.w("MinhaTV", "player: erro ao vivo ${error.errorCodeName} http=$http")
        handler.removeCallbacks(firstFrameWatch)
        val recent = lastPlayingAt > 0 && System.currentTimeMillis() - lastPlayingAt < 30_000
        if ((recent || error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) && reopenSame("Reconectando…")) return
        // a versão HLS (.m3u8) do canal não abriu: tenta o link original (.ts) antes de trocar de fonte
        if (usingHlsVariant && !readyOnce) {
            playing?.let { hlsFailed += it.url; android.util.Log.w("MinhaTV", "player: HLS não abriu, usando o .ts"); playSource(it, resume = false) }
            return
        }
        nextOrRound(frozen = false)
    }

    /** Todas as fontes do canal falharam: nunca fica tela preta parada. Recomeça da primeira, esperando cada vez mais. */
    private fun retryFromStart() {
        wraps++
        val wait = (5_000L * wraps).coerceAtMost(30_000L)
        android.util.Log.w("MinhaTV", "player: todas as fontes falharam, tentando de novo em ${wait / 1000}s")
        loadingText.text = "Canal instável, tentando de novo em ${wait / 1000} s"
        loadingBox.visibility = View.VISIBLE
        notice("Aperte OK e escolha outro em Canais, se preferir.", 6000)
        val mine = index
        handler.postDelayed({
            if (index != mine || isFinishing) return@postDelayed
            sourceIndex = -1
            reopenFails = 0
            if (!nextSource()) playSource(current, resume = false)
        }, wait)
    }

    /** Fontes da versão escolhida primeiro, depois as que não dizem, por último a outra versão. */
    private fun byVersion(list: List<Entry>, v: String): List<Entry> =
        list.sortedBy { if (Repository.versionOrDub(it) == v) 0 else 1 }

    /** Troca de fonte mantendo a posição (e a pausa, se a pergunta de continuar estiver aberta). */
    private fun switchSource(i: Int) {
        val exo = player ?: return
        val wasPaused = !exo.playWhenReady
        val pos = if (readyOnce) exo.currentPosition else Repository.getResume(current.url)
        sourceIndex = i
        retries = 0
        vodReprepares = 0
        playSource(sources[i], resume = false)
        if (!isLive && pos > 0) exo.seekTo(pos)
        if (wasPaused && controls.panelVisible) exo.playWhenReady = false
    }

    private fun versionPanel() {
        // As listas só marcam o legendado: fonte sem marca conta como dublada
        fun ver(e: Entry) = Repository.versionOrDub(e)
        val have = sources.map { ver(it) }.toSet()
        val now = ver(playing ?: current)
        // só uma versão: não tem o que escolher (antes abria a lista e deixava "trocar" para a que não existe)
        if (have.size < 2) { notice("Este título só tem a versão ${Repository.versionName(have.firstOrNull() ?: now)}."); return }
        val options = listOf(Repository.DUB, Repository.LEG)
        controls.showPanel("Versão", options.map { v ->
            PlayerControls.Choice(Repository.versionName(v), if (v in have) null else "Não achei nesta lista", selected = v == now)
        }) { i ->
            val v = options[i]
            Repository.setVersionFor(current, v)
            val target = sources.indexOfFirst { ver(it) == v }
            if (target < 0) { notice("Não achei a versão ${Repository.versionName(v)} deste título."); return@showPanel }
            sources = byVersion(sources, v)
            switchSource(0)
            updateButtons()
        }
    }

    /** Pula para a próxima fonte do mesmo item. Devolve false se não houver mais. */
    private fun nextSource(frozen: Boolean = false): Boolean {
        // a fonte saiu sem nunca mostrar imagem: vai para o fim da fila nas próximas vezes
        sources.getOrNull(sourceIndex)?.url?.let { if (it != markedWorking && !it.startsWith("v2:")) SourceHealth.markFailed(it) }
        if (sourceIndex >= sources.size - 1) return false
        vodReprepares = 0
        handler.post { updateButtons() }
        sourceIndex++
        retries = 0
        val pos = player?.currentPosition ?: 0
        loadingText.text = (if (frozen) "O vídeo travou, indo para outra fonte" else "Fonte fora do ar, tentando outra") + " · ${sourceIndex + 1} de ${sources.size}"
        loadingBox.visibility = View.VISIBLE
        playSource(sources[sourceIndex], resume = false)
        if (!isLive && pos > 0) player?.seekTo(pos)
        return true
    }

    /** Ficou carregando tempo demais: reconecta ou tenta outra fonte. */
    private fun onStall() {
        val exo = player ?: return
        if (exo.playbackState != Player.STATE_BUFFERING) return
        // antes da primeira imagem quem decide é o vigia da abertura (que espera mais por servidor lento)
        if (!firstFrame) return
        // o vídeo continua chegando, só devagar: espera mais um pouco antes de trocar de fonte (até ~1 min)
        val got = bytesIn.get()
        if (got > stallBytes && stallExtends < 4) {
            stallBytes = got; stallExtends++
            android.util.Log.w("MinhaTV", "player: carregando há muito tempo, mas o vídeo está chegando: espera mais")
            handler.postDelayed(stallCheck, 12_000)
            return
        }
        stallExtends = 0
        if (!nextSource()) {
            reconnect("Reconectando…")
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
        val labels = sources.mapIndexed { i, s -> PlayerControls.Choice("Fonte ${i + 1}", s.quality ?: s.name, selected = i == sourceIndex) }
        controls.showPanel("Trocar fonte", labels) { i ->
            switchSource(i)
            updateButtons()
        }
    }

    // ---------------- Controles na tela ----------------

    // igual ao Saimo: some 5 s depois da última tecla, e nunca com o vídeo pausado
    private val hideControlsTask = Runnable { if (isLive || player?.playWhenReady != false) controls.hide() }
    /** O Voltar que fechou um painel não pode, ao soltar a tecla, fechar o player também. */
    private var backConsumed = false
    private var lastBackAt = 0L
    private var exitOnUp = false
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
        // "Versão" só aparece quando o título tem Dublado e Legendado (ver updateButtons)
        btnVersion = if (isLive) null else c.textButton("Versão") { versionPanel() }.also { right += it }
        btnSource = c.textButton("Fonte") { sourceDialog() }.also { right += it }
        right += c.textButton("Tela") { resizePanel() }
        if (!isLive) right += c.textButton("Velocidade") { speedDialog() }
        if (current.type == ContentType.SERIES && items.size > 1) right.add(0, c.textButton("Episódios") { episodesPanel() })
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
        btnSource?.text = (if (sources.size > 1) "Fonte ${sourceIndex + 1}/${sources.size}" else "Fonte") + " · " + formatName()
        btnVersion?.let { b ->
            val both = sources.map { Repository.versionOrDub(it) }.toSet().size > 1
            b.visibility = if (both) View.VISIBLE else View.GONE
            if (both) b.text = "Versão · " + Repository.versionName(Repository.versionOrDub(playing ?: current))
        }
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
        // com um painel aberto (ex.: "Continuar de..."), a barra não pode roubar o foco dele
        if (controls.panelVisible) return
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
        if (exo.playWhenReady && controls.visible) { handler.removeCallbacks(hideControlsTask); handler.postDelayed(hideControlsTask, 5000) }
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

        if (down) autoEpisodes = 0
        if (::nextCard.isInitialized && nextCard.visibility == View.VISIBLE && !controls.panelVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) { hideNextCard(); finish() }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (controls.panelVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) controls.hidePanel()
                return true
            }
            // o painel é uma lista vertical: ◀ ▶ não deixam o foco sair dele
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true
            return super.dispatchKeyEvent(event)
        }

        if (controls.visible) {
            // com a barra aberta, as setas andam entre os botões e o OK aperta
            if (code == KeyEvent.KEYCODE_BACK) {
                // um VOLTAR só sai do vídeo (ao soltar a tecla, para não fechar a tela de trás também)
                if (!down) { controls.hide(); saveResume(); finish() }
                return true
            }
            if (down) { handler.removeCallbacks(hideControlsTask); handler.postDelayed(hideControlsTask, 5000) }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true
            return super.dispatchKeyEvent(event)
        }

        // barra fechada
        if (!down) {
            if (code == KeyEvent.KEYCODE_BACK && exitOnUp) { exitOnUp = false; saveResume(); finish(); return true }
            if (code == KeyEvent.KEYCODE_BACK && backConsumed) { backConsumed = false; return true }
            return center || super.dispatchKeyEvent(event)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { showControls(); return true }
            // ▲ ▼ nunca trocam de canal: só mostram o que está passando (ou a barra, no filme)
            KeyEvent.KEYCODE_DPAD_UP -> { if (isLive) showInfo(8000) else showControls(); return true }
            // ▼ na série abre a lista de episódios (como no Saimo); nunca troca de canal
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    isLive -> showInfo(8000)
                    current.type == ContentType.SERIES && items.size > 1 -> episodesPanel()
                    else -> showControls()
                }
                return true
            }
            // setas nunca avançam nem voltam sozinhas: só abrem a barra (no filme) ou o "agora passando" (ao vivo)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isLive) showInfo() else showControls(); return true }
            KeyEvent.KEYCODE_BACK -> {
                infoPanel.visibility = View.GONE
                // um VOLTAR só sai do vídeo; sai ao SOLTAR a tecla (se saísse ao apertar, o soltar
                // chegava na tela de trás e fechava ela também)
                exitOnUp = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Filme em 24 quadros numa tela em 60 Hz dá aquela "tremidinha". Se a TV tiver um modo
     * de tela compatível (24, 48, 25, 50...), troca para ele enquanto o vídeo toca, como Netflix e Kodi.
     */
    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || android.os.Build.VERSION.SDK_INT < 23) return
        val display = window.decorView.display ?: return
        val current = display.mode
        fun fits(rate: Float): Boolean {
            val ratio = rate / fps
            val n = Math.round(ratio)
            return n >= 1 && kotlin.math.abs(ratio - n) < 0.01f
        }
        val modes = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        android.util.Log.w("MinhaTV", "player: vídeo a $fps fps, modos da tela: " + modes.joinToString { "%.2f".format(it.refreshRate) })
        if (fits(current.refreshRate) && Math.round(current.refreshRate / fps) <= 2) return
        val best = modes.filter { fits(it.refreshRate) }.minByOrNull { Math.round(it.refreshRate / fps) } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        android.util.Log.w("MinhaTV", "player: tela trocada para %.2f Hz".format(best.refreshRate))
    }

    private fun clock(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
    }

    /** Ao abrir algo já começado: "Continuar de mm:ss" ou "Começar do início" (igual ao Saimo). */
    private fun askResume(pos: Long) {
        controls.showPanel(
            current.name,
            listOf(PlayerControls.Choice("Continuar de ${clock(pos)}"), PlayerControls.Choice("Começar do início")),
            // VOLTAR aqui desiste de assistir e sai do vídeo
            onClosed = { saveResume(); finish() },
        ) { i ->
            if (i == 1) player?.seekTo(0)
            player?.playWhenReady = true
            updateButtons()
        }
    }

    private fun epLabel(e: Entry): String =
        if (e.season != null && e.episode != null) "T${e.season} E${e.episode}" else ""

    private fun nextEpisodeIndex(): Int? {
        val e = current
        if (e.type != ContentType.SERIES || index >= items.size - 1) return null
        val n = items[index + 1]
        return if (n.type == ContentType.SERIES && (e.seriesName == null || n.seriesName == e.seriesName)) index + 1 else null
    }

    /** Episódios tocados em sequência sem ninguém mexer no controle. */
    private var autoEpisodes = 0
    private var nextCountdown = 0
    private lateinit var nextCard: LinearLayout
    private lateinit var nextTitle: TextView
    private lateinit var nextTimer: TextView
    private var nextPlay: TextView? = null
    private val nextTick = object : Runnable {
        override fun run() {
            if (nextCard.visibility != View.VISIBLE) return
            nextCountdown--
            if (nextCountdown <= 0) { playNext(auto = true); return }
            nextTimer.text = "Começa em $nextCountdown s"
            handler.postDelayed(this, 1000)
        }
    }

    private fun onVodEnded() {
        val next = nextEpisodeIndex()
        if (next == null) { finish(); return }
        if (autoEpisodes >= 7) {
            // depois de 7 episódios seguidos sem tocar no controle, pergunta antes de continuar
            controls.showPanel(
                "Ainda está assistindo?",
                listOf(PlayerControls.Choice("Continuar assistindo"), PlayerControls.Choice("Sair")),
                onClosed = { finish() },
            ) { i -> if (i == 0) { autoEpisodes = 0; play(next) } else finish() }
            return
        }
        showNextCard(next)
    }

    private fun showNextCard(next: Int) {
        val n = items[next]
        nextTitle.text = listOf(epLabel(n), n.name).filter { it.isNotBlank() }.joinToString(" · ")
        nextCountdown = 10
        nextTimer.text = "Começa em 10 s"
        nextCard.visibility = View.VISIBLE
        nextPlay?.requestFocus()
        handler.removeCallbacks(nextTick)
        handler.postDelayed(nextTick, 1000)
    }

    private fun hideNextCard() {
        if (!::nextCard.isInitialized) return
        handler.removeCallbacks(nextTick)
        nextCard.visibility = View.GONE
    }

    private fun playNext(auto: Boolean) {
        val next = nextEpisodeIndex() ?: run { hideNextCard(); return }
        hideNextCard()
        if (auto) autoEpisodes++ else autoEpisodes = 0
        play(next)
    }

    private fun buildNextCard(root: FrameLayout) {
        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF0000000.toInt()); setStroke(dp(1), 0x26FFFFFF); cornerRadius = dp(6).toFloat() }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
        }
        nextCard.addView(text(12f, 0xFFB3B3B3.toInt()).apply { text = "PRÓXIMO EPISÓDIO" })
        nextTitle = text(17f, Color.WHITE, bold = true).apply { maxLines = 2; maxWidth = dp(420) }
        nextCard.addView(nextTitle)
        nextTimer = text(13f, 0xFFB3B3B3.toInt())
        nextCard.addView(nextTimer)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        nextPlay = controls.textButton("Assistir agora") { playNext(auto = false) }
        row.addView(nextPlay)
        row.addView(controls.textButton("Cancelar") { hideNextCard(); autoEpisodes = 0 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        nextCard.addView(row)
        root.addView(nextCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(48), dp(56))
        })
    }

    /** Episódios da série dentro do player. */
    private fun episodesPanel() {
        val choices = items.mapIndexed { i, e ->
            val p = Repository.progress(e.url)
            val detail = when {
                Repository.isWatched(e.url) -> "Visto"
                p != null -> "Parou em ${(p * 100).toInt()}%"
                else -> null
            }
            PlayerControls.Choice(listOf(epLabel(e), e.name).filter { it.isNotBlank() }.joinToString("  "), detail, selected = i == index)
        }
        controls.showPanel(current.seriesName ?: "Episódios", choices) { i -> if (i != index) { autoEpisodes = 0; play(i) } }
    }

    private fun saveResume() {
        val exo = player ?: return
        if (items.isEmpty() || isLive) return
        Repository.saveResume(current.url, exo.currentPosition, exo.duration)
    }

    override fun onStart() {
        super.onStart()
        Repository.playbackActive = true
        // sem vídeo ainda (fontes do catálogo carregando): prepare() vazio daria "fim do vídeo" e fecharia o player
        player?.let { if (it.mediaItemCount == 0) return; if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.playWhenReady = true }
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


/** Bolinha de carregando que gira mesmo com as animações do sistema desligadas (o ProgressBar padrão fica parado). */
class SpinnerView(context: Context) : View(context) {
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = 0x33FFFFFF }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = Color.WHITE; strokeCap = android.graphics.Paint.Cap.ROUND }
    private val rect = android.graphics.RectF()
    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width * 0.12f
        track.strokeWidth = w; arc.strokeWidth = w
        rect.set(w / 2, w / 2, width - w / 2, height - w / 2)
        canvas.drawArc(rect, 0f, 360f, false, track)
        val angle = (android.os.SystemClock.uptimeMillis() % 900L) / 900f * 360f
        canvas.drawArc(rect, angle, 100f, false, arc)
        if (isShown) postInvalidateOnAnimation()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
