// Copiado de app/src/main/java/dev/guisousa/minhatv/player/PlayerControls.kt (vai para o módulo core depois)
// Adaptado para toque: escala pela densidade da tela, botão de voltar, barra de tempo arrastável,
// botões com rolagem lateral e painel que fecha ao tocar fora.
package dev.guisousa.badwolf.mobile.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.TextView

/** No celular a escala segue a densidade da tela (um pouco menor que o dp da TV). */
fun Context.ui(v: Float): Float = v * resources.displayMetrics.density * 0.85f
fun Context.ui(v: Int): Int = ui(v.toFloat()).toInt()
fun TextView.textSizeUi(sp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, context.ui(sp))

/** Ícones simples desenhados em código (sem emoji), que ficam pretos quando o botão está em foco. */
class IconDrawable(private val kind: Kind, private val sizePx: Int) : Drawable() {
    enum class Kind { PLAY, PAUSE, BACK10, FWD10, PREV, NEXT }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private var focused = false

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_pressed)
        if (f == focused) return false
        focused = f
        paint.color = if (f) Color.BLACK else Color.WHITE
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val x = b.left.toFloat()
        val y = b.top.toFloat()
        val p = Path()
        fun tri(x0: Float, pointRight: Boolean, tw: Float) {
            if (pointRight) { p.moveTo(x0, y + h * 0.18f); p.lineTo(x0 + tw, y + h / 2); p.lineTo(x0, y + h * 0.82f) }
            else { p.moveTo(x0 + tw, y + h * 0.18f); p.lineTo(x0, y + h / 2); p.lineTo(x0 + tw, y + h * 0.82f) }
            p.close()
        }
        when (kind) {
            Kind.PLAY -> tri(x + w * 0.25f, true, w * 0.6f)
            Kind.PAUSE -> {
                canvas.drawRect(x + w * 0.22f, y + h * 0.18f, x + w * 0.42f, y + h * 0.82f, paint)
                canvas.drawRect(x + w * 0.58f, y + h * 0.18f, x + w * 0.78f, y + h * 0.82f, paint)
                return
            }
            Kind.BACK10 -> { tri(x + w * 0.08f, false, w * 0.42f); tri(x + w * 0.5f, false, w * 0.42f) }
            Kind.FWD10 -> { tri(x + w * 0.08f, true, w * 0.42f); tri(x + w * 0.5f, true, w * 0.42f) }
            Kind.PREV -> { canvas.drawRect(x + w * 0.12f, y + h * 0.18f, x + w * 0.24f, y + h * 0.82f, paint); tri(x + w * 0.26f, false, w * 0.6f) }
            Kind.NEXT -> { tri(x + w * 0.14f, true, w * 0.6f); canvas.drawRect(x + w * 0.76f, y + h * 0.18f, x + w * 0.88f, y + h * 0.82f, paint) }
        }
        canvas.drawPath(p, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Barra de controles do player, no estilo da prévia: título em cima, tempo e botões embaixo. */
class PlayerControls(private val context: Context, root: FrameLayout) {

    private fun dp(v: Int) = context.ui(v)

    val top: LinearLayout
    val bottom: LinearLayout
    val kicker: TextView
    val title: TextView
    val epgLine: TextView
    val timeRow: LinearLayout
    val position: TextView
    val duration: TextView
    val progress: SeekBar
    val buttons: LinearLayout
    /** Botão de voltar no canto de cima (sai do vídeo). */
    var onBack: (() -> Unit)? = null
    /** Soltou a barra de tempo: posição de 0 a 1. */
    var onSeek: ((Float) -> Unit)? = null
    /** Mexeu na barra (para não esconder os controles enquanto arrasta). */
    var onInteract: (() -> Unit)? = null
    /** true enquanto o dedo está na barra de tempo. */
    var dragging = false
        private set

    var visible = false
        private set

    init {
        top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9000000.toInt(), 0x00000000))
            setPadding(dp(12), dp(12), dp(24), dp(40))
            visibility = View.GONE
        }
        kicker = label(13f, 0xFFA3A3A3.toInt())
        title = label(20f, Color.WHITE, bold = true)
        epgLine = label(14f, 0xFFD4D4D4.toInt())
        val back = TextView(context).apply {
            text = "\u2190"
            setTextColor(Color.WHITE)
            textSizeUi(26f)
            gravity = Gravity.CENTER
            contentDescription = "Voltar"
            background = bg(round = true)
            isClickable = true
            setOnClickListener { onBack?.invoke() }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        texts.addView(kicker); texts.addView(title); texts.addView(epgLine)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(back, LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(row)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
            setPadding(dp(20), dp(48), dp(20), dp(16))
            visibility = View.GONE
        }
        timeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        position = label(13f, 0xFFA3A3A3.toInt())
        duration = label(13f, 0xFFA3A3A3.toInt())
        progress = SeekBar(context).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFF00E5FF.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF00E5FF.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) onInteract?.invoke() }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true; onInteract?.invoke() }
                override fun onStopTrackingTouch(sb: SeekBar) { dragging = false; onSeek?.invoke(sb.progress / 1000f) }
            })
        }
        timeRow.addView(position)
        timeRow.addView(progress, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        timeRow.addView(duration)
        bottom.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // muitos botões numa tela estreita: a fileira rola para o lado
        buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(buttons) }
        bottom.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        textSizeUi(sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun bg(round: Boolean): StateListDrawable {
        val r = if (round) dp(28).toFloat() else dp(4).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r })
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r })
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x2EFFFFFF); cornerRadius = r })
        }
    }

    private val textColors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE),
    )

    /** Botão redondo só com ícone. */
    fun iconButton(kind: IconDrawable.Kind, description: String, onClick: () -> Unit): TextView = TextView(context).apply {
        background = bg(round = true)
        isFocusable = true
        isClickable = true
        contentDescription = description
        gravity = Gravity.CENTER
        setCompoundDrawables(IconDrawable(kind, dp(22)).also { it.setBounds(0, 0, dp(22), dp(22)) }, null, null, null)
        setPadding(dp(15), dp(15), dp(15), dp(15))
        setOnClickListener { onClick() }
    }

    /** Botão com texto. */
    fun textButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        background = bg(round = false)
        isFocusable = true
        isClickable = true
        this.text = text
        setTextColor(textColors)
        textSizeUi(15f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

    fun setButtons(left: List<View>, right: List<View>) {
        buttons.removeAllViews()
        left.forEach { buttons.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }) }
        buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(12) })
        right.forEach { buttons.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }) }
    }

    /** Atualiza a barra de tempo (não mexe enquanto o dedo está nela). */
    fun setProgress(permille: Int) {
        if (!dragging) progress.progress = permille
    }

    fun show(focus: View?) {
        top.visibility = View.VISIBLE
        bottom.visibility = View.VISIBLE
        if (!visible) focus?.requestFocus()
        visible = true
    }

    fun hide() {
        top.visibility = View.GONE
        bottom.visibility = View.GONE
        visible = false
    }

    // ---------- Painel de escolhas (legendas, áudio, fonte, canais...) ----------

    data class Choice(val label: String, val detail: String? = null, val selected: Boolean = false)

    /** Fundo escuro atrás do painel: tocar fora fecha (menos nas perguntas, que pedem uma escolha). */
    private val scrim = View(context).apply {
        setBackgroundColor(0x66000000)
        visibility = View.GONE
        isClickable = true
        setOnClickListener { if (onPanelClosed == null) hidePanel() }
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xF20A0A0A.toInt())
        setPadding(dp(16), dp(20), dp(16), dp(16))
        visibility = View.GONE
        isClickable = true
    }
    private val panelTitle = label(20f, Color.WHITE, bold = true).apply { setPadding(dp(12), 0, dp(12), dp(16)) }
    private val panelList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val panelScroll = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(panelList) }
    var panelVisible = false
        private set
    private var onPanelClosed: (() -> Unit)? = null

    init {
        panel.addView(panelTitle)
        panel.addView(panelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val m = context.resources.displayMetrics
        val w = minOf((420 * m.density).toInt(), (minOf(m.widthPixels, m.heightPixels) * 0.85f).toInt().coerceAtLeast((280 * m.density).toInt()))
        root.addView(panel, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    /** Abre o painel do lado direito. O foco começa no item escolhido agora. [onClosed] só roda se fechar com VOLTAR. */
    fun showPanel(title: String, items: List<Choice>, onClosed: (() -> Unit)? = null, onPick: (Int) -> Unit) {
        panelTitle.text = title
        panelList.removeAllViews()
        var focusView: View? = null
        items.forEachIndexed { i, c ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(0x55FFFFFF); cornerRadius = dp(6).toFloat() })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(if (c.selected) 0x2EFFFFFF else 0x00000000); cornerRadius = dp(6).toFloat() })
                }
                isClickable = true
                minimumHeight = (48 * context.resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { onPanelClosed = null; hidePanel(); onPick(i) }
            }
            val main = label(16f, Color.WHITE, bold = c.selected).apply { text = c.label }
            val sub = c.detail?.let { d -> label(13f, 0xFFA3A3A3.toInt()).apply { text = d } }
            row.addView(main)
            sub?.let { row.addView(it) }
            if (c.selected) main.setTextColor(0xFF00E5FF.toInt())
            panelList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            if (c.selected && focusView == null) focusView = row
        }
        onPanelClosed = onClosed
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panelVisible = true
        hide()
        val target = focusView
        if (target != null) panelScroll.post { panelScroll.scrollTo(0, (target.top - dp(120)).coerceAtLeast(0)) } else panelScroll.scrollTo(0, 0)
    }

    fun hidePanel() {
        scrim.visibility = View.GONE
        panel.visibility = View.GONE
        panelVisible = false
        onPanelClosed?.invoke()
        onPanelClosed = null
    }
}
