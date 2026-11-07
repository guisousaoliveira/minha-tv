package dev.guisousa.minhatv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Conta W4LLE (opcional). Sem conta o app funciona igual; com conta, o histórico sincroniza
 * entre TV, celular, Windows e o site. Usa o mesmo Supabase do W4LLE.
 *
 * Login na TV: a TV mostra um código / QR. A pessoa abre a página no celular, entra com usuário e
 * senha, e a página manda a sessão para a TV por um canal ao vivo do Supabase, cifrada com uma chave
 * que sai do próprio código (quem não tem o código não consegue ler).
 */
object Conta {
    const val SUPABASE_URL = "https://hkiifyxossklpnsavujw.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhraWlmeXhvc3NrbHBuc2F2dWp3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzcxNDc1MTAsImV4cCI6MjA5MjcyMzUxMH0.GFpAWo-8yJVALJIDoiPCfDGYyMgTkVubM8JjYzll5EA"
    /** Página que a pessoa abre no celular. */
    const val PAGINA = "https://badwolf.pages.dev/entrar"

    data class Usuario(val id: String, val nome: String)

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenLock = Mutex()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _usuario = MutableStateFlow<Usuario?>(null)
    /** Quem está conectado (null = sem conta). */
    val usuario: StateFlow<Usuario?> = _usuario

    fun init(context: Context) {
        prefs = context.getSharedPreferences("conta", Context.MODE_PRIVATE)
        val id = prefs.getString("user_id", null)
        if (id != null && prefs.getString("refresh", null) != null) {
            _usuario.value = Usuario(id, prefs.getString("nome", null) ?: "")
        }
    }

    val conectado get() = _usuario.value != null

    // ---------- Sessão ----------

    private fun post(path: String, body: String, bearer: String? = null): Response {
        val req = Request.Builder().url(SUPABASE_URL + path)
            .header("apikey", ANON_KEY)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute()
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
            val o = json.parseToJsonElement(text).jsonObject
            val access = o["access_token"]?.jsonPrimitive?.content ?: return false
            val newRefresh = o["refresh_token"]?.jsonPrimitive?.content ?: return false
            val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
            val userId = o["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return false
            prefs.edit()
                .putString("access", access)
                .putString("refresh", newRefresh)
                .putLong("expira", System.currentTimeMillis() + expiresIn * 1000)
                .putString("user_id", userId)
                .apply()
            return true
        }
    }

    /** Token para chamar o Supabase (renova sozinho perto de vencer). null = sem conta ou sem rede. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        tokenLock.withLock {
            val refresh = prefs.getString("refresh", null) ?: return@withLock null
            val access = prefs.getString("access", null)
            if (access != null && prefs.getLong("expira", 0) - System.currentTimeMillis() > 60_000) return@withLock access
            runCatching { if (renovar(refresh)) prefs.getString("access", null) else null }.getOrNull()
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

    /** Entra com o refresh token que veio do celular. */
    private suspend fun entrar(refresh: String, nomeDica: String?): Boolean = withContext(Dispatchers.IO) {
        tokenLock.withLock {
            if (!runCatching { renovar(refresh) }.getOrDefault(false)) return@withLock false
            val id = prefs.getString("user_id", null) ?: return@withLock false
            val nome = buscarNome(id, prefs.getString("access", null)!!) ?: nomeDica ?: ""
            prefs.edit().putString("nome", nome).apply()
            _usuario.value = Usuario(id, nome)
            true
        }
    }

    private fun limpar() {
        prefs.edit().clear().apply()
        _usuario.value = null
    }

    /** Sai da conta nesta TV. O que está guardado no aparelho continua. */
    fun sair() {
        val access = prefs.getString("access", null)
        limpar()
        if (access != null) scope.launch { runCatching { post("/auth/v1/logout?scope=local", "{}", access).close() } }
    }

    // ---------- Login por código ----------

