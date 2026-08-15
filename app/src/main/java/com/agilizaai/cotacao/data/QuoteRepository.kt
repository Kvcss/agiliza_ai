package com.agilizaai.cotacao.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class QuoteRepository(context: Context) {
    private val dataFile = File(context.filesDir, "quote_items.json")
    private val draftFile = File(context.filesDir, "quote_items_draft.json")
    private val atomicFile = AtomicFile(dataFile)
    private val atomicDraftFile = AtomicFile(draftFile)

    fun load(): List<QuoteItem> {
        synchronized(PROCESS_LOCK) {
            return nonEmptyDraftOrNull(read(draftFile, atomicDraftFile))
                ?: read(dataFile, atomicFile)
                ?: emptyList()
        }
    }

    fun hasDraft(): Boolean = synchronized(PROCESS_LOCK) {
        nonEmptyDraftOrNull(read(draftFile, atomicDraftFile)) != null
    }

    /**
     * Starts a new protected collection. The previous draft becomes the last
     * saved collection before the working slot is cleared.
     */
    fun beginNewCapture() {
        synchronized(PROCESS_LOCK) {
            val previousDraft = read(draftFile, atomicDraftFile)
            if (!previousDraft.isNullOrEmpty()) write(atomicFile, previousDraft)
            atomicDraftFile.delete()
        }
    }

    /** Atomically replaces the working collection after each product read. */
    fun replaceDraft(items: List<QuoteItem>) {
        synchronized(PROCESS_LOCK) {
            val protectedItems = eligible(items)
            if (protectedItems.isEmpty()) {
                // An empty working snapshot must never shadow quote_items.json with "[]".
                // Removing only the draft makes load() fall back to the last useful base.
                atomicDraftFile.delete()
            } else {
                write(atomicDraftFile, protectedItems)
            }
        }
    }

    fun replace(items: List<QuoteItem>) {
        synchronized(PROCESS_LOCK) {
            write(atomicFile, eligible(items))
            atomicDraftFile.delete()
        }
    }

    fun clear() {
        synchronized(PROCESS_LOCK) {
            atomicFile.delete()
            atomicDraftFile.delete()
        }
    }

    private fun read(file: File, source: AtomicFile): List<QuoteItem>? {
        if (!file.exists()) return null
        return runCatching {
            val payload = source.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(payload)
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toQuoteItem())
            }.let(::eligible)
        }.getOrNull()
    }

    private fun write(destination: AtomicFile, items: List<QuoteItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        val stream = destination.startWrite()
        try {
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(array.toString())
            writer.flush()
            destination.finishWrite(stream)
        } catch (error: Throwable) {
            destination.failWrite(stream)
            throw error
        }
    }

    private fun QuoteItem.toJson() = JSONObject().apply {
        put("id", id)
        put("capturedAt", capturedAt)
        put("conditionDays", conditionDays)
        put("sourcePackage", sourcePackage)
        put("productCode", productCode)
        put("barcode", barcode)
        put("description", description)
        put("packaging", packaging)
        put("quantity", quantity)
        put("unitPrice", unitPrice)
        put("unitsPerCase", unitsPerCase)
        put("stock", stock)
        put("salesUnit", salesUnit)
        put("pricesByDays", JSONObject().apply {
            pricesByDays.toSortedMap().forEach { (days, price) -> put(days.toString(), price) }
        })
        put("casePricesByDays", JSONObject().apply {
            casePricesByDays.toSortedMap().forEach { (days, price) -> put(days.toString(), price) }
        })
        put("rawCells", JSONArray(rawCells))
    }

    private fun JSONObject.toQuoteItem(): QuoteItem {
        val cells = optJSONArray("rawCells") ?: JSONArray()
        val rawCells = buildList {
            for (index in 0 until cells.length()) add(cells.optString(index))
        }
        val legacyDays = optInt("conditionDays", 7)
        val legacyPrice = optString("unitPrice")
        val unitsPerCase = optString("unitsPerCase", optString("quantity"))
        val storedPrices = optJSONObject("pricesByDays").toDayPrices().toMutableMap()
        if (storedPrices.isEmpty() && legacyPrice.isNotBlank()) {
            storedPrices[legacyDays] = legacyPrice
        }
        val storedCasePricesObject = optJSONObject("casePricesByDays")
        val hasDedicatedCasePrices = storedCasePricesObject != null
        val casePrices = if (hasDedicatedCasePrices) {
            storedCasePricesObject.toDayPrices()
        } else {
            // v0.6.1 named the detail's Pço Tabela values as pricesByDays. They are
            // authoritative case/package prices and must never be multiplied again.
            storedPrices
        }
        val unitPrices = if (hasDedicatedCasePrices) {
            storedPrices.ifEmpty { deriveUnitPricesFromCases(casePrices, unitsPerCase) }
        } else {
            deriveUnitPricesFromCases(casePrices, unitsPerCase)
        }
        val primaryDays = if (legacyDays in casePrices || legacyDays in unitPrices) {
            legacyDays
        } else {
            (casePrices.keys + unitPrices.keys).minOrNull() ?: legacyDays
        }
        return QuoteItem(
            id = optString("id"),
            capturedAt = optLong("capturedAt"),
            conditionDays = legacyDays,
            sourcePackage = optString("sourcePackage"),
            productCode = optString("productCode"),
            barcode = optString("barcode"),
            description = optString("description"),
            packaging = optString("packaging"),
            quantity = optString("quantity"),
            unitPrice = unitPrices[primaryDays].orEmpty(),
            rawCells = rawCells,
            pricesByDays = unitPrices,
            unitsPerCase = unitsPerCase,
            // Captures made before the dedicated field already kept the real detail value
            // as "Estoque: 164.0" in rawCells. Migrate only that explicit labelled value.
            stock = optString("stock").ifBlank { legacyStockFromRawCells(rawCells) },
            casePricesByDays = casePrices,
            salesUnit = optString("salesUnit").ifBlank { legacySalesUnitFromRawCells(rawCells) }
        )
    }

    /** Missing/unreadable stock is deliberately excluded, just like a value below three. */
    private fun eligible(items: List<QuoteItem>): List<QuoteItem> = items.filter(QuoteItem::hasMinimumStock)

    private fun JSONObject?.toDayPrices(): Map<Int, String> {
        if (this == null) return emptyMap()
        val prices = linkedMapOf<Int, String>()
        keys().forEach { key ->
            key.toIntOrNull()?.let { days ->
                optString(key).takeIf { it.isNotBlank() }?.let { prices[days] = it }
            }
        }
        return prices
    }

    companion object {
        private val PROCESS_LOCK = Any()
    }
}

