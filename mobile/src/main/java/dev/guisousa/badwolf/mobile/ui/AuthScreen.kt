package dev.guisousa.badwolf.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.guisousa.badwolf.mobile.R
import dev.guisousa.badwolf.mobile.data.Conta
import dev.guisousa.badwolf.mobile.data.Nuvem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val authTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Tela dedicada de boas-vindas e autenticação (Entrar | Criar conta | Continuar sem conta).
 * Também exibe o resumo da conta conectada quando aberta pelo botão de perfil já autenticado.
 */
@Composable
fun AuthScreen(
    isOnboarding: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val usuarioAtual by Conta.usuario.collectAsState()
    val ultimaSinc by Nuvem.ultima.collectAsState()
    val sincronizando by Nuvem.sincronizando.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Entrar, 1 = Criar conta
    var usuario by rememberSaveable { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var confirmarSenha by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var carregando by remember { mutableStateOf(false) }
    var confirmarSair by remember { mutableStateOf(false) }

    fun enviar() {
        if (carregando) return
        keyboard?.hide()
        erro = null
        if (tab == 1) {
            if (usuario.trim().length < 3) {
                erro = "O usuário precisa ter pelo menos 3 caracteres."
                return
            }
            if (senha.length < 6) {
                erro = "A senha precisa ter pelo menos 6 caracteres."
                return
            }
            if (senha != confirmarSenha) {
                erro = "As senhas não coincidem."
                return
            }
        }
        carregando = true
        scope.launch {
            val res = if (tab == 0) {
                Conta.entrarComSenha(usuario, senha)
            } else {
                Conta.cadastrarComSenha(usuario, senha)
            }
            carregando = false
            if (res == null) {
                Conta.jaViuBoasVindas = true
                senha = ""
                confirmarSenha = ""
                val msg = if (tab == 0) "Conectado! Sincronizando sua lista…" else "Conta criada! Sincronizando…"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                onDone()
            } else {
                erro = res
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background),
    ) {
        // Glow radial ciano sutil no topo (Obsidian Cinema)
        Box(
            Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Palette.Brand.copy(alpha = 0.20f),
                            Palette.Brand.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(540f, 0f),
                        radius = 780f,
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Botão voltar no topo quando aberto a partir do app
            if (!isOnboarding) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            onDone()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Palette.SurfaceGlass)
                            .border(1.dp, Palette.Hairline, CircleShape),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Palette.Text, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Spacer(Modifier.height(20.dp))
            }

            Spacer(Modifier.height(14.dp))

            // Logo Bad Wolf em halo obsidiana com borda ciano sutil
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Palette.Surface)
                    .border(1.5.dp, Palette.Brand.copy(alpha = 0.35f), CircleShape)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.logo_lobo), contentDescription = "Bad Wolf TV", modifier = Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "BAD WOLF TV",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.4.sp,
                color = Palette.Text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Sincronize sua lista e continue de onde parou em qualquer tela",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Palette.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(26.dp))

            val conectado = usuarioAtual
            if (conectado != null) {
                // Estado já conectado (aberto pelo avatar no topo)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(ModalShape)
                        .background(Palette.Surface)
                        .border(1.dp, Palette.Hairline, ModalShape)
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val inicial = conectado.nome.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W"
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Palette.BrandSoft)
                            .border(1.5.dp, Palette.Brand, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(inicial, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Brand)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Success))
                        Text(
                            conectado.nome.ifBlank { "Sua conta" },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.Text,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val quando = when {
                        sincronizando -> "Sincronizando agora…"
                        ultimaSinc == 0L -> "Sincronização ativa"
                        else -> "Sincronizado às ${authTimeFmt.format(Date(ultimaSinc))}"
                    }
                    Text(quando, fontSize = 13.sp, color = Palette.TextDim)

                    Spacer(Modifier.height(22.dp))

                    Button(
                        onClick = {
                            Nuvem.sincronizar(forcar = true)
                            Toast.makeText(context, "Sincronizando…", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = InputShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.Brand,
                            contentColor = Palette.BrandText,
                        ),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sincronizar agora", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { confirmarSair = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = InputShape,
                        border = BorderStroke(1.dp, Palette.Error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Palette.Error.copy(alpha = 0.08f),
                            contentColor = Palette.Error,
                        ),
                    ) {
                        Text("Sair da conta", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // Segmented Tabs (espelho do W4LLE SegmentedTabs em pílula)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(PillShape)
                        .background(Palette.Surface)
                        .border(1.dp, Palette.Hairline, PillShape)
                        .padding(4.dp),
                ) {
                    listOf("Entrar", "Criar conta").forEachIndexed { idx, label ->
                        val active = tab == idx
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(PillShape)
                                .background(if (active) Palette.Brand else Color.Transparent)
                                .clickable {
                                    tab = idx
                                    erro = null
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (active) Palette.BrandText else Palette.TextDim,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Dark Glass Form Card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(ModalShape)
                        .background(Palette.Surface)
                        .border(1.dp, Palette.Hairline, ModalShape)
                        .padding(20.dp),
                ) {
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Palette.SurfaceHigh,
                        unfocusedContainerColor = Palette.SurfaceHigh,
                        focusedBorderColor = Palette.Brand,
                        unfocusedBorderColor = Palette.Hairline,
                        cursorColor = Palette.Brand,
                        focusedTextColor = Palette.Text,
                        unfocusedTextColor = Palette.Text,
                        focusedLeadingIconColor = Palette.Brand,
                        unfocusedLeadingIconColor = Palette.TextFaint,
                        focusedPlaceholderColor = Palette.TextFaint,
                        unfocusedPlaceholderColor = Palette.TextFaint,
                    )

                    Text("Usuário", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextDim, modifier = Modifier.padding(bottom = 6.dp))
                    OutlinedTextField(
                        value = usuario,
                        onValueChange = { usuario = it; erro = null },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Ex: joaosilva") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        singleLine = true,
                        shape = InputShape,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    Spacer(Modifier.height(14.dp))

                    Text("Senha", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextDim, modifier = Modifier.padding(bottom = 6.dp))
                    OutlinedTextField(
                        value = senha,
                        onValueChange = { senha = it; erro = null },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (tab == 1) "Mínimo 6 caracteres" else "Digite sua senha") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        singleLine = true,
                        shape = InputShape,
                        colors = fieldColors,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (tab == 1) ImeAction.Next else ImeAction.Done,
                            keyboardType = KeyboardType.Password,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (tab == 0) enviar() }),
                    )

                    if (tab == 1) {
                        Spacer(Modifier.height(14.dp))
                        Text("Confirmar senha", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextDim, modifier = Modifier.padding(bottom = 6.dp))
                        OutlinedTextField(
                            value = confirmarSenha,
                            onValueChange = { confirmarSenha = it; erro = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Repita a senha") },
                            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            singleLine = true,
                            shape = InputShape,
                            colors = fieldColors,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
                            keyboardActions = KeyboardActions(onDone = { enviar() }),
                        )
                    }

                    erro?.let { msg ->
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(InputShape)
                                .background(Palette.Error.copy(alpha = 0.12f))
                                .border(1.dp, Palette.Error.copy(alpha = 0.35f), InputShape)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(msg, fontSize = 13.sp, color = Palette.Error, lineHeight = 18.sp)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = { enviar() },
                        enabled = !carregando,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = InputShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.Brand,
                            contentColor = Palette.BrandText,
                            disabledContainerColor = Palette.Brand.copy(alpha = 0.45f),
                            disabledContentColor = Palette.BrandText,
                        ),
                    ) {
                        if (carregando) {
                            CircularProgressIndicator(
                                color = Palette.BrandText,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (tab == 0) "Entrando…" else "Criando conta…",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(
                                if (tab == 0) "Entrar" else "Criar conta grátis",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                TextButton(
                    onClick = {
                        keyboard?.hide()
                        Conta.jaViuBoasVindas = true
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = PillShape,
                ) {
                    Text(
                        "Continuar sem conta",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.TextDim,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Mesma conta W4LLE na TV, celular, Windows e web.",
                    fontSize = 12.sp,
                    color = Palette.TextFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (confirmarSair) {
        AlertDialog(
            onDismissRequest = { confirmarSair = false },
            title = { Text("Sair da conta?", color = Palette.Text) },
            text = { Text("O app continua funcionando neste celular, só para de sincronizar.", color = Palette.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    Conta.sair()
                    confirmarSair = false
                    Toast.makeText(context, "Você saiu da conta.", Toast.LENGTH_SHORT).show()
                }) { Text("Sair", color = Palette.Error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarSair = false }) { Text("Cancelar", color = Palette.TextDim) }
            },
            containerColor = Palette.SurfaceHigh,
        )
    }
}
