package com.agilizaai.cotacao.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalUrlTest {

    @Test
    fun `the internal portal on an ip with a port is accepted`() {
        val uri = PortalUrl.parse("http://177.94.206.10:8081/php/vrcotacao/login.php")

        assertNotNull(uri)
        assertEquals(8081, PortalUrl.portOf(uri!!))
        assertEquals("http://177.94.206.10:8081", PortalUrl.origin(uri))
        assertTrue(PortalUrl.isCleartext(uri))
    }

    @Test
    fun `https keeps working and is not flagged as cleartext`() {
        val uri = PortalUrl.parse("https://cotacao.exemplo.com.br/login")!!

        assertEquals(443, PortalUrl.portOf(uri))
        assertEquals("https://cotacao.exemplo.com.br:443", PortalUrl.origin(uri))
        assertFalse(PortalUrl.isCleartext(uri))
    }

    @Test
    fun `the default port follows the scheme so the origin never collides`() {
        val insecure = PortalUrl.parse("http://portal.exemplo.com/login")!!

        assertEquals(80, PortalUrl.portOf(insecure))
        assertEquals("http://portal.exemplo.com:80", PortalUrl.origin(insecure))
    }

    @Test
    fun `changing only the scheme changes the origin, forcing the password again`() {
        val insecure = PortalUrl.origin(PortalUrl.parse("http://portal.exemplo.com:8081/x")!!)
        val secure = PortalUrl.origin(PortalUrl.parse("https://portal.exemplo.com:8081/x")!!)

        assertFalse(insecure == secure)
    }

    @Test
    fun `addresses that could send the credential elsewhere are refused`() {
        assertNull(PortalUrl.parse(""))
        assertNull(PortalUrl.parse("ftp://portal.exemplo.com/login"))
        assertNull(PortalUrl.parse("javascript:alert(1)"))
        assertNull(PortalUrl.parse("http:///php/login.php"))
        // Usuário embutido na URL é o truque clássico de disfarçar o host real.
        assertNull(PortalUrl.parse("http://vitima@atacante.example.com/login"))
    }

    @Test
    fun `the host comparison is case insensitive`() {
        val upper = PortalUrl.parse("HTTP://Portal.Exemplo.COM:8081/login")!!

        assertEquals("http://portal.exemplo.com:8081", PortalUrl.origin(upper))
    }
}
