package dev.guisousa.minhatv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista que o player vai tocar (fica em memória para não estourar o limite do Intent). */
object PlaybackQueue {
    var items: List<Entry> = emptyList()
    var index: Int = 0

    fun start(context: Context, items: List<Entry>, index: Int) {
        if (items.isEmpty()) return
        this.items = items
        this.index = index.coerceIn(0, items.size - 1)
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = TextView.GONE }
    private var items: List<Entry> = emptyList()
    private var index = 0
    private var retries = 0
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val current get() = items[index]
    private val isLive get() = current.type == ContentType.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        items = PlaybackQueue.items
        index = PlaybackQueue.index
        if (items.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4000
            keepScreenOn = true
        }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setBackgroundColor(0xCC10141C.toInt())
            val p = dp(18)
            setPadding(p, p / 2, p, p / 2)
            visibility = TextView.GONE
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(40), dp(32), dp(40), 0) })
        setContentView(root)

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition(); exo.prepare(); return
                }
                if (retries < 3) {
                    retries++
                    handler.postDelayed({ exo.prepare() }, 1500L * retries)
                    showOverlay("Reconectando… ($retries)")
                    return
                }
                showOverlay("Não foi possível reproduzir este conteúdo.\n${error.errorCodeName}", 8000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                if (state == Player.STATE_ENDED && !isLive && index < items.size - 1) play(index + 1)
            }
        })
        play(index)
        playerView.requestFocus()
    }

    private fun play(i: Int) {
        val exo = player ?: return
        saveResume()
        index = ((i % items.size) + items.size) % items.size
        retries = 0
        val e = current
        if (e.type == ContentType.LIVE) Repository.lastChannelUrl = e.url

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(e.userAgent ?: Repository.DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        e.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

        val path = e.url.lowercase().substringBefore('?')
        val item = MediaItem.Builder()
            .setUri(e.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.name).build())
            .apply {
                when {
                    path.endsWith(".m3u8") || path.endsWith(".m3u") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()
        exo.setMediaSource(factory.createMediaSource(item))
        exo.prepare()
        if (!isLive) {
            val resume = Repository.getResume(e.url)
            if (resume > 0) exo.seekTo(resume)
        }
        exo.playWhenReady = true
        showOverlay(describe(e))
    }

    private fun describe(e: Entry): String {
        if (e.type != ContentType.LIVE) return e.name
        val sb = StringBuilder("${index + 1}  ${e.name}")
        val now = System.currentTimeMillis()
        val epg = Repository.epg.value
        epg.current(e, now)?.let { sb.append("\nAgora: ${it.title} (até ${timeFmt.format(Date(it.stop))})") }
        epg.next(e, now)?.let { sb.append("\n${timeFmt.format(Date(it.start))}  ${it.title}") }
        if (e.url in Repository.favorites.value) sb.append("\n★ Favorito")
        return sb.toString()
    }

    private fun showOverlay(text: String, ms: Long = 4000) {
        overlay.text = text
        overlay.visibility = TextView.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, ms)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && items.isNotEmpty()) {
            val controls = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { play(index + 1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(index - 1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (isLive && !controls) { play(index - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (isLive && !controls) { play(index + 1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (isLive && !controls) { showOverlay(describe(current)); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_PROG_RED -> {
                    val added = Repository.toggleFavorite(current.url)
                    showOverlay(if (added) "★ Adicionado aos favoritos" else "Removido dos favoritos")
                    return true
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showOverlay(describe(current), 8000); return true }
            }
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
        player?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.playWhenReady = true }
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
