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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private lateinit var progressBar: ProgressBar
    private lateinit var webHost: FrameLayout
    private lateinit var primaryAction: Button
    private lateinit var stopAction: Button

    private var service: PortalFillService? = null
    private var bound = false
    private var borrowed: WebView? = null
    private var completionShown = false

    private val listener: (PortalFillService.Progress) -> Unit = { render(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as? PortalFillService.LocalBinder)?.service ?: return
            service = local
            bound = true
            local.addListener(listener)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::handleBack
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PortalFillService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
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

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            setBackgroundColor(color(R.color.navy))
        }
        header.addView(TextView(this).apply {
            text = "Portal de cotação"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        status = TextView(this).apply {
            text = "Pronto para preencher · base ${preferences.portalConditionDays} dias"
            setTextColor(Color.rgb(205, 217, 236))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(7))
        }
        header.addView(status)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        header.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        detail = TextView(this).apply {
            text = "O preenchimento continua mesmo com a tela bloqueada."
            setTextColor(Color.rgb(160, 180, 210))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
        header.addView(detail)
        root.addView(header)

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
            text = "Revise os valores e toque em Salvar no próprio portal. O aplicativo nunca envia a cotação por você."
            setTextColor(color(R.color.muted))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        })
        return root
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
        status.text = update.message.ifBlank { "Pronto para preencher" }
        progressBar.isIndeterminate = update.running && update.total <= 0
        if (update.total > 0) {
            progressBar.isIndeterminate = false
            progressBar.max = update.total
            progressBar.progress = update.processed
        }
        detail.text = when {
            update.phase == PortalFillService.Phase.DONE ->
                "${update.filled} confirmados · ${update.skipped} em branco · revise e salve no portal"
            update.running ->
                "${update.percent}% · ${update.filled} preenchidos · continua com a tela bloqueada"
            else -> "O preenchimento continua mesmo com a tela bloqueada."
        }
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
    }
}
