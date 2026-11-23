package dev.guisousa.badwolf.mobile.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.guisousa.badwolf.mobile.data.Entry
import dev.guisousa.badwolf.mobile.data.Repository
import dev.guisousa.badwolf.mobile.data.Show

/** Cores do app da TV (app/.../ui/Components.kt), escuro, com o ciano da marca. */
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141416)
    val SurfaceHigh = Color(0xFF222226)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFB0B0B6)
    /** Legível no celular ao sol (antes #6B6B6B sumia). */
    val TextFaint = Color(0xFF8A8A92)
    val Live = Color(0xFFE24B4A)
    val Error = Color(0xFFFF6B6B)
    val Hairline = Color(0x1FFFFFFF)
    val Brand = Color(0xFF00E5FF)
}

/** Cantos de todo o app: cartões, capas e botões no mesmo raio. */
val CardShape = RoundedCornerShape(12.dp)

@Composable
fun BadWolfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Brand,
            onPrimary = Color.Black,
            secondary = Palette.Brand,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Background,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface,
            onSurfaceVariant = Palette.TextDim,
            surfaceContainer = Palette.Surface,
            surfaceContainerHigh = Palette.SurfaceHigh,
            secondaryContainer = Palette.SurfaceHigh,
            onSecondaryContainer = Palette.Text,
            outline = Palette.Hairline,
            error = Palette.Error,
        ),
        content = content,
    )
}

/** Roda [block] fora da tela e devolve o resultado (null enquanto carrega ou se falhar). */
@Composable
fun <T> load(vararg keys: Any?, block: suspend () -> T): T? =
    produceState<T?>(null, *keys) { value = null; value = runCatching { block() }.getOrNull() }.value

fun toggleFav(context: Context, e: Entry) {
    val added = Repository.toggleFavorite(e.url)
    Toast.makeText(context, if (added) "Adicionado à Minha lista" else "Removido da Minha lista", Toast.LENGTH_SHORT).show()
}

fun toggleFav(context: Context, s: Show) {
    val added = Repository.toggleShowFavorite(s, null)
    Toast.makeText(context, if (added) "Adicionado à Minha lista" else "Removido da Minha lista", Toast.LENGTH_SHORT).show()
}

@Composable
fun Logo(url: String?, modifier: Modifier = Modifier, fallback: String = "", crop: Boolean = false) {
    Box(modifier.background(Color(0xFF1B1B1F)), contentAlignment = Alignment.Center) {
        Text(initials(fallback), color = Palette.TextFaint, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp))
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url, contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(if (crop) 0.dp else 6.dp),
            )
        }
    }
}

private fun initials(s: String): String =
    s.split(' ', '-', '.', ':').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2).joinToString("") { it.take(1).uppercase() }

/** Barra do topo das abas: título grande e a lupa da busca. */
@Composable
fun TopBar(title: String, onSearch: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.weight(1f), maxLines = 1)
        if (onSearch != null) IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Buscar", tint = Palette.Text, modifier = Modifier.size(26.dp))
        }
    }
}

/** Título de fileira, com "Ver tudo" opcional. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) Text(
            action, fontSize = 14.sp, color = Palette.Brand, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Brand, strokeWidth = 3.dp) }
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, color = Palette.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Barrinha de "quanto já viu". */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Palette.Brand))
    }
}

/** Capa vertical (filme ou série). Tocar abre, segurar põe/tira da Minha lista. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String, image: String?, modifier: Modifier = Modifier, progress: Float? = null, seen: Boolean = false,
    badge: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(CardShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Logo(image, Modifier.fillMaxSize(), fallback = title, crop = true)
            val selo = if (seen) "Visto" else badge
            if (selo != null) Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xD9000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text(selo, fontSize = 10.sp, color = if (seen) Palette.Brand else Palette.Text, fontWeight = FontWeight.Bold) }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            title, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** Fileira com rolagem lateral. */
@Composable
fun <T> MediaRow(items: List<T>, itemWidth: Dp, content: @Composable (Int, T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(items) { i, item -> Box(Modifier.width(itemWidth)) { content(i, item) } }
    }
}

/** Fileira de categorias no topo das abas (pílulas). */
@Composable
fun Chips(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(options) { _, o ->
            val sel = o == selected
            Text(
                o, fontSize = 14.sp, maxLines = 1, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                color = if (sel) Color.Black else Palette.Text,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Palette.Text else Palette.SurfaceHigh)
                    .clickable { onSelect(o) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Bolinha vermelha do "ao vivo". */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(Palette.Live))
}

/** Fundo grande no topo dos detalhes, escurecendo para baixo. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 1f to Palette.Background)))
    }
}

@Composable
fun Meta(parts: List<String>) {
    if (parts.isEmpty()) return
    Text(parts.joinToString("  ·  "), fontSize = 13.sp, color = Palette.TextDim)
}

fun clock(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}

// ---------- Ajustes ----------

/** Grupo de ajustes num cartão (título em cima, linhas dentro). */
@Composable
fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), fontSize = 12.sp, color = Palette.Brand, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 28.dp, top = 22.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CardShape).background(Palette.Surface)) { content() }
}

/** Linha de ajuste: título, detalhe e, opcional, um interruptor ou um texto à direita. */
@Composable
fun SettingsRow(
    title: String, detail: String? = null, icon: ImageVector? = null, value: String? = null,
    toggle: Boolean? = null, danger: Boolean = false, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) Palette.Error else Palette.TextDim, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = if (danger) Palette.Error else Palette.Text, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, fontSize = 13.sp, color = Palette.TextDim, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) Text(value, fontSize = 14.sp, color = Palette.Brand, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        if (toggle != null) Switch(
            checked = toggle, onCheckedChange = { onClick() }, modifier = Modifier.padding(start = 8.dp),
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Palette.Brand, uncheckedTrackColor = Palette.SurfaceHigh),
        )
    }
}

@Composable
fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Palette.Hairline))
}
