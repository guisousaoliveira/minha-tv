package dev.guisousa.minhatv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Servidores de IPTV redirecionam o link do canal (.m3u8) para um endereço com senha temporária,
 * que só vale uma vez ou por poucos segundos. O player guardava esse endereço e pedia a playlist
 * de novo nele a cada atualização: 404 a cada ~30 s, tela travada e reconexão.
 *
 * Aqui, toda vez que o player pede de novo uma playlist que veio de um redirecionamento, o pedido
 * vai para o link ORIGINAL do canal, que gera um endereço novo e válido. O vídeo não para.
 */
@OptIn(UnstableApi::class)
class StableRedirectDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        /** endereço temporário -> link original (compartilhado entre as conexões do mesmo player) */
        private val originals = ConcurrentHashMap<String, Uri>()
        override fun createDataSource(): DataSource = StableRedirectDataSource(upstream.createDataSource()).also { it.originals = originals }
    }

    private var originals = ConcurrentHashMap<String, Uri>()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val asked = dataSpec.uri
        val original = originals[asked.toString()]
        val spec = if (original != null) dataSpec.withUri(original) else dataSpec
        val length = upstream.open(spec)
        val landed = upstream.uri
        val root = original ?: asked
        if (landed != null && landed != spec.uri && isPlaylist(root)) {
            if (originals.size > 200) originals.clear()
            if (original == null) android.util.Log.w("MinhaTV", "player: playlist redirecionada, as próximas atualizações vão pelo link original")
            originals[landed.toString()] = root
        }
        return length
    }

    private fun isPlaylist(u: Uri): Boolean {
        val p = (u.path ?: "").lowercase()
        return p.endsWith(".m3u8") || p.endsWith(".m3u")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}