internal fun deriveUnitPricesFromCases(casePrices: Map<Int, String>, unitsPerCase: String): Map<Int, String> {
    val units = unitsPerCase.toBrazilianDecimalOrNull()?.takeIf { it > java.math.BigDecimal.ZERO }
        ?: return emptyMap()
    return casePrices.mapNotNull { (days, value) ->
        value.toBrazilianDecimalOrNull()?.let { total ->
            days to total.divide(units, 6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    }.toMap()
}

internal fun legacyStockFromRawCells(rawCells: List<String>): String = rawCells.asSequence()
    .mapNotNull { LEGACY_STOCK.find(it)?.groupValues?.getOrNull(1) }
    .mapNotNull { it.toBrazilianDecimalOrNull() }
    .map { it.stripTrailingZeros().toPlainString() }
    .firstOrNull()
    .orEmpty()

internal fun legacySalesUnitFromRawCells(rawCells: List<String>): String = rawCells.asSequence()
    .mapNotNull { LEGACY_SALES_UNIT.find(it)?.groupValues?.getOrNull(1) }
    .firstOrNull()
    .orEmpty()

private val LEGACY_STOCK = Regex(
    "(?:ESTOQUE|EST\\.?)\\s*:\\s*([0-9]+(?:[.,][0-9]+)*)",
    RegexOption.IGNORE_CASE
)
private val LEGACY_SALES_UNIT = Regex("UNIDADE\\s*:\\s*([A-Z]{1,8})", RegexOption.IGNORE_CASE)

/** Empty/corrupt draft data is not a valid replacement for the last protected collection. */
internal fun <T> nonEmptyDraftOrNull(items: List<T>?): List<T>? = items?.takeIf { it.isNotEmpty() }
