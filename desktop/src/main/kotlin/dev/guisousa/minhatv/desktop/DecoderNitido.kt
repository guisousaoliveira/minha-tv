package dev.guisousa.minhatv.desktop

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * Decodificador de imagens do Coil com redução de boa qualidade.
 * O do Coil 3.0 para PC reduz a capa para o tamanho do quadro com "vizinho mais próximo"
 * (sem filtro), e por isso as capas ficavam quadriculadas. Aqui reduz com mipmap + linear
 * (reduções grandes) ou cúbica (reduções pequenas), que dá o resultado liso.
 */
class DecoderNitido(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val image = Image.makeFromEncoded(bytes)
        try {
            val sw = image.width
            val sh = image.height
            val tw = (options.size.width as? Dimension.Pixels)?.px ?: sw
            val th = (options.size.height as? Dimension.Pixels)?.px ?: sh
            var mult = DecodeUtils.computeSizeMultiplier(sw, sh, tw, th, options.scale).coerceAtMost(1.0)
            // nunca maior que 4096 de lado
            mult = minOf(mult, 4096.0 / maxOf(sw, sh))
            val ow = (sw * mult).toInt().coerceAtLeast(1)
            val oh = (sh * mult).toInt().coerceAtLeast(1)
            val bitmap = Bitmap()
            bitmap.allocN32Pixels(ow, oh)
            val sampling = if (mult < 0.5) FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR) else SamplingMode.MITCHELL
            Canvas(bitmap).use { c ->
                Paint().use { paint ->
                    c.drawImageRect(image, Rect.makeWH(sw.toFloat(), sh.toFloat()), Rect.makeWH(ow.toFloat(), oh.toFloat()), sampling, paint, true)
                }
            }
            bitmap.setImmutable()
            return DecodeResult(image = bitmap.asImage(), isSampled = ow < sw || oh < sh)
        } finally {
            image.close()
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
            DecoderNitido(result.source, options)
    }
}
