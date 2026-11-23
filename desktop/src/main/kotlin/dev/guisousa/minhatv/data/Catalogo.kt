package dev.guisousa.minhatv.data

import dev.guisousa.minhatv.core.CatalogoBase
import okhttp3.OkHttpClient
import java.io.File

typealias Fonte = dev.guisousa.minhatv.core.Fonte
typealias Canal = dev.guisousa.minhatv.core.Canal
typealias CardFilme = dev.guisousa.minhatv.core.CardFilme
typealias CardSerie = dev.guisousa.minhatv.core.CardSerie
typealias Cat = dev.guisousa.minhatv.core.Cat
typealias Categorias = dev.guisousa.minhatv.core.Categorias
typealias Versoes = dev.guisousa.minhatv.core.Versoes
typealias Ficha = dev.guisousa.minhatv.core.Ficha
typealias LinhaItem = dev.guisousa.minhatv.core.LinhaItem
typealias Linha = dev.guisousa.minhatv.core.Linha
typealias Apelidos = dev.guisousa.minhatv.core.Apelidos
typealias TmdbIds = dev.guisousa.minhatv.core.TmdbIds
typealias Manifesto = dev.guisousa.minhatv.core.Manifesto
typealias Episodio = dev.guisousa.minhatv.core.Episodio

fun ordemAoVivo(cats: List<Cat>): List<Cat> = dev.guisousa.minhatv.core.ordemAoVivo(cats)
fun <T> canaisPorIds(ids: List<Long>, porId: Map<Long, T>): List<T> = dev.guisousa.minhatv.core.canaisPorIds(ids, porId)

class Catalogo(dir: File, http: OkHttpClient, base: String = BASE) : CatalogoBase(
    dir = dir,
    http = http,
    base = base,
    onWarn = { msg, err -> Log.w(msg, err) },
) {
    companion object {
        const val BASE = CatalogoBase.BASE
        const val UA = CatalogoBase.UA
        val json = CatalogoBase.json
        fun defaultHttp(): OkHttpClient = CatalogoBase.defaultHttp()
        fun fichaParte(serie: Boolean, id: Long, n: Int): String = CatalogoBase.fichaParte(serie, id, n)
        fun usada(parte: String): Boolean = CatalogoBase.usada(parte)
        fun episodios(f: Ficha): List<Episodio> = CatalogoBase.episodios(f)
    }

    /** Guia: id do canal -> programas (horários em ms). */
    fun guia(): Map<String, List<Programme>> =
        guiaBruto("guia").ifEmpty { guiaBruto("guia_principais") }
            .mapValues { (_, list) -> list.map { Programme(it.start, it.end, it.title, it.desc) } }
}
