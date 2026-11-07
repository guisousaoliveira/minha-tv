package dev.guisousa.badwolf.mobile.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** Cores do app da TV (app/.../ui/Components.kt), escuro. */
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val SurfaceHigh = Color(0xFF232323)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFA3A3A3)
    val TextFaint = Color(0xFF6B6B6B)
    val Live = Color(0xFFE24B4A)
    val Error = Color(0xFFFF6B6B)
    val Hairline = Color(0x22FFFFFF)
    val Brand = Color(0xFF00E5FF)
}

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
    Box(modifier.background(Color(0xFF1B1B1B)), contentAlignment = Alignment.Center) {
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

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Brand) }
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.TextDim, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

/** Barrinha de "quanto já viu". */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Palette.Live))
    }
}

/** Capa vertical (filme ou série). Tocar abre, segurar põe/tira da Minha lista. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String, image: String?, modifier: Modifier = Modifier, progress: Float? = null, seen: Boolean = false,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Logo(image, Modifier.fillMaxSize(), fallback = title, crop = true)
            if (seen) Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text("Visto", fontSize = 10.sp, color = Palette.Text) }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            title, fontSize = 12.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** Fileira com rolagem lateral. */
@Composable
fun <T> MediaRow(items: List<T>, itemWidth: Dp, content: @Composable (Int, T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(items) { i, item -> Box(Modifier.width(itemWidth)) { content(i, item) } }
    }
}

/** Fileira de categorias no topo das abas. */
@Composable
fun Chips(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(options) { _, o ->
            FilterChip(
                selected = o == selected, onClick = { onSelect(o) }, label = { Text(o, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Palette.Surface, labelColor = Palette.Text,
                    selectedContainerColor = Palette.Text, selectedLabelColor = Color.Black,
                ),
                border = null,
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
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color.Transparent, Palette.Background))))
    }
}

@Composable
fun Meta(parts: List<String>) {
    if (parts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(parts.joinToString("  ·  "), fontSize = 13.sp, color = Palette.TextDim)
    }
}

fun clock(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}
