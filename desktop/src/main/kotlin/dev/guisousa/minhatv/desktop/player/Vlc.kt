package dev.guisousa.minhatv.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import dev.guisousa.minhatv.data.Log
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * Carrega o libVLC que vai junto com o app (pasta "vlc" nos recursos do instalador).
 * Se não achar (rodando de um jeito estranho), tenta o VLC instalado no Windows.
 */
object Vlc {
    @Volatile var erro: String? = null
        private set

    val factory: MediaPlayerFactory? by lazy {
        try {
            val res = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
            if (res != null && File(res, "libvlc.dll").exists()) {
                System.setProperty("jna.library.path", res.absolutePath)
                Log.i("VLC embutido em $res")
            } else {
                Log.w("VLC embutido não encontrado ($res); procurando o VLC instalado")
            }
            if (!NativeDiscovery().discover()) Log.w("NativeDiscovery não achou o VLC")
            MediaPlayerFactory(
                "--quiet", "--no-video-title-show", "--no-osd", "--no-snapshot-preview", "--no-stats",
                "--no-sub-autodetect-file", "--avcodec-hw=any", "--aout=mmdevice,directsound",
            ).also { Log.i("libVLC ${it.application().version()}") }
        } catch (t: Throwable) {
            Log.e("não consegui carregar o VLC", t)
            erro = "Não consegui iniciar o player (VLC): ${t.message}"
            null
        }
    }
}

private interface CLib : Library {
    fun memcpy(dst: Pointer, src: Pointer, n: Long): Pointer
    companion object { val I: CLib = Native.load("msvcrt", CLib::class.java) }
}

/**
 * Recebe os quadros do VLC (BGRA) e guarda em dois Bitmaps do Skia (um sendo escrito, outro na tela).
 * Assim o vídeo é desenhado pelo próprio Compose e os controles ficam por cima.
 */
class VideoFrames(private val onFrame: () -> Unit) {
    @Volatile var front: Bitmap? = null
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var frames = 0L
        private set

    private var back: Bitmap? = null
    private val lock = Any()

    val formatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            // limita a 1920 de largura: menos memória e cópia por quadro (o VLC reduz sozinho)
            var w = sourceWidth.coerceAtLeast(2)
            var h = sourceHeight.coerceAtLeast(2)
            if (w > 1920) { h = (h * 1920L / w).toInt(); w = 1920 }
            w = w and 1.inv(); h = h and 1.inv()
            synchronized(lock) {
                val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                back = Bitmap().apply { allocPixels(info) }
                front = Bitmap().apply { allocPixels(info) }
                width = w; height = h
            }
            return RV32BufferFormat(w, h)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
    }

    val renderCallback = object : RenderCallback {
        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
            val src = nativeBuffers.firstOrNull() ?: return
            synchronized(lock) {
                val b = back ?: return
                val pm = b.peekPixels() ?: return
                val n = minOf(src.capacity().toLong(), pm.rowBytes.toLong() * height)
                CLib.I.memcpy(Pointer(pm.addr), Native.getDirectBufferPointer(src), n)
                b.notifyPixelsChanged()
                back = front
                front = b
                frames++
            }
            onFrame()
        }
    }

    /** Usa o quadro da frente sem o VLC trocar no meio do desenho. */
    fun <T> withFront(block: (Bitmap) -> T): T? = synchronized(lock) { front?.let(block) }

    fun clear() = synchronized(lock) { front = null; back = null; width = 0; height = 0; frames = 0 }
}
