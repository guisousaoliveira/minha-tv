package dev.guisousa.minhatv.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import dev.guisousa.minhatv.data.Log
import java.awt.Window

/**
 * Tela cheia "sem borda": a janela perde a barra de título e as bordas e cobre o monitor onde está.
 * Diferente da tela cheia exclusiva do Java, ela não sai sozinha quando você clica em outro monitor
 * ou em outro programa (igual ao vídeo em tela cheia do navegador).
 */
object TelaCheia {
    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = size()
        @JvmField var rcMonitor = Rect()
        @JvmField var rcWork = Rect()
        @JvmField var dwFlags = 0
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
    }

    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_EX_DLGMODALFRAME = 0x00000001L
    private const val WS_EX_WINDOWEDGE = 0x00000100L
    private const val WS_EX_CLIENTEDGE = 0x00000200L
    private const val WS_EX_STATICEDGE = 0x00020000L
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val SW_RESTORE = 9
    private const val SW_MAXIMIZE = 3

    private val user32: User32? by lazy {
        runCatching { Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS) }
            .onFailure { Log.e("user32 indisponível", it) }.getOrNull()
    }

    private var ativa = false
    private var estilo = 0L
    private var estiloEx = 0L
    private var maximizada = false
    private val antes = Rect()

    /** Liga ou desliga. Devolve false se não deu (aí quem chamou usa a tela cheia normal). */
    fun definir(janela: Window, ligar: Boolean): Boolean {
        val u = user32 ?: return false
        val hwnd = runCatching { Native.getWindowPointer(janela) }.getOrNull() ?: return false
        if (ligar == ativa) return true
        return runCatching {
            if (ligar) {
                maximizada = u.IsZoomed(hwnd)
                if (maximizada) u.ShowWindow(hwnd, SW_RESTORE)
                u.GetWindowRect(hwnd, antes)
                estilo = u.GetWindowLongPtrW(hwnd, GWL_STYLE)
                estiloEx = u.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
                u.SetWindowLongPtrW(hwnd, GWL_STYLE, estilo and (WS_CAPTION or WS_THICKFRAME).inv())
                u.SetWindowLongPtrW(hwnd, GWL_EXSTYLE,
                    estiloEx and (WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE).inv())
                val info = MonitorInfo()
                val mon = u.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: error("sem monitor")
                u.GetMonitorInfoW(mon, info)
                val r = info.rcMonitor
                u.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top,
                    SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
            } else {
                u.SetWindowLongPtrW(hwnd, GWL_STYLE, estilo)
                u.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, estiloEx)
                u.SetWindowPos(hwnd, null, antes.left, antes.top, antes.right - antes.left, antes.bottom - antes.top,
                    SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
                if (maximizada) u.ShowWindow(hwnd, SW_MAXIMIZE)
            }
            ativa = ligar
            true
        }.onFailure { Log.e("tela cheia sem borda falhou", it) }.getOrDefault(false)
    }
}
