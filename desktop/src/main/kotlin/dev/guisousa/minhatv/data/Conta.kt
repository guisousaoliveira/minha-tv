package dev.guisousa.minhatv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/** Sessão da conta guardada neste computador (%APPDATA%\BadWolfTV\conta.json). */
@Serializable
data class Sessao(
    val access: String? = null,
    val refresh: String? = null,
    val expira: Long = 0,
    val userId: String? = null,
    val nome: String = "",
    val jaViuBoasVindas: Boolean = false,
)

/**
 * Conta W4LLE (opcional), a mesma do site, da TV e do celular. Sem conta o app funciona igual; com conta,
 * onde parou, Minha lista e "já assisti" sincronizam ([Nuvem]). No PC entra com usuário e senha.
 * Adaptado do Conta.kt do celular (sem SharedPreferences: a sessão fica num arquivo).
 */
object Conta {
    const val SUPABASE_URL = "https://hkiifyxossklpnsavujw.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhraWlmeXhvc3NrbHBuc2F2dWp3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzcxNDc1MTAsImV4cCI6MjA5MjcyMzUxMH0.GFpAWo-8yJVALJIDoiPCfDGYyMgTkVubM8JjYzll5EA"

    data class Usuario(val id: String, val nome: String)

    private val store = JsonFile(File(AppDirs.data, "conta.json"), Sessao.serializer()) { Sessao() }
    @Volatile private var sessao = store.load()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenLock = Mutex()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _usuario = MutableStateFlow(sessao.userId?.takeIf { sessao.refresh != null }?.let { Usuario(it, sessao.nome) })
    /** Quem está conectado (null = sem conta). */
    val usuario: StateFlow<Usuario?> = _usuario

    val conectado get() = _usuario.value != null

    val jaViuBoasVindas: Boolean get() = sessao.jaViuBoasVindas

    fun marcarBoasVindasVistas() {
        if (!sessao.jaViuBoasVindas) guardar(sessao.copy(jaViuBoasVindas = true))
    }

    private fun guardar(s: Sessao) {
        sessao = s
        store.writeNow(s)
    }

    private fun post(path: String, body: String, bearer: String? = null): Response {
        val req = Request.Builder().url(SUPABASE_URL + path)
            .header("apikey", ANON_KEY)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute()
    }