    /** Sem 0/O, 1/I/L, U: fácil de ler na TV e digitar no celular. */
    private const val ALFABETO = "23456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun novoCodigo(): String {
        val r = SecureRandom()
        return String(CharArray(8) { ALFABETO[r.nextInt(ALFABETO.length)] })
    }

    /** "ABCDEFGH" -> "ABCD-EFGH" */
    fun mostrarCodigo(c: String) = c.take(4) + "-" + c.drop(4)

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Mesmas contas da página (entrar/index.html): canal e chave saem do código. */
    fun canalDo(codigo: String) = "pair-" + hex(sha256("badwolf-canal:$codigo")).take(24)
    private fun chaveDo(codigo: String) = SecretKeySpec(sha256("badwolf-chave:$codigo"), "AES")

    /** Trocável nos testes (android.util.Base64 não roda fora do Android). */
    internal var b64: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) }

    internal fun decifrar(codigo: String, ivB64: String, ctB64: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chaveDo(codigo), GCMParameterSpec(128, b64(ivB64)))
        return String(c.doFinal(b64(ctB64)))
    }

    sealed interface EstadoLogin {
        data class Aguardando(val codigo: String) : EstadoLogin
        data object Entrando : EstadoLogin
        data class Pronto(val nome: String) : EstadoLogin
        data class Erro(val msg: String) : EstadoLogin
    }

    /**
     * Fica esperando o celular mandar a sessão. Troca de código a cada 10 min.
     * Cancele o Job ao fechar a tela.
     */
    fun esperarLogin(onEstado: (EstadoLogin) -> Unit): Job = scope.launch {
        while (isActive && !conectado) {
            val codigo = novoCodigo()
            onEstado(EstadoLogin.Aguardando(codigo))
            val recebido = kotlinx.coroutines.CompletableDeferred<Pair<String, String?>>()
            val canal = CanalAoVivo(canalDo(codigo)) { evento, payload ->
                if (evento != "sessao") return@CanalAoVivo
                runCatching {
                    val iv = payload["iv"]!!.jsonPrimitive.content
                    val ct = payload["ct"]!!.jsonPrimitive.content
                    val o = json.parseToJsonElement(decifrar(codigo, iv, ct)).jsonObject
                    recebido.complete(o["refresh_token"]!!.jsonPrimitive.content to o["nome"]?.jsonPrimitive?.content)
                }
            }
            canal.abrir()
            val r = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { recebido.await() }
            if (r == null) { canal.fechar(); continue }
            onEstado(EstadoLogin.Entrando)
            val ok = entrar(r.first, r.second)
            canal.enviar("resposta", buildJsonObject { put("ok", ok) })
            delay(800)
            canal.fechar()
            if (ok) { onEstado(EstadoLogin.Pronto(_usuario.value?.nome.orEmpty())); return@launch }
            onEstado(EstadoLogin.Erro("Não deu para entrar. Tente de novo."))
            delay(2500)
        }
    }

    /** Canal "broadcast" do Supabase Realtime (protocolo Phoenix por WebSocket). */
    internal class CanalAoVivo(nome: String, val onMsg: (String, JsonObject) -> Unit) {
        private val topic = "realtime:$nome"
        private var ws: WebSocket? = null
        private var ref = 1
        private var heartbeat: Job? = null
        @Volatile private var fechado = false

        fun abrir() {
            if (fechado) return
            val req = Request.Builder()
                .url("${SUPABASE_URL.replace("https://", "wss://")}/realtime/v1/websocket?apikey=$ANON_KEY&vsn=1.0.0")
                .build()
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    send(webSocket, topic, "phx_join", buildJsonObject {
                        putJsonObject("config") {
                            putJsonObject("broadcast") { put("self", false) }
                            put("private", false)
                        }
                    })
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    if (m["event"]?.jsonPrimitive?.content != "broadcast") return
                    val p = m["payload"]?.jsonObject ?: return
                    val ev = p["event"]?.jsonPrimitive?.content ?: return
                    onMsg(ev, p["payload"]?.jsonObject ?: JsonObject(emptyMap()))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconectar()
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconectar()
            })
            heartbeat?.cancel()
            heartbeat = scope.launch {
                while (isActive) {
                    delay(25_000)
                    ws?.let { send(it, "phoenix", "heartbeat", JsonObject(emptyMap())) }
                }
            }
        }

        private fun reconectar() {
            if (fechado) return
            scope.launch { delay(3000); abrir() }
        }

        @Synchronized
        private fun send(w: WebSocket, t: String, event: String, payload: JsonObject) {
            w.send(buildJsonObject {
                put("topic", t); put("event", event); put("payload", payload); put("ref", (ref++).toString())
            }.toString())
        }

        fun enviar(evento: String, payload: JsonObject) {
            ws?.let {
                send(it, topic, "broadcast", buildJsonObject {
                    put("type", "broadcast"); put("event", evento); put("payload", payload)
                })
            }
        }

        fun fechar() {
            fechado = true
            heartbeat?.cancel()
            ws?.close(1000, null)
        }
    }
}
