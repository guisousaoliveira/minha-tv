package dev.guisousa.minhatv.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.LinearLayout

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Context.field(parent: LinearLayout, hint: String, type: Int): EditText =
    EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        parent.addView(this)
    }

/** Texto digitado no diálogo de link (volta se o diálogo fechar sem querer). */
private var draft = arrayOf("", "", "")

private fun isEnter(action: Int, ev: KeyEvent?, vararg ok: Int) =
    action in ok || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_UP)

/** VOLTAR com o teclado aberto só fecha o teclado (não fecha o diálogo nem perde o texto). */
private fun AlertDialog.backClosesKeyboardFirst() {
    setOnKeyListener { _, code, ev ->
        if (code != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
        val view = currentFocus ?: return@setOnKeyListener false
        val imeOpen = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (!imeOpen) return@setOnKeyListener false
        if (ev.action == KeyEvent.ACTION_UP) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        }
        true
    }
}

/** Diálogo para digitar o link com o controle remoto (ou teclado). */
fun showAddLinkDialog(context: Context, onSave: (url: String, name: String?, epg: String?) -> Unit) {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(20)
        setPadding(p, p / 2, p, 0)
    }
    val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    val url = context.field(layout, "Link da playlist ou da página (https://...)", uri)
    val name = context.field(layout, "Nome (opcional)", InputType.TYPE_CLASS_TEXT)
    val epg = context.field(layout, "Guia XMLTV (opcional)", uri)
    val fields = listOf(url, name, epg)
    fields.forEachIndexed { i, f -> f.setText(draft[i]); f.setSelection(f.text.length) }
    val save = {
        onSave(url.text.toString(), name.text.toString(), epg.text.toString())
        draft = arrayOf("", "", "")
    }
    val dialog = AlertDialog.Builder(context, dev.guisousa.minhatv.R.style.Theme_MinhaTV_Dialog)
        .setTitle("Adicionar link")
        .setView(layout)
        .setPositiveButton("Adicionar") { _, _ -> save() }
        .setNegativeButton("Cancelar") { _, _ -> draft = arrayOf("", "", "") }
        .create()
    // fechou com VOLTAR: guarda o que foi digitado para a próxima vez
    dialog.setOnCancelListener { draft = fields.map { it.text.toString() }.toTypedArray() }
    dialog.backClosesKeyboardFirst()
    // com o teclado aberto o controle não alcança o botão: o OK do teclado adiciona direto
    // (nome e guia são opcionais; quem quiser preenche e o OK deles também adiciona)
    fields.forEach { f ->
        f.imeOptions = EditorInfo.IME_ACTION_DONE
        f.setSingleLine(true)
        f.setOnEditorActionListener { _, action, ev ->
            val enter = isEnter(action, ev, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT)
            if (enter && url.text.isNotBlank()) { save(); dialog.dismiss() }
            enter
        }
    }
    dialog.show()
    url.requestFocus()
}

fun showSearchDialog(context: Context, initial: String, onSearch: (String) -> Unit) {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(20)
        setPadding(p, p / 2, p, 0)
    }
    val q = context.field(layout, "Nome do canal, filme ou série", InputType.TYPE_CLASS_TEXT)
    q.setText(initial)
    q.setSelection(q.text.length)
    val dialog = AlertDialog.Builder(context, dev.guisousa.minhatv.R.style.Theme_MinhaTV_Dialog)
        .setTitle("Buscar")
        .setView(layout)
        .setPositiveButton("Buscar") { _, _ -> onSearch(q.text.toString()) }
        .setNegativeButton("Cancelar", null)
        .show()
    dialog.backClosesKeyboardFirst()
    // o Enter / "Buscar" do teclado da tela já faz a busca (com o teclado aberto o controle não alcança o botão)
    q.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    q.setSingleLine(true)
    q.setOnEditorActionListener { _, action, ev ->
        val enter = action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO || (ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_UP)
        if (enter) { onSearch(q.text.toString()); dialog.dismiss() }
        enter
    }
    q.requestFocus()
}

fun confirm(context: Context, title: String, message: String, yes: String, onYes: () -> Unit) {
    AlertDialog.Builder(context, dev.guisousa.minhatv.R.style.Theme_MinhaTV_Dialog)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton("Cancelar", null)
        .show()
}
