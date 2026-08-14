package com.agilizaai.cotacao.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IonProductReaderTest {
    @Test
    fun readsActualIonListIdsAndMergesDetailFields() {
        val list = listOf(
            node("79.458", "textViewCodigoProduto", 100),
            node("*CESTA C/ RODIZIO GRAVACAO SORRISO", "textViewDescricao", 110),
            node("7898924235997", "textViewCodigoBarras", 130),
            node("UN", "textViewUnidade", 130),
            node("1", "textViewEmbalagem", 150),
            node("59,700", "textViewPrecoTabela", 170),
            node("164,00", "textViewEstoque", 150)
        )
        val product = IonProductReader.readList(list).single()
        val detail = IonProductReader.readDetail(
            nodes = listOf(
                node("79.458", "textViewCodigoProduto", 80),
                node("*CESTA C/ RODIZIO GRAVACAO SORRISO", "textViewProduto", 100),
                node("1,000/1,000", "textViewQtUnitCX", 180),
                node("59,700", "textViewProdutoPrecoTabela", 180),
                node("UN", "textViewProdutoUN", 220)
            ),
            listProduct = product,
            conditionDays = 14,
            sourcePackage = "br.com.ionsistemas.ionvendas",
            capturedAt = 10L
        )!!

        assertEquals("7898924235997", detail.barcode)
        assertEquals("59.700", detail.priceForDays(14))
        assertEquals("1", detail.unitsPerCase)
        assertEquals("59.700", detail.casePriceForDays(14)!!.toPlainString())
        assertTrue(detail.rawCells.any { it.contains("Cx Unit/Tot") })
    }

    @Test
    fun readsIon1267GenericProductCards() {
        val nodes = listOf(
            genericNode("79.458", "textViewRowCustom1", 288, 472, 432, 525),
            genericNode("*CESTA C/ RODIZIO GRAVACAO SORRISO", "textViewRowCustom1", 432, 444, 900, 552),
            genericNode("Cód. Emb.:", "textViewRowCustom", 288, 552, 432, 603),
            genericNode("7898924235997", "textViewRowCustom3", 432, 552, 720, 603),
            genericNode("Und:", "textViewRowCustom", 720, 552, 828, 603),
            genericNode("UN", "textViewRowCustom3", 828, 552, 900, 603),
            genericNode("Est:", "textViewRowCustom", 720, 603, 828, 654),
            genericNode("164,00", "textViewRowCustom3", 828, 605, 1008, 651),
            genericNode("Emb:", "textViewRowCustom", 288, 654, 468, 705),
            genericNode("12", "textViewRowCustom3", 468, 654, 648, 705),
            genericNode("Preço:", "textViewRowCustom", 288, 705, 432, 756),
            genericNode("59,700", "textViewRowCustom3", 432, 707, 576, 753),
            genericNode("115.967", "textViewRowCustom1", 288, 841, 432, 894),
            genericNode("*DISPLAY ESMALTE RISQUE", "textViewRowCustom1", 432, 813, 900, 921),
            genericNode("Cód. Emb.:", "textViewRowCustom", 288, 921, 432, 972),
            genericNode("7893596204005", "textViewRowCustom3", 432, 921, 720, 972),
            genericNode("Und:", "textViewRowCustom", 720, 921, 828, 972),
            genericNode("UN", "textViewRowCustom3", 828, 921, 900, 972),
            genericNode("Emb:", "textViewRowCustom", 288, 1023, 468, 1074),
            genericNode("1", "textViewRowCustom3", 468, 1023, 648, 1074),
            genericNode("Preço:", "textViewRowCustom", 288, 1074, 432, 1125),
            genericNode("54,725", "textViewRowCustom3", 432, 1076, 576, 1122)
        )

        val products = IonProductReader.readList(nodes)

        assertEquals(2, products.size)
        assertEquals("79.458", products[0].productCode)
        assertEquals("*CESTA C/ RODIZIO GRAVACAO SORRISO", products[0].description)
        assertEquals("7898924235997", products[0].barcode)
        assertEquals("UN", products[0].unit)
        assertEquals("12", products[0].packaging)
        assertEquals("59.700", products[0].unitPrice)
        assertEquals("164,00", products[0].stock)
        assertEquals("54.725", products[1].unitPrice)
        assertTrue(IonProductReader.isProductList(nodes))
    }

    @Test
    fun keepsSameProductCodeWhenPackagingVariantDiffers() {
        val nodes = listOf(
            genericNode("11-111-2", "textViewRowCustom1", 20, 100, 120, 130),
            genericNode("PRODUTO TESTE", "textViewRowCustom1", 130, 100, 390, 130),
            genericNode("Cód. Emb.:", "textViewRowCustom", 20, 145, 130, 170),
            genericNode("7890000000001", "textViewRowCustom3", 130, 145, 280, 170),
            genericNode("Emb:", "textViewRowCustom", 20, 180, 90, 205),
            genericNode("4", "textViewRowCustom3", 90, 180, 130, 205),
            genericNode("Preço:", "textViewRowCustom", 20, 215, 90, 240),
            genericNode("10,00", "textViewRowCustom3", 90, 215, 150, 240),
            genericNode("11-111-2", "textViewRowCustom1", 20, 400, 120, 430),
            genericNode("PRODUTO TESTE", "textViewRowCustom1", 130, 400, 390, 430),
            genericNode("Cód. Emb.:", "textViewRowCustom", 20, 445, 130, 470),
            genericNode("7890000000002", "textViewRowCustom3", 130, 445, 280, 470),
            genericNode("Emb:", "textViewRowCustom", 20, 480, 90, 505),
            genericNode("12", "textViewRowCustom3", 90, 480, 130, 505),
            genericNode("Preço:", "textViewRowCustom", 20, 515, 90, 540),
            genericNode("30,00", "textViewRowCustom3", 90, 515, 150, 540)
        )

        val products = IonProductReader.readList(nodes)

        assertEquals(2, products.size)
        assertEquals(listOf("4", "12"), products.map { it.packaging })
        assertTrue(products[0].stableKey != products[1].stableKey)
    }

    @Test
    fun variantIdMergesTermsButSeparatesCaseSizes() {
        fun product(packaging: String, barcode: String) = IonProductReader.readList(
            listOf(
                node("11-111-2", "textViewCodigoProduto", 100),
                node("PRODUTO TESTE", "textViewDescricao", 110),
                node(barcode, "textViewCodigoBarras", 130),
                node(packaging, "textViewEmbalagem", 150),
                node("10,00", "textViewPrecoTabela", 170)
            )
        ).single()

        fun detail(product: IonProductReader.ListProduct, days: Int, units: String) =
            IonProductReader.readDetail(
                nodes = listOf(
                    node("11-111-2", "textViewCodigoProduto", 80),
                    node("PRODUTO TESTE", "textViewProduto", 100),
                    node("$units,000/$units,000", "textViewQtUnitCX", 180),
                    node("10,00", "textViewProdutoPrecoTabela", 180)
                ),
                listProduct = product,
                conditionDays = days,
                sourcePackage = "ion",
                capturedAt = days.toLong()
            )!!

        val case4 = product("4", "7890000000001")
        val case12 = product("12", "7890000000002")
        val case4At7 = detail(case4, 7, "4")
        val case4At28 = detail(case4, 28, "4")
        val case12At7 = detail(case12, 7, "12")

        assertEquals(case4At7.id, case4At28.id)
        assertTrue(case4At7.id != case12At7.id)
    }

    @Test
    fun safelyMatchesFullBarcodeToItsTruncatedVariantOnly() {
        val truncated = IonProductReader.variantIdentity(
            productCode = "11-111-2",
            barcode = "789000000000",
            barcodeTruncated = true,
            description = "PRODUTO TESTE",
            packaging = "4",
            unitsPerCase = "4"
        )
        val full = IonProductReader.variantIdentity(
            productCode = "11.111.2",
            barcode = "7890000000001",
            barcodeTruncated = false,
            description = "PRODUTO TESTE",
            packaging = "4,000",
            unitsPerCase = "4.0"
        )
        val otherCase = full.copy(packaging = "12", unitsPerCase = "12")

        assertTrue(truncated.isCompatibleWith(full))
        assertTrue(!truncated.isCompatibleWith(otherCase))
    }

    private fun node(text: String, id: String, y: Int) = NodeSnapshot(
        text = text,
        viewId = "br.com.ionsistemas.ionvendas:id/$id",
        className = "android.widget.TextView",
        packageName = "br.com.ionsistemas.ionvendas",
        left = 10,
        top = y,
        right = 400,
        bottom = y + 20,
        childCount = 0
    )

    private fun genericNode(
        text: String,
        id: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = NodeSnapshot(
        text = text,
        viewId = "br.com.ionsistemas.ionvendas:id/$id",
        className = "android.widget.TextView",
        packageName = "br.com.ionsistemas.ionvendas",
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        childCount = 0
    )
}
