package dev.guisousa.minhatv.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.minhatv.data.Conta
import dev.guisousa.minhatv.data.Nuvem
import kotlinx.coroutines.launch

/**
 * Modal dedicada de autenticação "Obsidian Cinema" (Conta W4LLE / Bad Wolf).
 * [initialTab]: 0 = Entrar, 1 = Criar conta.
 */
@Composable
fun AuthModal(
    initialTab: Int = 0,
    onDismiss: () -> Unit,
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, 1)) }
    var usuario by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var confirmarSenha by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val userFocus = remember { FocusRequester() }

    LaunchedEffect(tab) {
        erro = null
        runCatching { userFocus.requestFocus() }
    }
    DisposableEffect(Unit) {
        onDispose { campoDeTextoFocado = false }
    }

    fun fecharSemConta() {
        Conta.marcarBoasVindasVistas()
        onDismiss()
    }

    fun enviar() {
        if (ocupado) return
        erro = null
        if (tab == 1 && senha != confirmarSenha) {
            erro = "As senhas não coincidem."
            return
        }
        ocupado = true
        scope.launch {
            val res = if (tab == 0) {
                Conta.entrarComSenha(usuario, senha)
            } else {
                Conta.cadastrarComSenha(usuario, senha)
            }
            ocupado = false
            if (res == null) {
                senha = ""
                confirmarSenha = ""
                Conta.marcarBoasVindasVistas()
                Nuvem.sincronizar(forcar = true)
                onDismiss()
            } else {
                erro = res
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD606070A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!ocupado) fecharSemConta() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Hairline, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(28.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("logo_lobo.png"), null, Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Text("BAD WOLF", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Text(" TV", color = Palette.Brand, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                IconBtn(Ic.Close, { if (!ocupado) fecharSemConta() }, size = 34.dp, tint = Palette.TextDim)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (tab == 0) "Entre na sua conta" else "Crie sua conta grátis",
                color = Palette.Text,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Sincronize Minha lista, onde parou e já assisti entre TV, celular, PC e site.",
                color = Palette.TextDim,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )

            Spacer(Modifier.height(20.dp))

            // Segmented tabs: Entrar | Criar conta
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Background)
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AuthSegmentTab("Entrar", selected = tab == 0, modifier = Modifier.weight(1f)) {
                    if (!ocupado) tab = 0
                }
                AuthSegmentTab("Criar conta", selected = tab == 1, modifier = Modifier.weight(1f)) {
                    if (!ocupado) tab = 1
                }
            }

            Spacer(Modifier.height(18.dp))

            AuthFieldLabel("Usuário")
            Spacer(Modifier.height(6.dp))
            AuthInput(
                valor = usuario,
                onChange = { usuario = it; erro = null },
                dica = "Ex.: joaosilva",
                modifier = Modifier.fillMaxWidth().focusRequester(userFocus),
                onEnter = ::enviar,
            )

            Spacer(Modifier.height(14.dp))

            AuthFieldLabel("Senha")
            Spacer(Modifier.height(6.dp))
            AuthInput(
                valor = senha,
                onChange = { senha = it; erro = null },
                dica = if (tab == 1) "Mínimo de 6 caracteres" else "Digite sua senha",
                modifier = Modifier.fillMaxWidth(),
                senha = true,
                onEnter = ::enviar,
            )

            if (tab == 1) {
                Spacer(Modifier.height(14.dp))
                AuthFieldLabel("Confirmar senha")
                Spacer(Modifier.height(6.dp))
                AuthInput(
                    valor = confirmarSenha,
                    onChange = { confirmarSenha = it; erro = null },
                    dica = "Repita a senha",
                    modifier = Modifier.fillMaxWidth(),
                    senha = true,
                    onEnter = ::enviar,
                )
            }

            erro?.let { msg ->
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.Error.copy(alpha = 0.12f))
                        .border(1.dp, Palette.Error.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(msg, color = Palette.Error, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }

            Spacer(Modifier.height(22.dp))

            // Primary Electric Cyan action button
            val (btnSrc, btnHover) = rememberHover()
            val primaryBg = when {
                ocupado -> Palette.Brand.copy(alpha = 0.55f)
                btnHover -> Color(0xFF33ECFF)
                else -> Palette.Brand
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primaryBg)
                    .hoverable(btnSrc)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = btnSrc, indication = null, enabled = !ocupado, onClick = ::enviar),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        ocupado && tab == 0 -> "Entrando…"
                        ocupado && tab == 1 -> "Criando conta…"
                        tab == 0 -> "Entrar"
                        else -> "Criar conta grátis"
                    },
                    color = Palette.BrandText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Secondary discreet skip action
            val (skipSrc, skipHover) = rememberHover()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (skipHover) Color(0x12FFFFFF) else Color.Transparent)
                    .hoverable(skipSrc)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = skipSrc, indication = null, enabled = !ocupado, onClick = ::fecharSemConta),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Continuar sem conta",
                    color = if (skipHover) Palette.Text else Palette.TextDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AuthSegmentTab(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (src, hovered) = rememberHover()
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .height(38.dp)
            .clip(shape)
            .background(
                when {
                    selected -> Palette.BrandSoft
                    hovered -> Color(0x14FFFFFF)
                    else -> Color.Transparent
                },
            )
            .border(1.dp, if (selected) Palette.Brand.copy(alpha = 0.65f) else Color.Transparent, shape)
            .hoverable(src)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = if (selected) Palette.Brand else Palette.TextDim,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun AuthFieldLabel(text: String) {
    Text(
        text,
        color = Palette.TextDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
    )
}

@Composable
private fun AuthInput(
    valor: String,
    onChange: (String) -> Unit,
    dica: String,
    modifier: Modifier = Modifier,
    senha: Boolean = false,
    onEnter: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    var focado by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, if (focado) Palette.Brand else Palette.Hairline, shape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (valor.isEmpty()) Text(dica, color = Palette.TextFaint, fontSize = 14.sp)
        BasicTextField(
            value = valor,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focado = it.isFocused
                    campoDeTextoFocado = it.isFocused
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter, Key.NumPadEnter -> { onEnter(); true }
                        Key.Tab -> { focus.moveFocus(if (ev.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.Brand),
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}
