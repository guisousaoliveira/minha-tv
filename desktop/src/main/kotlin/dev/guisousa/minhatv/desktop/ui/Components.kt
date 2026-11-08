package dev.guisousa.minhatv.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.FilterQuality
import coil3.compose.AsyncImage
import dev.guisousa.minhatv.data.ContentType
import dev.guisousa.minhatv.data.Entry
import dev.guisousa.minhatv.data.Repository
import dev.guisousa.minhatv.data.Show
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val PagePad = 48.dp

@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val src = remember { MutableInteractionSource() }
    val h by src.collectIsHoveredAsState()
    return src to h
}

/**
 * Pôster/fundo do TMDB numa resolução boa para tela de PC: o catálogo traz pôster w342 e fundo w1280,
 * que ficam borrados/pixelados esticados numa tela grande ou com escala do Windows.
 */
fun nitida(url: String?, fundo: Boolean = false): String? =
    url?.replace(tmdbTamanho, if (fundo) "/t/p/original/" else "/t/p/w500/")

private val tmdbTamanho = Regex("""/t/p/(w\d+(_and_h\d+_bestv2)?|original)/""")

/** Imagem da internet com um nome no lugar quando não abre. */
@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, crop: Boolean = true, fallback: String? = null, alignment: Alignment = Alignment.Center) {
    var broken by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        if (!url.isNullOrBlank() && !broken) {
            AsyncImage(
                model = nitida(url), contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit, alignment = alignment,
                filterQuality = FilterQuality.High,
                onError = { broken = true },
            )
        }
        if ((url.isNullOrBlank() || broken) && fallback != null) {
            Text(
                fallback, Modifier.align(Alignment.Center).padding(8.dp), color = Palette.TextDim, fontSize = 13.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Brand))
    }
}

/** Cartão com moldura branca e zoom quando o mouse passa por cima. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HoverCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onHover: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null,
    zoom: Float = 1.06f,
    background: Color = Palette.Surface,
    content: @Composable BoxScope.(hovered: Boolean) -> Unit,
) {
    val (src, hovered) = rememberHover()
    LaunchedEffect(hovered) { if (hovered) onHover?.invoke() }
    val s by animateFloatAsState(if (hovered) zoom else 1f, tween(140))
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .scale(s)
            .clip(shape)
            .background(background)
            .border(if (hovered) 2.dp else 0.dp, if (hovered) Palette.Focus else Color.Transparent, shape)
            .hoverable(src)
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onRightClick),
    ) { content(hovered) }
}

/** Capa vertical (filmes e séries). */
@Composable
fun Poster(
    title: String, image: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    progress: Float? = null, watched: Boolean = false, onHover: (() -> Unit)? = null, caption: Boolean = false,
) {
    Column(modifier) {
        HoverCard(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), onHover = onHover) {
            var broken by remember(image) { mutableStateOf(false) }
            if (!image.isNullOrBlank() && !broken) {
                AsyncImage(nitida(image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High, onError = { broken = true })
            } else {
                Text(
                    title.trimStart { !it.isLetterOrDigit() }.take(1).uppercase(), Modifier.align(Alignment.Center),
                    fontSize = 48.sp, fontWeight = FontWeight.Black, color = Palette.TextFaint,
                )
                Text(
                    title, color = Palette.TextDim, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                )
            }
            if (watched) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(Color(0xCC000000), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Ic.Check, null, Modifier.size(15.dp), tint = Palette.Brand)
                }
            }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
        }
        if (caption) {
            Text(title, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Cartão horizontal de canal. */
@Composable
fun ChannelCard(e: Entry, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, onHover: (() -> Unit)? = null) {
    Column(modifier) {
        HoverCard(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onHover = onHover, zoom = 1.04f) {
            NetImage(e.logo, Modifier.fillMaxSize().padding(14.dp), crop = false, fallback = e.name)
        }
        Text(e.name, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Botão redondinho (primário = branco). */
@Composable
fun PillButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, primary: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val (src, hovered) = rememberHover()
    val bg = when {
        !enabled -> Palette.Surface
        primary -> if (hovered) Color(0xFFE6E6E6) else Palette.Focus
        else -> if (hovered) Color(0x55FFFFFF) else Color(0x33FFFFFF)
    }
    val fg = if (primary && enabled) Palette.OnFocus else if (enabled) Palette.Text else Palette.TextFaint
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(bg).hoverable(src).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = fg); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Botão só com ícone (redondo). */
@Composable
fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = Palette.Text, bg: Color = Color.Transparent, enabled: Boolean = true) {
    val (src, hovered) = rememberHover()
    Box(
        modifier.size(size).clip(CircleShape).background(if (hovered && enabled) Color(0x33FFFFFF) else bg).hoverable(src)
            .pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.6f), tint = if (enabled) tint else Palette.TextFaint) }
}

/** Opção selecionável (categorias, temporadas, letras). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val (src, hovered) = rememberHover()
    val bg = when { selected -> Palette.Focus; hovered -> Color(0x33FFFFFF); else -> Color(0x1AFFFFFF) }
    Box(
        modifier.clip(RoundedCornerShape(50)).background(bg).hoverable(src).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 5.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Palette.OnFocus else Palette.Text, fontSize = if (compact) 13.sp else 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun Tag(text: String, color: Color = Palette.TextDim) {
    Box(Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(modifier.padding(start = PagePad, end = PagePad, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val m = if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier
        Text(text, m, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        if (onClick != null) {
            Text("  Ver tudo", m, fontSize = 13.sp, color = Palette.Brand, fontWeight = FontWeight.SemiBold)
            Icon(Ic.Right, null, Modifier.size(16.dp), tint = Palette.Brand)
        }
    }
}

/** Fileira que rola para os lados, com setas que aparecem quando o mouse está em cima. */
@Composable
fun <T> Shelf(
    title: String, items: List<T>, itemWidth: Dp, modifier: Modifier = Modifier,
    onTitle: (() -> Unit)? = null, key: ((T) -> Any)? = null,
    item: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val (src, hovered) = rememberHover()
    Column(modifier.padding(bottom = 26.dp)) {
        SectionTitle(title, onClick = onTitle)
        Box(Modifier.fillMaxWidth().hoverable(src)) {
            LazyRow(
                state = state, contentPadding = PaddingValues(horizontal = PagePad, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items, key = if (key != null) { _, it -> key(it) } else null) { _, it -> Box(Modifier.width(itemWidth)) { item(it) } }
            }
            val page = { dir: Int ->
                scope.launch {
                    val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(2) - 1
                    state.animateScrollToItem((state.firstVisibleItemIndex + dir * visible).coerceIn(0, items.lastIndex))
                }
            }
            androidx.compose.animation.AnimatedVisibility(hovered && state.canScrollBackward, Modifier.align(Alignment.CenterStart), enter = fadeIn(), exit = fadeOut()) {
                ArrowPanel(Ic.Left, Brush.horizontalGradient(listOf(Color(0xEE000000), Color.Transparent))) { page(-1) }
            }
            androidx.compose.animation.AnimatedVisibility(hovered && state.canScrollForward, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
                ArrowPanel(Ic.Right, Brush.horizontalGradient(listOf(Color.Transparent, Color(0xEE000000)))) { page(1) }
            }
        }
    }
}

@Composable
private fun ArrowPanel(icon: ImageVector, brush: Brush, onClick: () -> Unit) {
    Box(
        Modifier.width(PagePad + 8.dp).height(itemArrowHeight).background(brush).pointerHoverIcon(PointerIcon.Hand)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(40.dp), tint = Palette.Text) }
}

