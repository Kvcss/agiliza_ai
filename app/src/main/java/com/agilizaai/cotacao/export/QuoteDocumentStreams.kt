package com.agilizaai.cotacao.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Safely coordinates a document that may be selected as both input and output.
 *
 * The source is completely snapshotted and closed before the destination is
 * opened with truncation. This prevents a DocumentsProvider from leaving the
 * previous XLSX central directory at the end of a shorter generated file.
 */
internal object QuoteDocumentStreams {
    const val TRUNCATING_OUTPUT_MODE = "rwt"

    fun <T> withSnapshot(
        openInput: () -> InputStream?,
        openOutput: (mode: String) -> OutputStream?,
        action: (snapshot: InputStream, output: OutputStream) -> T
    ): T {
        val sourceBytes = openInput()?.use { input -> input.readBytes() }
            ?: error("Não foi possível abrir o modelo")

        val generated = ByteArrayOutputStream()
        val result = ByteArrayInputStream(sourceBytes).use { snapshot ->
            generated.use { stagedOutput -> action(snapshot, stagedOutput) }
        }
        val generatedBytes = generated.toByteArray()

        openOutput(TRUNCATING_OUTPUT_MODE)?.use { output ->
            output.write(generatedBytes)
            output.flush()
        } ?: error("Não foi possível criar o arquivo de saída")
        return result
    }
}
