package com.agilizaai.cotacao.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream

class QuoteDocumentStreamsTest {
    @Test
    fun snapshotsAndClosesInputBeforeTruncatingTheSameDestination() {
        val file = kotlin.io.path.createTempFile(prefix = "agiliza-same-document-", suffix = ".xlsx").toFile()
        val original = "conteúdo original que é maior que a nova saída".toByteArray()
        file.writeBytes(original)
        val events = mutableListOf<String>()

        try {
            val result = QuoteDocumentStreams.withSnapshot(
                openInput = {
                    events += "input-open"
                    object : FilterInputStream(FileInputStream(file)) {
                        override fun close() {
                            events += "input-close"
                            super.close()
                        }
                    }
                },
                openOutput = { mode ->
                    events += "output-open:$mode"
                    FileOutputStream(file, false)
                }
            ) { snapshot, output ->
                assertTrue(snapshot is ByteArrayInputStream)
                assertTrue(snapshot.readBytes().contentEquals(original))
                output.write("nova".toByteArray())
                "ok"
            }

            assertEquals("ok", result)
            assertEquals(
                listOf("input-open", "input-close", "output-open:rwt"),
                events
            )
            assertEquals("nova", file.readText())
            assertEquals(4L, file.length())
        } finally {
            file.delete()
        }
    }

    @Test
    fun reportsInputAndOutputOpenFailuresPrecisely() {
        val inputFailure = runCatching {
            QuoteDocumentStreams.withSnapshot(
                openInput = { null },
                openOutput = { error("não deveria abrir") }
            ) { _, _ -> Unit }
        }.exceptionOrNull()
        assertTrue(inputFailure?.message.orEmpty().contains("abrir o modelo"))

        val outputFailure = runCatching {
            QuoteDocumentStreams.withSnapshot(
                openInput = { ByteArrayInputStream(byteArrayOf(1)) },
                openOutput = { null }
            ) { _, _ -> Unit }
        }.exceptionOrNull()
        assertTrue(outputFailure?.message.orEmpty().contains("criar o arquivo de saída"))
    }

    @Test
    fun generatorFailureDoesNotOpenOrTruncateTheDestination() {
        val file = kotlin.io.path.createTempFile(prefix = "agiliza-generator-failure-", suffix = ".xlsx").toFile()
        val original = "arquivo anterior protegido".toByteArray()
        file.writeBytes(original)
        var outputWasOpened = false

        try {
            val failure = runCatching {
                QuoteDocumentStreams.withSnapshot(
                    openInput = { ByteArrayInputStream(original) },
                    openOutput = {
                        outputWasOpened = true
                        FileOutputStream(file, false)
                    }
                ) { _, stagedOutput ->
                    stagedOutput.write("parcial".toByteArray())
                    error("modelo inválido")
                }
            }.exceptionOrNull()

            assertEquals("modelo inválido", failure?.message)
            assertTrue(!outputWasOpened)
            assertTrue(file.readBytes().contentEquals(original))
        } finally {
            file.delete()
        }
    }
}
