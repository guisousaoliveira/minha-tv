package dev.guisousa.minhatv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.guisousa.minhatv.data.Repository
import kotlinx.coroutines.delay

// Telas no estilo cinema (Início, Filmes, Séries): o fundo do item em foco ocupa a tela,
// com título grande, gêneros e sinopse à esquerda, e as fileiras de capas embaixo.

/** Altura da área do destaque (as fileiras começam logo abaixo). */
val CINEMA_HERO: Dp = 318.dp

/** Ficha do item em foco, só depois de ele ficar parado um pouco (não baixa nada enquanto você passa rápido). */
@Composable
private fun fichaParada(ref: String?) = produceState<dev.guisousa.minhatv.data.Ficha?>(null, ref) {
    value = null
    if (ref == null) return@produceState
    delay(650)
    value = Repository.fichaDe(ref)
}

/** Tela inteira no estilo cinema: fundo + destaque em cima, [rows] (as fileiras) embaixo. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CinemaLayout(header: HeaderState, lib: Any?, fallbackTitle: String, fallbackSub: String, rows: @Composable () -> Unit) {
    val destaque = remember(header.ref, lib) { Repository.destaque(header.ref) }
    val ficha by fichaParada(header.ref)
    Box(Modifier.fillMaxSize()) {
        CinemaBackdrop(header, destaque, ficha)
        Column(Modifier.fillMaxSize()) {
            CinemaInfo(header, destaque, ficha, fallbackTitle, fallbackSub)
            // a fileira em foco sobe para o topo da área das fileiras (com o título dela à mostra),
            // em vez de parar cortada pela metade embaixo do destaque
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
            val spec = remember(px) { TopSnapSpec(px) }
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) { rows() }
            }
        }
    }
}

/** Fundo da tela: imagem larga do item em foco, sumindo no preto à esquerda e embaixo. */
@Composable
private fun CinemaBackdrop(header: HeaderState, destaque: Repository.Destaque?, ficha: dev.guisousa.minhatv.data.Ficha?) {
    val fundo = destaque?.fundo ?: ficha?.b
    Box(Modifier.fillMaxSize()) {
        when {
            header.isLogo && !header.image.isNullOrBlank() -> {
                // canal: logo inteira à direita, num brilho suave
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 80.dp).size(width = 380.dp, height = 214.dp)
                        .background(Brush.radialGradient(listOf(Color(0x3300E5FF), Color.Transparent))),
                ) {
                    AsyncImage(header.image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(30.dp))
                }
            }
            fundo != null -> Crossfade(fundo, animationSpec = tween(350), label = "fundo", modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.80f).aspectRatio(16f / 9f)) { img ->
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(img, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                    FadeEdges()
                }
            }
            !header.image.isNullOrBlank() -> Crossfade(header.image, animationSpec = tween(300), label = "capa", modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.34f).fillMaxHeight(0.78f)) { img ->
                // sem fundo largo: a capa à direita, apagada
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(img, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, alpha = 0.55f, modifier = Modifier.fillMaxSize())
                    FadeEdges()
                }
            }
        }
    }
}

@Composable
private fun BoxScope.FadeEdges() {
    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Palette.Background, 0.42f to Color(0xB3000000), 0.75f to Color.Transparent)))
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.82f to Color(0xCC000000), 1f to Palette.Background)))
}

/** Texto do destaque: tipo, título grande, ano/gêneros/versões e sinopse. */
@Composable
private fun CinemaInfo(header: HeaderState, destaque: Repository.Destaque?, ficha: dev.guisousa.minhatv.data.Ficha?, fallbackTitle: String, fallbackSub: String) {
    Column(Modifier.fillMaxWidth().height(CINEMA_HERO).padding(start = 56.dp, top = 84.dp, end = 56.dp)) {
        Text(
            (header.subtitle ?: fallbackSub).uppercase(), fontSize = 12.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Bold,
            color = Palette.Brand, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp),
        )
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            header.title ?: fallbackTitle,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "titulo",
        ) { t ->
            Text(
                t, fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Text,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(600.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (destaque != null) MetaLine(destaque, ficha?.g)
        else if (!header.detail.isNullOrBlank()) {
            Text(header.detail!!, fontSize = 15.sp, lineHeight = 22.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
        }
        val sinopse = ficha?.s
        if (!sinopse.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(sinopse, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFCFCFD4), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(540.dp))
        }
    }
}

@Composable
private fun MetaLine(d: Repository.Destaque, generosFicha: List<String>?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val partes = buildList {
            d.ano?.let { add("$it") }
            if (d.serie && d.temporadas > 0) add(if (d.temporadas == 1) "1 temporada" else "${d.temporadas} temporadas")
            addAll(d.generos.ifEmpty { generosFicha.orEmpty().take(2) })
        }
        if (partes.isNotEmpty()) Text(partes.joinToString("  ·  "), fontSize = 15.sp, color = Palette.Text, fontWeight = FontWeight.Medium)
        if ('d' in d.versoes) Tag("DUB")
        if ('l' in d.versoes) Tag("LEG")
        if (d.k4) Tag("4K")
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim,
        modifier = Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Rolagem vertical que deixa o item em foco no topo (menos [margin], o espaço do título da fileira). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class TopSnapSpec(private val margin: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - margin
}

/** Rolagem padrão (só o necessário para o item aparecer): usada nas fileiras horizontais. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal object MinimalScrollSpec : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size - containerSize
        return when {
            offset >= 0 && trailing <= 0 -> 0f
            offset < 0 -> offset
            else -> trailing
        }
    }
}
