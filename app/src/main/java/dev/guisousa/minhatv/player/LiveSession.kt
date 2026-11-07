package dev.guisousa.minhatv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Um player só para o ao vivo, dividido entre a prévia (quadro na tela de canais) e a tela cheia.
 * Abrir o canal que já está na prévia só "amplia" o vídeo que está tocando, e voltar "minimiza":
 * nada é carregado de novo. Filme e série continuam com um player próprio (ver [buildPlayer]).
 */
@OptIn(UnstableApi::class)
object LiveSession {
    private var exo: ExoPlayer? = null

    /** Canal carregado agora (endereço do catálogo, "v2:c:<id>"). */
    @Volatile var channelUrl: String? = null
    /** Fonte (link original do canal) e o endereço que está tocando (pode ser a versão .m3u8 dele). */
    @Volatile var sourceUrl: String? = null
    @Volatile var playedUrl: String? = null
    /** A tela cheia está usando o player: a prévia não troca nem para nada. */
    @Volatile var fullscreen = false
    /** Quantos quadros de prévia estão na tela (sem nenhum, sair da tela cheia para o vídeo). Só na thread principal. */
    var previews = 0
    /** Quadro da prévia na tela e se o vídeo está sendo desenhado nele agora (a tela cheia desenha na dela). */
    var previewTexture: java.lang.ref.WeakReference<android.view.TextureView>? = null
    var onPreview = false

    /** Passa a imagem para o quadro da prévia (se houver um). Só na thread principal. */
    fun showOnPreview(): Boolean {
        val tv = previewTexture?.get() ?: return false
        val p = exo ?: return false
        if (!onPreview) p.setVideoTextureView(tv)
        onPreview = true
        return true
    }

    fun player(context: Context): ExoPlayer = exo ?: buildPlayer(context.applicationContext, live = true).also { exo = it }

    /** O canal [channel] está carregado e sem erro (tocando ou carregando). */
    fun isPlaying(channel: String): Boolean {
        val p = exo ?: return false
        return channelUrl == channel && p.mediaItemCount > 0 && p.playerError == null &&
            p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
    }

    fun loaded(channel: String, source: String, played: String) {
        channelUrl = channel; sourceUrl = source; playedUrl = played
    }

    /** Para o vídeo (libera a conexão com o servidor; o player fica para a próxima vez). */
    fun stop() {
        exo?.let { it.stop(); it.clearMediaItems() }
        channelUrl = null; sourceUrl = null; playedUrl = null
    }
}

/**
 * Player com a configuração do app. Ao vivo: reserva igual à do Saimo (32 s / 120 s / 1,5 s / 5 s, 96 MB);
 * filme: reserva grande. Aparelho com pouca memória: 15 / 30 / 1,5 / 3 s e 32 MB.
 * Mínimo = máximo de puxar sinal: a conexão nunca fica ociosa (o servidor do canal derrubava depois de ~40 s).
 */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, live: Boolean): ExoPlayer {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
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
    val exo = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setBandwidthMeter(androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(8_000_000L).build())
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
    return exo
}
