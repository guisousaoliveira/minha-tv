@file:OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.guisousa.minhatv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.guisousa.minhatv.data.GroupInfo
import dev.guisousa.minhatv.data.Library

object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val SurfaceHigh = Color(0xFF232323)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFA3A3A3)
    val TextFaint = Color(0xFF6B6B6B)
    val Focus = Color(0xFFFFFFFF)
    val OnFocus = Color(0xFF000000)
    val Live = Color(0xFFE24B4A)
    val Error = Color(0xFFFF6B6B)
    val Hairline = Color(0x22FFFFFF)
}

@Composable
fun MinhaTvTheme(content: @Composable () -> Unit) {
    // A tela inteira mede sempre 1280 "pontos" de largura, como na prévia aprovada.
    // Assim o tamanho não muda de TV para TV e nada fica com cara de zoom.
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val density = androidx.compose.ui.unit.Density(maxOf(metrics.widthPixels, metrics.heightPixels) / 1280f, 1f)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        ThemeColors(content)
    }
}

@Composable
private fun ThemeColors(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Text,
            background = Palette.Background,
            surface = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
        ),
        content = content,
    )
}

/**
 * Base de tudo que o controle remoto foca. [style] decide como o foco aparece:
 * "fill" (fundo branco, texto preto: botões e itens de lista) ou "frame" (borda branca: capas).
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    selected: Boolean = false,
    frame: Boolean = false,
    focusScale: Float = 1.03f,
    shape: Shape = RoundedCornerShape(6.dp),
    background: Color = Palette.Surface,
    focusKey: String? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // ao voltar de uma tela (série, categoria), o foco volta para o cartão em que você estava
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (focusKey != null) {
        // roda ao aparecer e de novo a cada volta do player (FocusMemory.restore muda)
        val tick = FocusMemory.restore.intValue
        androidx.compose.runtime.LaunchedEffect(focusKey, tick) {
            if (FocusMemory.last == focusKey) {
                kotlinx.coroutines.delay(if (tick > 0) 40 else 60)
                runCatching { requester.requestFocus() }
            }
        }
    }
    val enabled = LocalFocusEnabled.current
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(150), label = "scale")
    val bg = when {
        focused && !frame -> Palette.Focus
        selected -> Palette.SurfaceHigh
        else -> background
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusKey != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties { canFocus = enabled }
            .onFocusChanged {
                if (it.isFocused && focusKey != null) FocusMemory.last = focusKey
                if (it.isFocused && !focused) onFocused?.invoke()
                focused = it.isFocused
            }
            .clip(shape)
            .background(bg)
            .then(if (focused && frame) Modifier.border(3.dp, Palette.Focus, shape) else Modifier)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        content(focused)
    }
}

/** false = esta parte da tela está escondida atrás de outra (detalhes) e não pode receber foco. */
val LocalFocusEnabled = androidx.compose.runtime.compositionLocalOf { true }

/** Último cartão focado, para devolver o foco ao voltar de outra tela. */
object FocusMemory {
    var last: String? = null
    /** Muda quando o app volta do player: o item que estava em foco pega o foco de novo. */
    val restore = androidx.compose.runtime.mutableIntStateOf(0)
    private val stack = ArrayDeque<String?>()
    /** Ao abrir uma tela por cima (detalhes, categoria): guarda onde o foco estava. */
    fun push() { stack.addLast(last) }
    /** Ao voltar dela: devolve o foco ao cartão que a abriu. */
    fun pop() {
        if (stack.isNotEmpty()) last = stack.removeLast()
        restore.intValue++
    }
    fun clear() = stack.clear()
    /** Se o foco estava no esqueleto da fileira [row] enquanto carregava, passa para o primeiro cartão. */
    fun handoff(row: String, firstKey: String?) { if (last == "load:$row" && firstKey != null) last = firstKey }
}

/** Cor do texto dentro de um FocusBox "fill". */
fun textOn(focused: Boolean, dim: Boolean = false) = when {
    focused -> Palette.OnFocus
    dim -> Palette.TextDim
    else -> Palette.Text
}

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, focusKey: String? = null) {
    FocusBox(onClick = onClick, modifier = modifier, selected = selected, shape = RoundedCornerShape(4.dp), focusKey = focusKey) { focused ->
        Text(
            text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = textOn(focused),
        )
    }
}

