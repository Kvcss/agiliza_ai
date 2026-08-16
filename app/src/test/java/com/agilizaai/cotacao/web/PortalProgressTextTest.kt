package com.agilizaai.cotacao.web

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalProgressTextTest {

    @Test
    fun `no estimate is announced while there is no reliable measurement`() {
        assertEquals("", PortalProgressText.remaining(-1))
    }

    @Test
    fun `the final seconds stop promising a number`() {
        assertEquals("quase terminando", PortalProgressText.remaining(0))
        assertEquals("quase terminando", PortalProgressText.remaining(10))
    }

    @Test
    fun `seconds are rounded so the estimate never looks exact`() {
        assertEquals("cerca de 20 s restantes", PortalProgressText.remaining(18))
        assertEquals("cerca de 50 s restantes", PortalProgressText.remaining(52))
    }

    @Test
    fun `minutes agree in number with the portuguese sentence`() {
        assertEquals("cerca de 1 min restante", PortalProgressText.remaining(70))
        assertEquals("cerca de 3 min restantes", PortalProgressText.remaining(190))
        assertEquals("cerca de 59 min restantes", PortalProgressText.remaining(3_540))
    }

    @Test
    fun `a long wait is broken into hours and minutes`() {
        assertEquals("cerca de 1 h restante", PortalProgressText.remaining(3_600))
        assertEquals("cerca de 1h 30min restantes", PortalProgressText.remaining(5_400))
        assertEquals("cerca de 2 h restantes", PortalProgressText.remaining(7_200))
    }

    @Test
    fun `rounding never produces sixty seconds or sixty minutes`() {
        // Os pontos exatos de virada: um segundo antes de cada unidade cheia.
        assertEquals("cerca de 1 min restante", PortalProgressText.remaining(59))
        assertEquals("cerca de 1 h restante", PortalProgressText.remaining(3_599))
        assertEquals("cerca de 2 h restantes", PortalProgressText.remaining(7_199))
    }

    @Test
    fun `elapsed time keeps growing while the app waits for the page`() {
        assertEquals("", PortalProgressText.elapsed(0L))
        assertEquals("há 4s", PortalProgressText.elapsed(4_500L))
        assertEquals("há 1min 30s", PortalProgressText.elapsed(90_000L))
        assertEquals("há 1h 5min", PortalProgressText.elapsed(3_900_000L))
    }

    @Test
    fun `counts are grouped in thousands like the rest of the interface`() {
        assertEquals("0", PortalProgressText.count(0))
        assertEquals("999", PortalProgressText.count(999))
        assertEquals("3.879", PortalProgressText.count(3879))
        assertEquals("12.345", PortalProgressText.count(12345))
    }
}
