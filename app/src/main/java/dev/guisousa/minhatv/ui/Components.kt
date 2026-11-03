@file:OptIn(ExperimentalFoundationApi::class)

package dev.guisousa.minhatv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

object Palette {
    val Background = Color(0xFF0E1117)
    val Surface = Color(0xFF1A1F2B)
    val SurfaceHigh = Color(0xFF252C3B)
    val Accent = Color(0xFF3D7BFF)
    val Text = Color.White
    val TextDim = Color(0xFFA0A8B8)
    val Error = Color(0xFFFF7A7A)
}

@Composable
fun MinhaTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Background,
            surface = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
        ),
        content = content,
    )
}

/** Qualquer coisa que o controle remoto pode focar e "clicar". Cresce e ganha borda quando focado. */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    focusScale: Float = 1.04f,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "scale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> Palette.Accent
                    selected -> Palette.SurfaceHigh
                    else -> Palette.Surface
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        content(focused)
    }
}

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    FocusBox(onClick = onClick, modifier = modifier, selected = selected) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = Palette.Text,
        )
    }
}

@Composable
fun Logo(url: String?, modifier: Modifier = Modifier, fallback: String = "", crop: Boolean = false) {
    Box(modifier.background(Color(0xFF11151E)), contentAlignment = Alignment.Center) {
        Text(
            fallback.take(2).uppercase(),
            color = Palette.TextDim,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(if (crop) 0.dp else 4.dp),
            )
        }
    }
}

/** Coluna de categorias à esquerda. "Todos" só aparece se [total] não for gigante. */
@Composable
fun GroupColumn(
    groups: List<dev.guisousa.minhatv.data.GroupInfo>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val rows = remember(groups, total) {
        val list = groups.map<dev.guisousa.minhatv.data.GroupInfo, Pair<String?, Int>> { it.name to it.count }
        if (total <= dev.guisousa.minhatv.data.Library.ALL_LIMIT) listOf<Pair<String?, Int>>(null to total) + list else list
    }
    LazyColumn(
        Modifier.width(280.dp).fillMaxHeight(),
        contentPadding = PaddingValues(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(rows) { _, (g, count) ->
            FocusBox(onClick = { onSelect(g) }, selected = selected == g, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        g ?: "Todos",
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        color = Palette.Text,
                        fontWeight = if (selected == g) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text("$count", fontSize = 13.sp, color = Palette.TextDim)
                }
            }
        }
    }
}

/** Categoria inicial: "Todos" quando a lista é pequena, senão a primeira categoria. */
fun defaultGroup(groups: List<dev.guisousa.minhatv.data.GroupInfo>, total: Int): String? =
    if (total <= dev.guisousa.minhatv.data.Library.ALL_LIMIT) null else groups.firstOrNull()?.name

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
        androidx.compose.foundation.Image(bitmap, contentDescription = "QR Code", modifier = modifier.clip(RoundedCornerShape(8.dp)))
    }
}

@Composable
fun PosterCard(title: String, image: String?, subtitle: String? = null, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    FocusBox(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth(), focusScale = 1.06f) { focused ->
        Column {
            Logo(image, Modifier.fillMaxWidth().aspectRatio(2f / 3f), fallback = title, crop = true)
            Text(
                title,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).height(40.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = if (focused) Color.White else Palette.Text,
            )
            if (subtitle != null) {
                Text(subtitle, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp), fontSize = 11.sp, color = Palette.TextDim)
            }
        }
    }
}

@Composable
fun Message(title: String, body: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(12.dp))
            Text(body, fontSize = 18.sp, color = Palette.TextDim, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
fun Dot(modifier: Modifier = Modifier) = Box(modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Accent))
