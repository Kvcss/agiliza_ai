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
            return read(draftFile, atomicDraftFile)
                ?: read(dataFile, atomicFile)
                ?: emptyList()
        }
    }

    fun hasDraft(): Boolean = synchronized(PROCESS_LOCK) { draftFile.exists() }

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
            write(atomicDraftFile, items)
        }
    }

    fun replace(items: List<QuoteItem>) {
        synchronized(PROCESS_LOCK) {
            write(atomicFile, items)
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
            }
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
        put("pricesByDays", JSONObject().apply {
            pricesByDays.toSortedMap().forEach { (days, price) -> put(days.toString(), price) }
        })
        put("rawCells", JSONArray(rawCells))
    }

    private fun JSONObject.toQuoteItem(): QuoteItem {
        val cells = optJSONArray("rawCells") ?: JSONArray()
        val legacyDays = optInt("conditionDays", 7)
        val legacyPrice = optString("unitPrice")
        val priceObject = optJSONObject("pricesByDays")
        val prices = linkedMapOf<Int, String>()
        if (priceObject != null) {
            priceObject.keys().forEach { key ->
                key.toIntOrNull()?.let { days ->
                    priceObject.optString(key).takeIf { it.isNotBlank() }?.let { prices[days] = it }
                }
            }
        }
        if (prices.isEmpty() && legacyPrice.isNotBlank()) prices[legacyDays] = legacyPrice
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
            unitPrice = legacyPrice,
            rawCells = buildList {
                for (index in 0 until cells.length()) add(cells.optString(index))
            },
            pricesByDays = prices,
            unitsPerCase = optString("unitsPerCase", optString("quantity"))
        )
    }

    companion object {
        private val PROCESS_LOCK = Any()
    }
}
