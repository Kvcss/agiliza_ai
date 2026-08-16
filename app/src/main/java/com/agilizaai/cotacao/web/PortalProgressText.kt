package com.agilizaai.cotacao.web

/**
 * Textos de tempo do preenchimento do portal.
 *
 * Uma estimativa medida em segundos não deve ser anunciada como se fosse exata: o
 * ritmo do portal varia com o tamanho da página. Os textos abaixo arredondam de
 * propósito e dizem "cerca de", para a tela informar sem prometer.
 */
internal object PortalProgressText {

    /** Tempo restante estimado. Vazio quando ainda não há medida confiável. */
    fun remaining(seconds: Int): String = when {
        seconds < 0 -> ""
        seconds <= 10 -> "quase terminando"
        // Acima de 55 s o arredondamento para dezenas já daria "60 s": daí em diante
        // a contagem passa a ser em minutos.
        seconds < 55 -> "cerca de ${roundToStep(seconds, 10)} s restantes"
        seconds < 3_600 -> {
            val minutes = roundToStep(seconds, 60) / 60
            when {
                minutes <= 1 -> "cerca de 1 min restante"
                // 59min30s arredonda para 60 minutos, que se diz "1 h".
                minutes >= 60 -> "cerca de 1 h restante"
                else -> "cerca de $minutes min restantes"
            }
        }
        else -> {
            // Arredondar o total, e não o resto, evita que 1h59min59s vire "1h 60min".
            val totalMinutes = roundToStep(seconds, 60) / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            when {
                minutes > 0 -> "cerca de ${hours}h ${minutes}min restantes"
                hours == 1 -> "cerca de 1 h restante"
                else -> "cerca de $hours h restantes"
            }
        }
    }

    /** Tempo já decorrido, para que uma espera longa não pareça travamento. */
    fun elapsed(millis: Long): String {
        if (millis <= 0L) return ""
        val seconds = millis / 1_000L
        return when {
            seconds < 60L -> "há ${seconds}s"
            seconds < 3_600L -> "há ${seconds / 60L}min ${seconds % 60L}s"
            else -> "há ${seconds / 3_600L}h ${(seconds % 3_600L) / 60L}min"
        }
    }

    /** Milhares separados por ponto, como todo o resto da interface. */
    fun count(value: Int): String {
        val digits = value.coerceAtLeast(0).toString()
        return digits.reversed().chunked(3).joinToString(".").reversed()
    }

    private fun roundToStep(value: Int, step: Int): Int = (value + step / 2) / step * step
}
