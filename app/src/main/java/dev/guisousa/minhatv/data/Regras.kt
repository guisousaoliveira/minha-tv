package dev.guisousa.minhatv.data

// Regras puras (sem Android) usadas pelo Repository e pelas telas: dá para testar na JVM.

/** Categoria principal de um filme/série: a que aparece no destaque e nos cartões. */
object CategoriaPrincipal {

    private val acentos = Regex("\\p{M}+")

    /** Categorias que não dizem "do que é" o título: versão, qualidade, sobra ou fileiras de vitrine. */
    private val secundaria = Regex("""\b(lancamentos?|em alta|legendad[oa]s?|4k|uhd|diversos?|outr[oa]s)\b""")

    fun normalizar(nome: String): String =
        acentos.replace(java.text.Normalizer.normalize(nome.lowercase(), java.text.Normalizer.Form.NFD), "").trim()

    /** true para Lançamentos, Em Alta, Legendados, 4K/UHD, Diversos/Outras. */
    fun ehSecundaria(nome: String): Boolean = secundaria.containsMatchIn(normalizar(nome))

    /**
     * Escolhe a categoria principal.
     * [sugerida] = campo "c" do servidor (vale se a categoria existir); [doTitulo] = ids das categorias em que
     * o título aparece, na ordem do catálogo; [nome] = nome da categoria (null se não existe);
     * [primeiraDoCatalogo] = id da primeira categoria do catálogo (a regra antiga a deixava por último).
     */
    fun escolher(sugerida: String?, doTitulo: List<String>, primeiraDoCatalogo: String?, nome: (String) -> String?): String? {
        if (!sugerida.isNullOrBlank() && nome(sugerida) != null) return sugerida
        doTitulo.firstOrNull { id -> nome(id)?.let { !ehSecundaria(it) } == true }?.let { return it }
        // nenhuma serve: regra antiga (a primeira que não é a de abertura, senão ela)
        return doTitulo.firstOrNull { it != primeiraDoCatalogo } ?: doTitulo.firstOrNull()
    }
}

/** Chaves da Minha lista. */
object FavRefs {
    private const val E = "v2:e:"

    /** Episódio ("v2:e:<id>:T:E") vira a série ("v2:e:<id>"): a Minha lista guarda a série. O resto fica igual. */
    fun normalizar(ref: String): String =
        if (ref.startsWith(E) && ref.indexOf(':', E.length) > 0) E + ref.substring(E.length).substringBefore(':') else ref

    /** true se a chave é de uma série inteira (sem temporada/episódio). */
    fun ehSerie(ref: String): Boolean = ref.startsWith(E) && ref.indexOf(':', E.length) < 0 && ref.length > E.length

    /** Converte um conjunto antigo de favoritos (episódios soltos viram a série, sem repetir). */
    fun converter(favs: Set<String>): Set<String> = favs.mapTo(LinkedHashSet()) { normalizar(it) }
}

/** Títulos das fileiras da tela inicial. */
object TituloFileira {
    private val feminino = listOf("netflix", "hbo", "max", "apple", "amazon", "crunchyroll", "claro", "univer", "band")
    private val masculino = listOf("prime", "disney", "globoplay", "paramount", "star", "discovery", "telecine", "youtube", "looke", "pluto", "mubi", "sbt")

    /** "da Netflix", "do Prime Video", "de Ação". */
    fun preposicao(nome: String): String {
        val n = CategoriaPrincipal.normalizar(nome)
        return when {
            feminino.any { n.startsWith(it) } -> "da"
            masculino.any { n.startsWith(it) } -> "do"
            else -> "de"
        }
    }

    /** Quando a mesma categoria aparece em filmes e em séries: "Filmes da Netflix" / "Séries da Netflix". */
    fun titulo(serie: Boolean, nome: String, repetida: Boolean): String =
        if (!repetida) nome else (if (serie) "Séries " else "Filmes ") + preposicao(nome) + " " + nome
}