    /** Lê a resposta de /auth/v1/token. null = resposta sem os campos esperados. */
    private fun sessaoDe(text: String, nome: String): Sessao? {
        val o = json.parseToJsonElement(text).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.content ?: return null
        val refresh = o["refresh_token"]?.jsonPrimitive?.content ?: return null
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        val userId = o["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return null
        return Sessao(access, refresh, System.currentTimeMillis() + expiresIn * 1000, userId, nome)
    }

    /** Troca o refresh token por uma sessão nova e guarda. Devolve false se a sessão não vale mais. */
    private fun renovar(refresh: String): Boolean {
        val body = buildJsonObject { put("refresh_token", refresh) }.toString()
        post("/auth/v1/token?grant_type=refresh_token", body).use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // 400/401: sessão revogada ou vencida. Outros erros (rede, 5xx): tenta de novo depois.
                if (r.code == 400 || r.code == 401) limpar()
                return false
            }
            val s = sessaoDe(text, sessao.nome) ?: return false
            guardar(s.copy(jaViuBoasVindas = true))
            return true
        }
    }

    /** Token para chamar o Supabase (renova sozinho perto de vencer). null = sem conta ou sem rede. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        tokenLock.withLock {
            val s = sessao
            val refresh = s.refresh ?: return@withLock null
            if (s.access != null && s.expira - System.currentTimeMillis() > 60_000) return@withLock s.access
            runCatching { if (renovar(refresh)) sessao.access else null }.getOrNull()
        }
    }

    private fun buscarNome(userId: String, access: String): String? = runCatching {
        val req = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/profiles?select=username&user_id=eq.$userId")
            .header("apikey", ANON_KEY).header("Authorization", "Bearer $access")
            .build()
        http.newCall(req).execute().use { r ->
            val arr = json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray
            arr?.firstOrNull()?.jsonObject?.get("username")?.jsonPrimitive?.content
        }
    }.getOrNull()

    private fun entrarComSenhaLocked(nome: String, senha: String): String? {
        val body = buildJsonObject { put("email", "$nome@w4lle.tv"); put("password", senha) }.toString()
        return runCatching {
            post("/auth/v1/token?grant_type=password", body).use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 400 || r.code == 401) return "Usuário ou senha incorretos."
                // 402: o Supabase do W4LLE passou do limite do plano e pausou o serviço (vale para o site também)
                if (r.code == 402) return "O servidor da conta W4LLE está pausado (passou do limite do plano). Tente mais tarde."
                if (!r.isSuccessful) return "Não consegui entrar agora (erro ${r.code}). Tente de novo."
                val s = sessaoDe(text, nome) ?: return "Resposta inesperada do servidor."
                val exibir = buscarNome(s.userId!!, s.access!!) ?: nome
                guardar(s.copy(nome = exibir, jaViuBoasVindas = true))
                _usuario.value = Usuario(s.userId, exibir)
                Log.i("conta: entrou como $exibir")
                null
            }
        }.getOrElse { Log.w("conta: login falhou", it); "Sem internet? Não consegui falar com o servidor." }
    }

    /**
     * Entra com usuário e senha da conta W4LLE. O site usa "usuario@w4lle.tv" como e-mail por baixo
     * (lib/auth.js do W4LLE). Devolve null se entrou, ou a mensagem do erro.
     */
    suspend fun entrarComSenha(usuario: String, senha: String): String? = withContext(Dispatchers.IO) {
        val nome = usuario.trim().lowercase().replace(Regex("\\s+"), "")
        if (nome.isEmpty() || senha.isEmpty()) return@withContext "Digite o usuário e a senha."
        tokenLock.withLock { entrarComSenhaLocked(nome, senha) }
    }

    /**
     * Cria uma nova conta grátis W4LLE / Bad Wolf (POST /auth/v1/signup).
     * Se o servidor já devolver refresh_token, autentica na hora; caso contrário faz login em seguida.
     * Devolve null se deu certo, ou a mensagem do erro.
     */
    suspend fun cadastrarComSenha(usuario: String, senha: String): String? = withContext(Dispatchers.IO) {
        val nome = usuario.trim().lowercase().replace(Regex("\\s+"), "")
        if (nome.length < 3) return@withContext "O usuário precisa ter pelo menos 3 caracteres."
        if (!nome.matches(Regex("[a-z0-9._-]+"))) return@withContext "Use apenas letras, números, ponto, traço ou sublinhado no usuário."
        if (senha.length < 6) return@withContext "A senha precisa ter pelo menos 6 caracteres."
        val body = buildJsonObject {
            put("email", "$nome@w4lle.tv")
            put("password", senha)
            put("data", buildJsonObject { put("username", nome) })
        }.toString()
        tokenLock.withLock {
            runCatching {
                post("/auth/v1/signup", body).use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.code == 402) return@withLock "O servidor da conta W4LLE está pausado (passou do limite do plano). Tente mais tarde."
                    if (r.code == 400 || r.code == 422) {
                        val lower = text.lowercase()
                        if ("already" in lower || "exists" in lower || "registered" in lower) {
                            return@withLock "Esse usuário já existe. Tente entrar ou escolha outro nome."
                        }
                        return@withLock "Não foi possível criar a conta. Confira o usuário e a senha."
                    }
                    if (!r.isSuccessful) return@withLock "Não consegui criar a conta agora (erro ${r.code}). Tente de novo."
                    val s = sessaoDe(text, nome)
                    if (s != null) {
                        val exibir = buscarNome(s.userId!!, s.access!!) ?: nome
                        guardar(s.copy(nome = exibir, jaViuBoasVindas = true))
                        _usuario.value = Usuario(s.userId, exibir)
                        Log.i("conta: criou e entrou como $exibir")
                        null
                    } else {
                        entrarComSenhaLocked(nome, senha)
                    }
                }
            }.getOrElse { Log.w("conta: cadastro falhou", it); "Sem internet? Não consegui falar com o servidor." }
        }
    }

    private fun limpar() {
        guardar(Sessao(jaViuBoasVindas = sessao.jaViuBoasVindas))
        _usuario.value = null
    }

    /** Sai da conta neste computador. O que está guardado aqui continua. */
    fun sair() {
        val access = sessao.access
        limpar()
        if (access != null) scope.launch { runCatching { post("/auth/v1/logout?scope=local", "{}", access).close() } }
    }
}
