package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductNameMatcherTest {
    @Test
    fun correlatesNescauAbbreviationPunctuationAndWordOrder() {
        val captured = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6")

        assertEquals(
            captured,
            ProductNameMatcher.find(
                "Nescau achocolatado em pó 200 gramas",
                listOf(captured),
                expectedUnitsPerCase = "6"
            )?.item
        )
    }

    @Test
    fun rejectsNescauWithDifferentWeightEvenWhenItIsTheOnlyCandidate() {
        val twoHundred = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6")

        assertNull(
            ProductNameMatcher.find(
                "ACHOCOLATADO NESCAU EM PO 350 GR",
                listOf(twoHundred),
                expectedUnitsPerCase = "6"
            )
        )
    }

    @Test
    fun choosesCorrectNescauWeightAmongSimilarDescriptions() {
        val twoHundred = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6")
        val threeHundredFifty = item("ACHOC.NESCAU PO 350G", unitsPerCase = "6")

        assertEquals(
            threeHundredFifty,
            ProductNameMatcher.find(
                "NESCAU ACHOCOLATADO EM PO 350 GRAMAS",
                listOf(twoHundred, threeHundredFifty),
                expectedUnitsPerCase = "6"
            )?.item
        )
    }

    @Test
    fun treatsEquivalentWeightAndVolumeUnitsAsTheSameVariant() {
        val coffee = item("CAFE TORRADO MARCA FORTE 500G")
        val juice = item("SUCO UVA MARCA BOA 1L")

        assertEquals(coffee, ProductNameMatcher.find("CAFE MARCA FORTE 0,5 KG TORRADO", listOf(coffee), "12")?.item)
        assertEquals(juice, ProductNameMatcher.find("SUCO MARCA BOA UVA 1000 ML", listOf(juice), "12")?.item)
    }

    @Test
    fun matchesCommonAbbreviationsButKeepsBrandImportant() {
        val nescau = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6")
        val toddy = item("ACHOC.TODDY PO 200G", unitsPerCase = "6")

        assertEquals(
            nescau,
            ProductNameMatcher.find("ACHOCOLATADO EM PO NESCAU 200G", listOf(nescau, toddy), "6")?.item
        )
        assertNull(ProductNameMatcher.find("ACHOCOLATADO EM PO OVOMALTINE 200G", listOf(nescau), "6"))
    }

    @Test
    fun rejectsDifferentElectricalOrNumericVariants() {
        val shower = item("CHUVEIRO DUCHA 127V 5400W")
        val mask = item("KIT 10 MASCARAS NINJA 1 ADULTO")

        assertNull(ProductNameMatcher.find("DUCHA CHUVEIRO 220 VOLTS 5400 WATTS", listOf(shower), "12"))
        assertNull(ProductNameMatcher.find("KIT 20 MASCARAS NINJA 1 ADULTO", listOf(mask), "12"))
    }

    @Test
    fun matchesSachetSynonymsAndPunctuation() {
        val tea = item("CHA LEÃO SAQUINHO CAMOMILA 25 SAQ. 25G")

        assertEquals(
            tea,
            ProductNameMatcher.find("CHA CAMOMILA LEAO C/ 25 SACHES - 25 GR", listOf(tea), "12")?.item
        )
    }

    @Test
    fun matchesAccentsAndPunctuationButRejectsDifferentSizes() {
        val items = listOf(
            item("CHA CHIMARRÃO GAÚCHO 500G TRADICIONAL"),
            item("CHA CHIMARRÃO GAÚCHO 1KG TRADICIONAL")
        )
        assertEquals(items[0], ProductNameMatcher.find("Cha chimarrao gaucho 500g tradicional", items)?.item)
        assertNull(ProductNameMatcher.find("Cha chimarrao gaucho 250g tradicional", items))
    }

    @Test
    fun leavesAmbiguousDescriptionsUnmatched() {
        val items = listOf(item("COPO DESCARTAVEL BRANCO 180ML"), item("COPO DESCARTAVEL CRISTAL 180ML"))
        assertNull(ProductNameMatcher.find("COPO DESCARTAVEL 180ML", items))
    }

    @Test
    fun leavesExactNamePackagingVariantsUnmatched() {
        val items = listOf(
            item("SABONETE LIQUIDO", unitsPerCase = "4"),
            item("SABONETE LIQUIDO", unitsPerCase = "12")
        )

        assertNull(ProductNameMatcher.find("SABONETE LIQUIDO", items))
    }

    @Test
    fun usesQuotationCaseQuantityToSelectExactPackagingVariant() {
        val four = item("SABONETE LIQUIDO", unitsPerCase = "4")
        val twelve = item("SABONETE LIQUIDO", unitsPerCase = "12")

        assertEquals(twelve, ProductNameMatcher.find("SABONETE LIQUIDO", listOf(four, twelve), "12")?.item)
        assertEquals(four, ProductNameMatcher.find("SABONETE LIQUIDO", listOf(four, twelve), "4,0")?.item)
    }

    @Test
    fun separatesNescauCaseSixFromCaseTwentyFour() {
        val caseSix = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6")
        val caseTwentyFour = item("ACHOC.NESCAU PO 200G", unitsPerCase = "24")

        assertEquals(
            caseSix,
            ProductNameMatcher.find("ACHOCOLATADO NESCAU PO 200 GR", listOf(caseSix, caseTwentyFour), "6")?.item
        )
        assertEquals(
            caseTwentyFour,
            ProductNameMatcher.find("NESCAU ACHOCOLATADO 200G PO", listOf(caseSix, caseTwentyFour), "24")?.item
        )
        assertNull(ProductNameMatcher.find("ACHOCOLATADO NESCAU PO 200G", listOf(caseSix, caseTwentyFour)))
    }

    @Test
    fun excelBoxPolicyPrefersSingleCjAndIgnoresQuotationCaseQuantity() {
        val cjSix = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6", salesUnit = "CJ")
        val cxTwentyFour = item("ACHOC.NESCAU PO 200G", unitsPerCase = "24", salesUnit = "CX")

        assertEquals(
            cjSix,
            ProductNameMatcher.find(
                "ACHOCOLATADO NESCAU PO 200 GR",
                listOf(cjSix, cxTwentyFour),
                expectedUnitsPerCase = "24",
                variantPolicy = ProductNameMatcher.VariantPolicy.PREFER_SINGLE_CJ
            )?.item
        )
    }

    @Test
    fun excelBoxPolicyLeavesMultipleCompatibleCjVariantsUnmatched() {
        val cjSix = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6", salesUnit = "CJ")
        val anotherCj = item("ACHOC.NESCAU PO 200G", unitsPerCase = "12", salesUnit = "CJ")
        val cxTwentyFour = item("ACHOC.NESCAU PO 200G", unitsPerCase = "24", salesUnit = "CX")

        assertNull(
            ProductNameMatcher.find(
                "ACHOCOLATADO NESCAU PO 200G",
                listOf(cjSix, anotherCj, cxTwentyFour),
                variantPolicy = ProductNameMatcher.VariantPolicy.PREFER_SINGLE_CJ
            )
        )
    }

    @Test
    fun excelBoxPolicyWithoutCjRequiresExactlyOneCompatibleVariant() {
        val cxSix = item("ACHOC.NESCAU PO 200G", unitsPerCase = "6", salesUnit = "CX")
        val cxTwentyFour = item("ACHOC.NESCAU PO 200G", unitsPerCase = "24", salesUnit = "CX")

        assertEquals(
            cxSix,
            ProductNameMatcher.find(
                "ACHOCOLATADO NESCAU PO 200G",
                listOf(cxSix),
                variantPolicy = ProductNameMatcher.VariantPolicy.PREFER_SINGLE_CJ
            )?.item
        )
        assertNull(
            ProductNameMatcher.find(
                "ACHOCOLATADO NESCAU PO 200G",
                listOf(cxSix, cxTwentyFour),
                variantPolicy = ProductNameMatcher.VariantPolicy.PREFER_SINGLE_CJ
            )
        )
    }

    @Test
    fun excelBoxPolicyResolvesExactProductBeforePreferringCjFromAnotherBrand() {
        val requestedName = "BISCOITO WAFER RECHEADO SABOR CHOCOLATE MARILAN 140G"
        val exactCx = item(requestedName, unitsPerCase = "24", salesUnit = "CX")
        val otherBrandCj = item(
            "BISCOITO WAFER RECHEADO SABOR CHOCOLATE BAUDUCCO 140G",
            unitsPerCase = "24",
            salesUnit = "CJ"
        )

        // This intentionally proves the competing description clears the fuzzy
        // threshold; the exact product identity must nevertheless win first.
        assertTrue(ProductNameMatcher.similarity(requestedName, otherBrandCj.description) >= 0.78)
        assertEquals(
            exactCx,
            ProductNameMatcher.find(
                requestedName,
                listOf(otherBrandCj, exactCx),
                variantPolicy = ProductNameMatcher.VariantPolicy.PREFER_SINGLE_CJ
            )?.item
        )
    }

    @Test
    fun doesNotFallbackWhenRequestedCaseDoesNotExist() {
        val items = listOf(item("SABONETE LIQUIDO", unitsPerCase = "4"))

        assertNull(ProductNameMatcher.find("SABONETE LIQUIDO", items, "12"))
    }

    private fun item(name: String, unitsPerCase: String = "12", salesUnit: String = "CX") = QuoteItem(
        id = "$name-$unitsPerCase-$salesUnit",
        capturedAt = 1,
        conditionDays = 7,
        sourcePackage = "ion",
        productCode = name,
        barcode = "",
        description = name,
        packaging = "CX-$unitsPerCase",
        quantity = unitsPerCase,
        unitPrice = "10",
        rawCells = emptyList(),
        pricesByDays = mapOf(7 to "10", 14 to "11", 21 to "12", 28 to "13"),
        unitsPerCase = unitsPerCase,
        salesUnit = salesUnit
    )
}
