package dev.guisousa.minhatv.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Context.field(parent: LinearLayout, hint: String, type: Int): EditText =
    EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        parent.addView(this)
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
    AlertDialog.Builder(context, dev.guisousa.minhatv.R.style.Theme_MinhaTV_Dialog)
        .setTitle("Adicionar link")
        .setView(layout)
        .setPositiveButton("Adicionar") { _, _ ->
            onSave(url.text.toString(), name.text.toString(), epg.text.toString())
        }
        .setNegativeButton("Cancelar", null)
        .show()
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
