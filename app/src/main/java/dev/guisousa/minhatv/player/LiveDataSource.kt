package dev.guisousa.minhatv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Canal ao vivo em .ts direto: o servidor costuma fechar a conexão de tempos em tempos.
 * Em vez de o player achar que o vídeo acabou (e piscar reconectando), esta fonte fecha a
 * conexão velha, espera um pouco e abre outra, emendando os dados sem a imagem parar.
 */
@OptIn(UnstableApi::class)
class LiveDataSource(private val upstream: DataSource, private val onReopen: () -> Unit) : DataSource {

    class Factory(private val upstream: DataSource.Factory, private val onReopen: () -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveDataSource(upstream.createDataSource(), onReopen)
    }

    private var spec: DataSpec? = null
    private var reopensInARow = 0
    private var bytesSinceOpen = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        bytesSinceOpen = 0
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong() // ao vivo não tem fim
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val n = upstream.read(buffer, offset, length)
            if (n != C.RESULT_END_OF_INPUT) {
                if (n > 0) { bytesSinceOpen += n; if (bytesSinceOpen > 256 * 1024) reopensInARow = 0 }
                return n
            }
            // o servidor fechou: reabre (desiste depois de várias seguidas sem receber quase nada)
            if (++reopensInARow > 5) return C.RESULT_END_OF_INPUT
            val s = spec ?: return C.RESULT_END_OF_INPUT
            runCatching { upstream.close() }
            Thread.sleep(700) // dá tempo de o servidor liberar a conexão antiga (limite de telas)
            onReopen()
            bytesSinceOpen = 0
            upstream.open(s.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
        }
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}
