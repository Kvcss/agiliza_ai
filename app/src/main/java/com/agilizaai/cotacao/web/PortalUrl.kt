package com.agilizaai.cotacao.web

import java.net.URI

/**
 * Regras do endereço do portal, compartilhadas pela configuração, pela tela e pelo
 * serviço de preenchimento.
 *
 * Portais de cotação internos costumam ficar em um IP da rede da empresa com porta
 * própria e sem certificado (`http://177.94.206.10:8081/php/vrcotacao/login.php`).
 * Exigir HTTPS deixava a funcionalidade inteira inacessível, então `http` é aceito —
 * mas identificado como sem criptografia para que a tela avise antes de guardar a
 * senha, e a navegação continua presa à origem cadastrada.
 */
internal object PortalUrl {

    /** Endereço aceitável: http/https, com host e sem usuário embutido na URL. */
    fun parse(url: String): URI? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null) return null
        return uri
    }

    fun defaultPort(scheme: String?): Int = if (scheme?.lowercase() == "http") 80 else 443

    fun portOf(uri: URI): Int = if (uri.port == -1) defaultPort(uri.scheme) else uri.port

    /**
     * Identidade do portal usada para prender a credencial ao endereço cadastrado.
     * O esquema faz parte dela: trocar https por http muda para onde a senha vai.
     */
    fun origin(uri: URI): String =
        "${uri.scheme.lowercase()}://${uri.host.lowercase()}:${portOf(uri)}"

    fun origin(scheme: String, host: String, port: Int): String =
        "${scheme.lowercase()}://${host.lowercase()}:$port"

    fun isCleartext(uri: URI): Boolean = uri.scheme?.lowercase() == "http"
}