/** Liga/desliga em forma de linha de ajuste. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    FocusBox(onClick = onToggle, modifier = Modifier.fillMaxWidth(), focusScale = 1.01f, background = Color.Transparent) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, color = textOn(focused))
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = if (focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.TextDim)
            }
            Switch(checked, focused)
        }
    }
}

@Composable
private fun Switch(checked: Boolean, focused: Boolean) {
    val track = when {
        checked && focused -> Palette.OnFocus
        checked -> Palette.Text
        focused -> Color(0x33000000)
        else -> Palette.SurfaceHigh
    }
    val knob = when {
        checked && focused -> Palette.Focus
        checked -> Palette.OnFocus
        else -> Palette.TextDim
    }
    Box(Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(12.dp)).background(track).padding(3.dp)) {
        Box(Modifier.size(18.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).clip(RoundedCornerShape(9.dp)).background(knob))
    }
}

@Composable
fun Logo(url: String?, modifier: Modifier = Modifier, fallback: String = "", crop: Boolean = false, onError: (() -> Unit)? = null) {
    Box(modifier.background(Color(0xFF1B1B1B)), contentAlignment = Alignment.Center) {
        Text(
            initials(fallback),
            color = Palette.TextFaint,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(6.dp),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(if (crop) 0.dp else 6.dp),
                onError = { onError?.invoke() },
            )
        }
    }
}

private fun initials(s: String): String =
    s.split(' ', '-', '.', ':').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2).joinToString("") { it.take(1).uppercase() }

/** Título de seção, no estilo das fileiras de streaming. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.padding(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, fontSize = 13.sp, color = Palette.TextFaint)
        }
    }
}

/** Fileira horizontal genérica. Com [seeAll], termina com um cartão "Ver tudo". */
@Composable
fun <T> MediaRow(
    items: List<T>,
    itemWidth: Dp,
    seeAll: (() -> Unit)? = null,
    seeAllCount: Int = 0,
    seeAllAspect: Float = 2f / 3f,
    item: @Composable (Int, T) -> Unit,
) {
    LazyRow(
        // no fim da fileira, ◀ ▶ param ali em vez de pular para o menu do topo
        modifier = Modifier.focusProperties {
            exit = { dir -> if (dir == FocusDirection.Left || dir == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
        },
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items) { i, it -> Box(Modifier.width(itemWidth)) { item(i, it) } }
        if (seeAll != null) {
            item(key = "see-all") { Box(Modifier.width(itemWidth)) { SeeAllCard(seeAllCount, seeAllAspect, seeAll) } }
        }
    }
}

/** Capa vertical (filmes e séries). */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    progress: Float? = null,
    requester: FocusRequester? = null,
    key: String = title,
    tmdbTitle: String? = null,
    tv: Boolean = false,
) {
    // série: a lista costuma trazer um quadro do episódio; usa o pôster oficial do TMDB quando achar
    val official = if (tmdbTitle != null && dev.guisousa.minhatv.data.Tmdb.enabled) {
        androidx.compose.runtime.produceState<String?>(null, tmdbTitle) { value = dev.guisousa.minhatv.data.Tmdb.lookup(tmdbTitle, tv)?.poster }.value
    } else null
    @Suppress("NAME_SHADOWING") val image = if (tv) official ?: image else image ?: official
    Column {
        FocusBox(
            onClick = onClick, onLongClick = onLongClick, onFocused = onFocused,
            frame = true, focusScale = 1.07f,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
            focusKey = "p:$key",
        ) {
            // capa que não abre (link quebrado) vira o mesmo cartão com o nome, e não um quadrado cinza vazio
            var broken by remember(image) { mutableStateOf(false) }
            Logo(image, Modifier.fillMaxSize(), fallback = "", crop = true, onError = { broken = true })
            if (image.isNullOrBlank() || broken) {
                // sem capa: inicial grande, como no Saimo
                Text(
                    title.trimStart { !it.isLetterOrDigit() }.take(1).uppercase(), Modifier.align(Alignment.Center),
                    fontSize = 52.sp, fontWeight = FontWeight.Black, color = Palette.TextFaint,
                )
                Text(
                    title, color = Palette.TextDim, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                )
            }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** Cartão horizontal (canais). */
@Composable
fun ChannelCard(title: String, logo: String?, subtitle: String?, onFocused: (() -> Unit)? = null, key: String = title, onClick: () -> Unit) {
    Column {
        FocusBox(onClick = onClick, onFocused = onFocused, frame = true, focusScale = 1.06f, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), focusKey = "c:$key") {
            Logo(logo, Modifier.fillMaxSize().padding(12.dp), fallback = title)
        }
        Text(title, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Cartão "Ver tudo" no fim de uma fileira. */
@Composable
fun SeeAllCard(count: Int, aspect: Float, onClick: () -> Unit) {
    FocusBox(onClick = onClick, frame = true, focusScale = 1.07f, modifier = Modifier.fillMaxWidth().aspectRatio(aspect), background = Palette.Surface) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ver tudo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text("$count", fontSize = 13.sp, color = Palette.TextDim)
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Text))
    }
}

/** Destaque no topo: o item focado em letras grandes, com a imagem dele à direita. */
@Composable
fun FocusHeader(
    title: String?,
    subtitle: String?,
    modifier: Modifier = Modifier,
    image: String? = null,
    tall: Boolean = true,
    detail: String? = null,
    logo: Boolean = false,
) {
    val backdrop = tall && !image.isNullOrBlank() && !logo
    Box(modifier.fillMaxWidth().height(if (tall) 240.dp else 110.dp)) {
        if (backdrop) {
            // destaque estilo Prime Video: a capa ocupando a direita, sumindo no preto
            // o degradê só aparece depois que a imagem carregou (sem imagem, fica tudo preto)
            var loaded by remember(image) { mutableStateOf(false) }
            androidx.compose.animation.Crossfade(image, animationSpec = tween(220), label = "hero", modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.62f).fillMaxHeight()) { img ->
                AsyncImage(
                    model = img, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, alpha = 0.6f,
                    modifier = Modifier.fillMaxSize(), onSuccess = { if (img == image) loaded = true },
                )
            }
            if (loaded) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.30f to Palette.Background, 0.62f to Color(0x99000000), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.Background)))
            }
        }
        Row(
            Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold,
                        color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                androidx.compose.animation.AnimatedContent(
                    title ?: "",
                    transitionSpec = { androidx.compose.animation.fadeIn(tween(160)) togetherWith androidx.compose.animation.fadeOut(tween(120)) },
                    label = "title",
                ) { t ->
                    Text(
                        t, fontSize = if (tall) 38.sp else 28.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                        lineHeight = if (tall) 44.sp else 34.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(if (backdrop) 0.75f else 1f),
                    )
                }
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(detail, fontSize = 15.sp, color = Palette.TextDim, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tall && !backdrop && !image.isNullOrBlank()) {
                Box(Modifier.width(420.dp).fillMaxHeight()) {
                    if (!image.isNullOrBlank()) {
                        // logo de canal: inteira, sem cortar nem esticar
                        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(36.dp))
                    }
                }
            }
        }
    }
}

