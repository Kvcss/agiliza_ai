package com.agilizaai.cotacao.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.agilizaai.cotacao.R
import com.agilizaai.cotacao.data.AppPreferences

/**
 * Painel do preenchimento do portal.
 *
 * A tela não executa o trabalho: ela acompanha o [PortalFillService] e apenas exibe
 * o WebView que pertence ao serviço. Sair daqui — ou bloquear o celular — não
 * interrompe o preenchimento; ao voltar, a página aparece no ponto em que está.
 *
 * O botão Salvar do portal continua sendo do usuário: o aplicativo nunca o aciona.
 */
class PortalActivity : Activity() {
    private lateinit var preferences: AppPreferences
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var percentLabel: TextView
    private lateinit var progressBar: PortalProgressBar
    private val stepChips = mutableListOf<TextView>()
    private lateinit var webHost: FrameLayout
    private lateinit var primaryAction: Button
    private lateinit var stopAction: Button

    private var service: PortalFillService? = null
    private var bound = false
    private var borrowed: WebView? = null
    private var completionShown = false
    private var contentReady = false
    private var lastProgress = PortalFillService.Progress()

    private val listener: (PortalFillService.Progress) -> Unit = { render(it) }

    /**
     * Redesenha os tempos uma vez por segundo. Entre uma atualização e outra do
     * serviço pode passar bastante tempo — o carregamento da página é o pior caso — e
     * um "há 4s" congelado na tela é justamente o que faz o app parecer travado.
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (lastProgress.running) {
                renderTimings(lastProgress)
                clockHandler.postDelayed(this, TICK_MS)
            }
        }
    }
    private val clockHandler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as? PortalFillService.LocalBinder)?.service ?: return
            service = local
            bound = true
            local.addListener(listener)
            local.openForManualUse()
            attachWebView(local)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        if (PortalUrl.parse(preferences.portalUrl) == null) {
            Toast.makeText(this, "Configure o link do portal antes de abrir", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(buildContent())
        contentReady = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::handleBack
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // `finish()` em onCreate ainda deixa o ciclo passar por aqui. Conectar ao
        // serviço sem a tela montada faria o primeiro progresso desenhar em campos
        // que não existem.
        if (!contentReady) return
        bindService(Intent(this, PortalFillService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        clockHandler.removeCallbacks(ticker)
        // Devolve o WebView para o serviço: o preenchimento continua sem a tela.
        releaseWebView()
        service?.removeListener(listener)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Fechar a tela só encerra o serviço se ele não estiver trabalhando. Enquanto
        // houver preenchimento em andamento, ele continua sozinho em segundo plano.
        if (isFinishing && service?.currentProgress()?.running != true) {
            stopService(Intent(this, PortalFillService::class.java))
        }
        super.onDestroy()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        val view = borrowed
        if (view != null && view.canGoBack()) view.goBack() else finish()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
        }

        root.addView(buildProgressHeader())

        webHost = FrameLayout(this)
        root.addView(webHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        primaryAction = actionButton("PREENCHER AUTOMÁTICO", color(R.color.green)) { start() }
        stopAction = actionButton("PARAR", color(R.color.amber)) { PortalFillService.stop(this) }
        actions.addView(primaryAction, LinearLayout.LayoutParams(0, dp(48), 1.4f).apply { marginEnd = dp(5) })
        actions.addView(stopAction, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(actions)
        root.addView(TextView(this).apply {
            text = "Faça o login e navegue manualmente. O app só preenche preços; Salvar e qualquer confirmação ficam com você."
            setTextColor(color(R.color.muted))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        })
        return root
    }

    /**
     * Cabeçalho de andamento.
     *
     * O preenchimento passa vários segundos abrindo a página, entrando e procurando a
     * tabela antes de escrever o primeiro preço. Sem percentual, contagem e etapa
     * visíveis, essa espera é indistinguível de um travamento — que é exatamente a
     * dúvida que este painel existe para eliminar.
     */
    private fun buildProgressHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(color(R.color.navy))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        titleRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@PortalActivity).apply {
                    text = "Portal de cotação"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                })
                status = TextView(this@PortalActivity).apply {
                    text = "Pronto para preencher · base ${preferences.portalConditionDays} dias"
                    setTextColor(Color.rgb(205, 217, 236))
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                }
                addView(status)
            }
        )
        percentLabel = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }
        titleRow.addView(percentLabel)
        header.addView(titleRow)

        progressBar = PortalProgressBar(this)
        header.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                topMargin = dp(11)
            }
        )

        detail = TextView(this).apply {
            text = "Faça o login manualmente e abra a tabela antes de preencher."
            setTextColor(Color.rgb(170, 190, 218))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        header.addView(detail)

        val stepsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        STEPS.forEachIndexed { position, step ->
            val chip = TextView(this).apply {
                text = step.second
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(5), dp(4), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (position > 0) marginStart = dp(5)
                }
            }
            stepChips += chip
            stepsRow.addView(chip)
        }
        header.addView(stepsRow)
        return header
    }

    private fun start() {
        completionShown = false
        ensureNotificationPermission()
        PortalFillService.start(this)
        // Reconecta caso o serviço ainda não estivesse criado ao abrir a tela.
        if (!bound) {
            bindService(Intent(this, PortalFillService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * A notificação é o que mostra o progresso quando a tela está bloqueada. Sem a
     * permissão o serviço ainda roda, mas o andamento fica invisível.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun attachWebView(local: PortalFillService) {
        if (borrowed != null) return
        val view = local.borrowWebView() ?: return
        borrowed = view
        webHost.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun releaseWebView() {
        val view = borrowed ?: return
        webHost.removeView(view)
        borrowed = null
        service?.returnWebView()
    }

    private fun render(update: PortalFillService.Progress) {
        lastProgress = update
        status.text = update.message.ifBlank { "Pronto para preencher" }

        when {
            update.hasPercent -> {
                percentLabel.visibility = View.VISIBLE
                percentLabel.text = "${update.percent}%"
                progressBar.setProgress(update.processed.toFloat() / update.total)
            }
            update.phase == PortalFillService.Phase.DONE -> {
                percentLabel.visibility = View.VISIBLE
                percentLabel.text = "100%"
                progressBar.setProgress(1f)
            }
            update.running -> {
                percentLabel.visibility = View.GONE
                progressBar.setIndeterminate()
            }
            else -> {
                percentLabel.visibility = View.GONE
                progressBar.setProgress(0f, animate = false)
            }
        }
        renderSteps(update)
        renderTimings(update)

        clockHandler.removeCallbacks(ticker)
        if (update.running) clockHandler.postDelayed(ticker, TICK_MS)

        primaryAction.isEnabled = !update.running
        primaryAction.alpha = if (update.running) 0.5f else 1f
        stopAction.isEnabled = update.running
        stopAction.alpha = if (update.running) 1f else 0.5f
        // Aparece uma única vez por execução, e só depois que o serviço termina.
        if (update.phase == PortalFillService.Phase.DONE && !completionShown) {
            completionShown = true
            attachWebView(service ?: return)
            showSummary(update)
        }
    }

    /** Acende a etapa atual e marca como concluídas as que já passaram. */
    private fun renderSteps(update: PortalFillService.Progress) {
        val current = STEPS.indexOfFirst { it.first == update.phase }
        val done = update.phase == PortalFillService.Phase.DONE
        stepChips.forEachIndexed { position, chip ->
            val reached = done || (current >= 0 && position < current)
            val active = !done && position == current
            chip.setTextColor(
                when {
                    active -> Color.WHITE
                    reached -> Color.rgb(116, 232, 194)
                    else -> Color.rgb(120, 140, 170)
                }
            )
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(
                    when {
                        active -> Color.argb(52, 96, 165, 250)
                        reached -> Color.argb(38, 52, 211, 153)
                        else -> Color.argb(26, 255, 255, 255)
                    }
                )
                if (active) setStroke(dp(1), Color.rgb(96, 165, 250))
            }
        }
    }

    /**
     * Linha de contagem e tempo. É o que responde "quanto falta?" — e, nas fases sem
     * percentual, "há quanto tempo isso está rodando?".
     */
    private fun renderTimings(update: PortalFillService.Progress) {
        val elapsed = PortalProgressText.elapsed(update.elapsedMillis())
        detail.text = when {
            update.phase == PortalFillService.Phase.DONE -> buildString {
                append(PortalProgressText.count(update.filled)).append(" confirmados · ")
                append(PortalProgressText.count(update.skipped)).append(" em branco")
                append(" · revise e salve no portal")
            }
            update.phase == PortalFillService.Phase.FAILED ->
                "Preenchimento interrompido. Você pode tentar de novo sem perder a página."
            update.hasPercent -> buildString {
                append(PortalProgressText.count(update.processed)).append(" de ")
                append(PortalProgressText.count(update.total)).append(" produtos")
                val eta = PortalProgressText.remaining(update.etaSeconds)
                if (eta.isNotBlank()) append(" · ").append(eta)
                append("\n").append(PortalProgressText.count(update.filled))
                append(" confirmados pelo portal · continua com a tela bloqueada")
            }
            update.running -> buildString {
                append(
                    when (update.phase) {
                        PortalFillService.Phase.LOADING -> "Abrindo a página do portal"
                        else -> "Procurando a tabela da cotação"
                    }
                )
                if (elapsed.isNotBlank()) append(" · ").append(elapsed)
                append("\nPode bloquear a tela — o trabalho continua.")
            }
            else -> "O preenchimento continua mesmo com a tela bloqueada."
        }
    }

    private fun showSummary(update: PortalFillService.Progress) {
        val signals = update.bySignal
            .filterValues { it > 0 }
            .entries
            .joinToString("\n") { "• ${it.key}: ${it.value}" }
            .ifBlank { "• nenhum casamento registrado" }
        AlertDialog.Builder(this)
            .setTitle("Preenchimento concluído")
            .setMessage(
                "${update.filled} campos de Custo foram confirmados e ${update.skipped} ficaram em branco.\n\n" +
                    "Como cada produto foi encontrado:\n$signals\n\n" +
                    "Campos verdes foram aceitos pelo portal; laranja foram recusados. " +
                    "Confira os valores e toque em Salvar no portal — o aplicativo não envia por você."
            )
            .setPositiveButton("Revisar", null)
            .show()
    }

    private fun actionButton(label: String, background: Int, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundColor(background)
        setOnClickListener { click() }
    }

    private fun color(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 9301
        const val TICK_MS = 1_000L

        /** Etapas mostradas em sequência, na ordem em que o serviço passa por elas. */
        val STEPS = listOf(
            PortalFillService.Phase.LOADING to "ABRIR",
            PortalFillService.Phase.LOCATING to "LOCALIZAR",
            PortalFillService.Phase.FILLING to "PREENCHER"
        )
    }
}
