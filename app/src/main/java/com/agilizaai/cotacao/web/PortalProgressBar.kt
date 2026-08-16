package com.agilizaai.cotacao.web

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Barra de progresso do preenchimento do portal.
 *
 * Existe por um motivo específico: entre abrir a página, entrar e localizar a tabela,
 * o aplicativo passa vários segundos sem nada visível acontecendo, e uma tela parada
 * parece travada. A barra nunca fica imóvel — quando ainda não há percentual, ela
 * roda um segmento deslizante; quando há, avança animada até o valor novo.
 */
class PortalProgressBar(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    private var shownFraction = 0f
    private var indeterminate = false
    private var sweep = 0f

    private var progressAnimator: ValueAnimator? = null
    private var sweepAnimator: ValueAnimator? = null

    init {
        trackPaint.color = TRACK_COLOR
        fillPaint.color = START_COLOR
    }

    /**
     * @param fraction 0..1 do que já foi preenchido.
     * @param animate falso ao restaurar o estado, para a barra não vir do zero.
     */
    fun setProgress(fraction: Float, animate: Boolean = true) {
        val target = fraction.coerceIn(0f, 1f)
        stopIndeterminate()
        if (indeterminate) {
            indeterminate = false
            // Sair do modo indeterminado sem salto: começa do valor já mostrado.
            shownFraction = shownFraction.coerceAtMost(target)
        }
        progressAnimator?.cancel()
        if (!animate || target <= shownFraction) {
            shownFraction = target
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(shownFraction, target).apply {
            duration = PROGRESS_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Trabalho em andamento sem percentual conhecido: carregar, entrar, localizar. */
    fun setIndeterminate() {
        if (indeterminate) return
        progressAnimator?.cancel()
        indeterminate = true
        shownFraction = 0f
        startIndeterminate()
    }

    private fun startIndeterminate() {
        if (sweepAnimator != null || !isAttachedToWindow) return
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_ANIMATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopIndeterminate() {
        sweepAnimator?.cancel()
        sweepAnimator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (indeterminate) startIndeterminate()
    }

    override fun onDetachedFromWindow() {
        // Um animador infinito preso a uma View fora da tela segura a Activity inteira.
        stopIndeterminate()
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(START_COLOR, END_COLOR),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val height = height.toFloat()
        val width = width.toFloat()
        if (height <= 0f || width <= 0f) return
        val radius = height / 2f

        bounds.set(0f, 0f, width, height)
        canvas.drawRoundRect(bounds, radius, radius, trackPaint)

        if (indeterminate) {
            val segment = width * SWEEP_WIDTH
            // O segmento entra e sai pelas bordas, sem encolher nem parar nos extremos.
            val start = -segment + sweep * (width + segment)
            bounds.set(start, 0f, start + segment, height)
            canvas.save()
            canvas.clipRect(0f, 0f, width, height)
            canvas.drawRoundRect(bounds, radius, radius, fillPaint)
            canvas.restore()
            return
        }

        if (shownFraction <= 0f) return
        // Abaixo de um traço a barra viraria um ponto achatado: o mínimo é a bolinha.
        val filled = maxOf(width * shownFraction, height)
        bounds.set(0f, 0f, filled, height)
        canvas.drawRoundRect(bounds, radius, radius, fillPaint)
    }

    private companion object {
        const val PROGRESS_ANIMATION_MS = 420L
        const val SWEEP_ANIMATION_MS = 1_150L
        const val SWEEP_WIDTH = 0.32f
        val TRACK_COLOR = Color.argb(48, 255, 255, 255)
        val START_COLOR = Color.rgb(52, 211, 153)
        val END_COLOR = Color.rgb(96, 165, 250)
    }
}
