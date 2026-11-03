@file:OptIn(ExperimentalFoundationApi::class)

package dev.guisousa.minhatv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(150), label = "scale")
    val bg = when {
        focused && !frame -> Palette.Focus
        selected -> Palette.SurfaceHigh
        else -> background
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
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

/** Cor do texto dentro de um FocusBox "fill". */
fun textOn(focused: Boolean, dim: Boolean = false) = when {
    focused -> Palette.OnFocus
    dim -> Palette.TextDim
    else -> Palette.Text
}

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    FocusBox(onClick = onClick, modifier = modifier, selected = selected, shape = RoundedCornerShape(4.dp)) { focused ->
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
fun Logo(url: String?, modifier: Modifier = Modifier, fallback: String = "", crop: Boolean = false) {
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
) {
    Column {
        FocusBox(
            onClick = onClick, onLongClick = onLongClick, onFocused = onFocused,
            frame = true, focusScale = 1.07f, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            Logo(image, Modifier.fillMaxSize(), fallback = title, crop = true)
            if (image.isNullOrBlank()) {
                Text(
                    title, color = Palette.TextDim, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                )
            }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
        }
        Text(
            title,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp).height(36.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = Palette.TextDim,
        )
    }
}

/** Cartão horizontal (canais). */
@Composable
fun ChannelCard(title: String, logo: String?, subtitle: String?, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    Column {
        FocusBox(onClick = onClick, onFocused = onFocused, frame = true, focusScale = 1.06f, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
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

/** Faixa com o nome e detalhes do item focado, no topo das telas (como nos apps de streaming). */
@Composable
fun FocusHeader(title: String?, subtitle: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF151515), Palette.Background)))
            .padding(horizontal = 48.dp, vertical = 16.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                title ?: "", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 15.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Coluna de categorias à esquerda (Ao vivo e grades). "Todos" só aparece se [total] não for gigante. */
@Composable
fun GroupColumn(groups: List<GroupInfo>, total: Int, selected: String?, onSelect: (String?) -> Unit) {
    val rows = remember(groups, total) {
        val list = groups.map<GroupInfo, Pair<String?, Int>> { it.name to it.count }
        if (total <= Library.ALL_LIMIT) listOf<Pair<String?, Int>>(null to total) + list else list
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
    Column(
        modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(10.dp))
            Text(body, fontSize = 16.sp, color = Palette.TextDim, textAlign = TextAlign.Center, lineHeight = 24.sp)
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
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
