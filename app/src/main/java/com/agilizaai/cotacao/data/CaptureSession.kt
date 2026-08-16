package com.agilizaai.cotacao.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * Estado da coleta em andamento no ION.
 *
 * Cada tabela leva horas para ser lida. Quando o aplicativo é fechado no meio do
 * caminho, este registro é o que permite continuar do mesmo prazo e do mesmo
 * ponto da lista em vez de recomeçar tudo pelos 7 dias.
 */
data class CaptureSession(
    /** Prazos planejados para esta coleta, na ordem em que serão lidos. */
    val conditions: List<Int>,
    /** Índice do prazo que está sendo lido agora. */
    val conditionIndex: Int = 0,
    /** Produtos já visitados no prazo atual, guardados como token curto. */
    val visitedKeys: Set<String> = emptySet(),
    /** Quantos produtos foram percorridos em cada prazo já concluído. */
    val conditionCounts: Map<Int, Int> = emptyMap(),
    val skippedConjunto: Int = 0,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val currentDays: Int
        get() = conditions.getOrNull(conditionIndex) ?: conditions.lastOrNull() ?: DEFAULT_DAYS

    /** Uma sessão só pode ser retomada enquanto ainda restar prazo por ler. */
    val isResumable: Boolean
        get() = conditions.isNotEmpty() && conditionIndex in conditions.indices

    /**
     * Quantos produtos já foram percorridos no prazo atual. Uma sessão deduzida da
     * base não tem a lista de visitados, mas sabe o total pelo próprio contador.
     */
    val readInCurrentCondition: Int
        get() = maxOf(visitedKeys.size, conditionCounts[currentDays] ?: 0)

    val remainingConditions: List<Int>
        get() = if (isResumable) conditions.drop(conditionIndex) else emptyList()

    /**
     * Formato de linhas em vez de JSON: o conjunto de visitados chega a milhares de
     * tokens e é regravado a cada lote de produtos. Serializar isso como texto puro
     * mantém a gravação barata e o arquivo legível.
     */
    fun encode(): String = buildString {
        append(VERSION_LINE).append('\n')
        append("conditions=").append(conditions.joinToString(",")).append('\n')
        append("index=").append(conditionIndex).append('\n')
        append("skipped=").append(skippedConjunto).append('\n')
        append("startedAt=").append(startedAt).append('\n')
        append("updatedAt=").append(updatedAt).append('\n')
        append("counts=").append(
            conditionCounts.entries.joinToString(",") { "${it.key}:${it.value}" }
        ).append('\n')
        append(KEYS_LINE).append('\n')
        visitedKeys.forEach { append(it).append('\n') }
    }

    companion object {
        const val DEFAULT_DAYS = 7
        private const val VERSION_LINE = "agiliza-capture-session/1"
        private const val KEYS_LINE = "keys"

        fun decode(payload: String): CaptureSession? {
            val lines = payload.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != VERSION_LINE) return null
            var conditions = emptyList<Int>()
            var index = 0
            var skipped = 0
            var startedAt = 0L
            var updatedAt = 0L
            var counts = emptyMap<Int, Int>()
            val keys = linkedSetOf<String>()
            var readingKeys = false
            while (lines.hasNext()) {
                val line = lines.next().trim()
                if (line.isEmpty()) continue
                if (readingKeys) {
                    keys += line
                    continue
                }
                if (line == KEYS_LINE) {
                    readingKeys = true
                    continue
                }
                val separator = line.indexOf('=')
                if (separator <= 0) continue
                val field = line.substring(0, separator)
                val value = line.substring(separator + 1)
                when (field) {
                    "conditions" -> conditions = value.split(',').mapNotNull { it.trim().toIntOrNull() }
                    "index" -> index = value.toIntOrNull() ?: 0
                    "skipped" -> skipped = value.toIntOrNull() ?: 0
                    "startedAt" -> startedAt = value.toLongOrNull() ?: 0L
                    "updatedAt" -> updatedAt = value.toLongOrNull() ?: 0L
                    "counts" -> counts = value.split(',').mapNotNull { entry ->
                        val parts = entry.split(':')
                        val days = parts.getOrNull(0)?.trim()?.toIntOrNull()
                        val total = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (days != null && total != null) days to total else null
                    }.toMap()
                }
            }
            if (conditions.isEmpty()) return null
            return CaptureSession(
                conditions = conditions,
                conditionIndex = index.coerceIn(0, conditions.size),
                visitedKeys = keys,
                conditionCounts = counts,
                skippedConjunto = skipped,
                startedAt = startedAt,
                updatedAt = updatedAt
            )
        }
    }
}

/**
 * Deduz um ponto de retomada olhando só para a base já salva.
 *
 * É a rede de segurança para uma coleta que foi interrompida antes de existir
 * arquivo de sessão — ou cujo arquivo se perdeu. Um prazo com menos produtos
 * precificados que o prazo mais completo é, necessariamente, um prazo que parou no
 * meio.
 */
object CaptureRecovery {

    fun deduce(
        pricedByDays: Map<Int, Int>,
        conditions: List<Int>,
        updatedAt: Long = 0L
    ): CaptureSession? {
        if (conditions.isEmpty()) return null
        val counts = conditions.associateWith { pricedByDays[it] ?: 0 }
        val best = counts.values.maxOrNull() ?: 0
        // Sem nenhum preço salvo não existe coleta anterior para continuar.
        if (best == 0) return null
        val incomplete = conditions.indexOfFirst { counts.getValue(it) < best }
        // Todos os prazos igualmente cobertos: a coleta chegou ao fim.
        if (incomplete < 0) return null
        return CaptureSession(
            conditions = conditions,
            conditionIndex = incomplete,
            conditionCounts = counts,
            updatedAt = updatedAt
        )
    }
}

/** Guarda a sessão em disco para que ela sobreviva ao fechamento do aplicativo. */
class CaptureSessionStore(context: Context) {
    private val file = File(context.filesDir, "capture_session.txt")
    private val atomicFile = AtomicFile(file)

    fun load(): CaptureSession? {
        synchronized(LOCK) {
            if (!file.exists()) return null
            return runCatching {
                val payload = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                CaptureSession.decode(payload)
            }.getOrNull()
        }
    }

    /** Sessão pendente de retomada, ou `null` quando não há coleta interrompida. */
    fun resumable(): CaptureSession? = load()?.takeIf { it.isResumable }

    fun save(session: CaptureSession) {
        synchronized(LOCK) {
            val stream = atomicFile.startWrite()
            try {
                stream.writer(Charsets.UTF_8).apply {
                    write(session.encode())
                    flush()
                }
                atomicFile.finishWrite(stream)
            } catch (error: Throwable) {
                atomicFile.failWrite(stream)
                throw error
            }
        }
    }

    fun clear() {
        synchronized(LOCK) { atomicFile.delete() }
    }

    private companion object {
        val LOCK = Any()
    }
}
