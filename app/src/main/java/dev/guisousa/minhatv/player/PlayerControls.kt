package dev.guisousa.minhatv.player

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
import android.widget.ProgressBar
import android.widget.TextView

/** Ícones simples desenhados em código (sem emoji), que ficam pretos quando o botão está em foco. */
class IconDrawable(private val kind: Kind, private val sizePx: Int) : Drawable() {
    enum class Kind { PLAY, PAUSE, BACK10, FWD10, PREV, NEXT }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private var focused = false

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
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

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    val top: LinearLayout
    val bottom: LinearLayout
    val kicker: TextView
    val title: TextView
    val epgLine: TextView
    val timeRow: LinearLayout
    val position: TextView
    val duration: TextView
    val progress: ProgressBar
    val buttons: LinearLayout

    var visible = false
        private set

    init {
        top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9000000.toInt(), 0x00000000))
            setPadding(dp(48), dp(32), dp(360), dp(56))
            visibility = View.GONE
        }
        kicker = label(13f, 0xFFA3A3A3.toInt())
        title = label(24f, Color.WHITE, bold = true)
        epgLine = label(15f, 0xFFD4D4D4.toInt())
        top.addView(kicker); top.addView(title); top.addView(epgLine)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
            setPadding(dp(48), dp(64), dp(48), dp(32))
            visibility = View.GONE
        }
        timeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        position = label(13f, 0xFFA3A3A3.toInt())
        duration = label(13f, 0xFFA3A3A3.toInt())
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        }
        timeRow.addView(position)
        timeRow.addView(progress, LinearLayout.LayoutParams(0, dp(6), 1f).apply { setMargins(dp(14), 0, dp(14), 0) })
        timeRow.addView(duration)
        bottom.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })

        buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(buttons)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun bg(round: Boolean): StateListDrawable {
        val r = if (round) dp(28).toFloat() else dp(4).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r })
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x2EFFFFFF); cornerRadius = r })
        }
    }

    private val textColors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(Color.BLACK, Color.WHITE),
    )

    /** Botão redondo só com ícone. */
    fun iconButton(kind: IconDrawable.Kind, description: String, onClick: () -> Unit): TextView = TextView(context).apply {
        background = bg(round = true)
        isFocusable = true
        isClickable = true
        contentDescription = description
        gravity = Gravity.CENTER
        setCompoundDrawables(IconDrawable(kind, dp(20)).also { it.setBounds(0, 0, dp(20), dp(20)) }, null, null, null)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { onClick() }
    }

    /** Botão com texto. */
    fun textButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        background = bg(round = false)
        isFocusable = true
        isClickable = true
        this.text = text
        setTextColor(textColors)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        setPadding(dp(18), dp(11), dp(18), dp(11))
        setOnClickListener { onClick() }
    }

    fun setButtons(left: List<View>, right: List<View>) {
        buttons.removeAllViews()
        left.forEach { buttons.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }) }
        buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        right.forEach { buttons.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) }) }
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
}