/** Coluna de categorias à esquerda (Ao vivo e grades). "Todos" só aparece se [total] não for gigante. */
@Composable
fun GroupColumn(groups: List<GroupInfo>, total: Int, selected: String?, alwaysAll: Boolean = false, onSelect: (String?) -> Unit) {
    val rows = remember(groups, total) {
        val list = groups.map<GroupInfo, Pair<String?, Int>> { it.name to it.count }
        if (alwaysAll || total <= Library.ALL_LIMIT) listOf<Pair<String?, Int>>(null to total) + list else list
    }
    LazyColumn(
        Modifier.width(290.dp).fillMaxHeight(),
        contentPadding = PaddingValues(start = 40.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(rows) { _, (g, count) ->
            val isSel = selected == g
            FocusBox(
                onClick = { onSelect(g) }, selected = isSel, modifier = Modifier.fillMaxWidth(),
                focusScale = 1.0f, background = Color.Transparent, shape = RoundedCornerShape(4.dp),
            ) { focused ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        g ?: "Todos",
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        color = if (focused) Palette.OnFocus else if (isSel) Palette.Text else Palette.TextDim,
                        fontWeight = if (isSel || focused) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text("$count", fontSize = 12.sp, color = if (focused) Palette.OnFocus.copy(alpha = 0.6f) else Palette.TextFaint)
                }
            }
        }
    }
}

/** Categoria inicial: "Todos" quando a lista é pequena, senão a primeira categoria. */
fun defaultGroup(groups: List<GroupInfo>, total: Int): String? =
    if (total <= Library.ALL_LIMIT) null else groups.firstOrNull()?.name

@Composable
fun Message(title: String, body: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val loading = title.startsWith("Carregando") || title.startsWith("Preparando")
    Column(
        modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) { Spinner(36.dp); Spacer(Modifier.height(20.dp)) }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(10.dp))
            Text(body, fontSize = 16.sp, color = Palette.TextDim, textAlign = TextAlign.Center, lineHeight = 24.sp)
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/** Indicador de carregamento: arco branco girando, fino. */
@Composable
fun Spinner(size: Dp, modifier: Modifier = Modifier) {
    // gira pelo relógio de quadros: funciona mesmo com as animações do sistema desligadas
    // (nessas TVs o indicador padrão do Android fica parado)
    val angle by androidx.compose.runtime.produceState(0f) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { t -> value = ((t - start) / 1_000_000L % 900L) / 900f * 360f }
        }
    }
    val stroke = if (size < 20.dp) 1.5.dp else 3.dp
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(Color(0x33FFFFFF), 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(Palette.Text, angle, 100f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Retângulo cinza "pulsando" no lugar de algo que ainda está carregando. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(
        0.35f, 0.7f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh.copy(alpha = a)))
}

/** QR Code desenhado com ZXing. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        runCatching {
            val size = 512
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
            val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = "QR Code", modifier = modifier.clip(RoundedCornerShape(6.dp)))
    }
}


/**
 * Fileira ainda carregando: um cartão "esqueleto" que pode receber o foco (descendo com ▼ o foco
 * não some). Quando a fileira chega, o foco passa para o primeiro cartão dela.
 */
@Composable
fun LoadingRow(height: Dp, key: String? = null) {
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 48.dp, vertical = 10.dp).height(height),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FocusBox(onClick = {}, frame = true, focusScale = 1f, modifier = Modifier.width(136.dp).fillMaxHeight(), focusKey = key?.let { "load:$it" }) {
            Skeleton(Modifier.fillMaxSize())
            Spinner(22.dp, Modifier.align(Alignment.Center))
        }
        repeat(6) { Skeleton(Modifier.width(136.dp).fillMaxHeight()) }
    }
}