private val itemArrowHeight = 200.dp

// ---------- Destaque (estilo cinema) ----------

/** Item mostrado no destaque do topo. */
data class HeroItem(val title: String, val subtitle: String?, val ref: String?, val image: String?)

fun heroOf(item: Any?): HeroItem? = when (item) {
    is Show -> HeroItem(item.name, "SÉRIE · ${item.group}".uppercase(), Repository.showRef(item), item.logo)
    is Entry -> when (item.type) {
        ContentType.MOVIE -> HeroItem(item.name, "FILME · ${item.group}".uppercase(), item.url, item.logo)
        ContentType.SERIES -> HeroItem(item.seriesName ?: item.name, "T${item.season} · E${item.episode}", Repository.showOf(item)?.let { Repository.showRef(it) }, item.logo)
        ContentType.LIVE -> HeroItem(item.name, "AO VIVO · ${item.group}".uppercase(), null, item.logo)
    }
    else -> null
}

/**
 * Destaque no topo das páginas: o item sob o mouse em letras grandes, com o fundo largo à direita
 * sumindo no preto (mesma ideia do CinemaLayout da TV).
 */
@Composable
fun CinemaHeader(hero: HeroItem?, modifier: Modifier = Modifier, extra: @Composable (() -> Unit)? = null) {
    val d = remember(hero?.ref) { Repository.destaque(hero?.ref) }
    val sinopse by produceState<String?>(null, hero?.ref) {
        value = null
        val r = hero?.ref ?: return@produceState
        delay(350) // não baixa a ficha de cada capa que o mouse só atravessa
        value = Repository.fichaDe(r)?.s
    }
    val fundo = d?.fundo
    Box(modifier.fillMaxWidth()) {
        Crossfade(fundo, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight(), animationSpec = tween(300)) { img ->
            if (img != null) Box(Modifier.fillMaxSize()) {
                AsyncImage(nitida(img, fundo = true), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, filterQuality = FilterQuality.High)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.Background, 0.45f to Color(0x99000000), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Palette.Background)))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.52f).padding(start = PagePad, bottom = 18.dp)) {
            if (hero == null) return@Column
            if (!hero.subtitle.isNullOrBlank()) {
                Text(hero.subtitle, color = Palette.Brand, fontSize = 12.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
            }
            Text(hero.title, color = Palette.Text, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (d != null) {
                Spacer(Modifier.height(10.dp))
                MetaLine(d)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                sinopse ?: "", color = Palette.Synopsis, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(62.dp),
            )
            extra?.invoke()
        }
    }
}

@Composable
fun MetaLine(d: Repository.Destaque) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val parts = buildList {
            d.ano?.let { add(it.toString()) }
            if (d.serie && d.temporadas > 0) add(if (d.temporadas == 1) "1 temporada" else "${d.temporadas} temporadas")
            addAll(d.generos.take(3))
        }
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), color = Palette.TextDim, fontSize = 13.sp, maxLines = 1)
        if ('d' in d.versoes) Tag("DUB")
        if ('l' in d.versoes) Tag("LEG")
        if (d.k4) Tag("4K", Palette.Brand)
    }
}

/** Mensagem no meio da tela (carregando, vazio, erro). */
@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Palette.TextDim, fontSize = 16.sp, textAlign = TextAlign.Center)
            if (action != null) { Spacer(Modifier.height(16.dp)); PillButton(action.first, action.second) }
        }
    }
}
