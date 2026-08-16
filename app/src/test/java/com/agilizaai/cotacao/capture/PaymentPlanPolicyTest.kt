package com.agilizaai.cotacao.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPlanPolicyTest {
    @Test
    fun recognisesTheSelectedTermInTheHeader() {
        assertTrue(PaymentPlanPolicy.matches("14 DIAS", 14))
        assertTrue(PaymentPlanPolicy.matches("PLANO DE PAGAMENTO 28 DIAS BOLETO", 28))
    }

    @Test
    fun neverConfusesOneTermWithALongerNumber() {
        assertFalse(PaymentPlanPolicy.matches("128 DIAS", 28))
        assertFalse(PaymentPlanPolicy.matches("7 DIAS", 14))
        assertFalse(PaymentPlanPolicy.matches("21 DIAS", 7))
    }

    @Test
    fun findsTheTermAcrossTheWholeHeader() {
        val header = listOf("NOVO PEDIDO", "PLANO DE PAGAMENTO", "21 DIAS", "TOTAL R 63 94")
        assertTrue(PaymentPlanPolicy.matchesAny(header, 21))
        assertFalse(PaymentPlanPolicy.matchesAny(header, 28))
    }
}
