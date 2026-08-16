package com.agilizaai.cotacao.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.agilizaai.cotacao.MainActivity
import com.agilizaai.cotacao.R
import com.agilizaai.cotacao.data.AppPreferences
import com.agilizaai.cotacao.data.QuoteRepository
import com.agilizaai.cotacao.security.SecureCredentialStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executa o preenchimento do portal fora do ciclo de vida da tela.
 *
 * O WebView pertence ao serviço, não à `Activity`: quando o celular bloqueia ou o
 * usuário sai do aplicativo, o processo continua com um `WAKE_LOCK` parcial e uma
 * notificação de progresso. A tela apenas “empresta” o WebView para exibir a página
 * e devolve ao sair, sem interromper o trabalho.
 *
 * O serviço nunca aciona o botão Salvar do portal: o envio permanece do usuário.
 */
class PortalFillService : Service() {

    /** Fase atual, usada pela tela e pela notificação. */
    enum class Phase { IDLE, LOADING, LOGIN, LOCATING, FILLING, DONE, FAILED }

    data class Progress(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val processed: Int = 0,
        val total: Int = 0,
        val filled: Int = 0,
        val skipped: Int = 0,
        val bySignal: Map<String, Int> = emptyMap(),
        /** Início da execução, para a tela mostrar há quanto tempo ela trabalha. */
        val startedAt: Long = 0L,
        /** Segundos restantes estimados, ou -1 enquanto não houver medida confiável. */
        val etaSeconds: Int = -1
    ) {
        val percent: Int get() = if (total <= 0) 0 else (processed * 100 / total).coerceIn(0, 100)
        val running: Boolean get() = phase == Phase.LOADING || phase == Phase.LOGIN ||
            phase == Phase.LOCATING || phase == Phase.FILLING

        /** Só o preenchimento tem percentual real; as outras fases são de espera. */
        val hasPercent: Boolean get() = phase == Phase.FILLING && total > 0

        fun elapsedMillis(now: Long = System.currentTimeMillis()): Long =
            if (startedAt <= 0L) 0L else (now - startedAt).coerceAtLeast(0L)
    }

    inner class LocalBinder : Binder() {
        val service: PortalFillService get() = this@PortalFillService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: AppPreferences
    private lateinit var credentials: SecureCredentialStore
    private var wakeLock: PowerManager.WakeLock? = null

    private var webView: WebView? = null
    private var allowedScheme = "https"
    private var allowedHost = ""
    private var allowedPort = 443
    private var loginAttempted = false
    private var fillStarted = false
    private var locateAttempts = 0
    private var batchStart = 0
    private var stopping = false
    private var runStartedAt = 0L
    private var fillStartedAt = 0L

    private var progress = Progress()
    private val listeners = mutableSetOf<(Progress) -> Unit>()

    private var resolutions: List<PortalMatcher.Resolution> = emptyList()
    private var indexReport: PortalMatcher.Report? = null
    private var usableBaseItems = 0
    private var portalIndex: PortalMatcher.Index? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        credentials = SecureCredentialStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFilling("Preenchimento cancelado")
                return START_NOT_STICKY
            }
            ACTION_START -> startFilling()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        releaseWakeLock()
        detachWebView()
        webView?.let { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- estado

    fun currentProgress(): Progress = progress

    fun addListener(listener: (Progress) -> Unit) {
        listeners += listener
        listener(progress)
    }

    fun removeListener(listener: (Progress) -> Unit) {
        listeners -= listener
    }

    private fun publish(update: Progress) {
        progress = update
        listeners.toList().forEach { it(update) }
        updateNotification(update)
    }

    /**
     * A tela pega o WebView emprestado para o usuário revisar e salvar. Ele continua
     * pertencendo ao serviço, então sair da tela não cancela o preenchimento.
     */
    fun borrowWebView(): WebView? {
        val view = webView ?: return null
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun returnWebView() {
        detachWebView()
    }

    private fun detachWebView() {
        val view = webView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        // Sem uma janela, o WebView precisa de tamanho explícito para que
        // getBoundingClientRect() continue devolvendo valores reais.
        layoutOffscreen(view)
    }

    private fun layoutOffscreen(view: WebView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(OFFSCREEN_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(OFFSCREEN_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, OFFSCREEN_WIDTH, OFFSCREEN_HEIGHT)
    }

    // ------------------------------------------------------------- execução

    private fun startFilling() {
        if (progress.running) return
        // `startForegroundService` exige `startForeground` em poucos segundos, mesmo
        // quando a validação abaixo falha. Entrar em primeiro plano antes de qualquer
        // saída antecipada evita o encerramento forçado pelo sistema.
        startInForeground()

        val uri = PortalUrl.parse(preferences.portalUrl)
        if (uri == null) {
            failBeforeStart("Configure o link do portal antes de preencher")
            return
        }
        allowedScheme = uri.scheme.lowercase()
        allowedHost = uri.host.lowercase()
        allowedPort = PortalUrl.portOf(uri)

        val days = preferences.portalConditionDays
        val index = PortalMatcher.buildIndex(QuoteRepository(this).load(), days)
        if (index.isEmpty) {
            failBeforeStart("Nenhum preço de $days dias na base capturada")
            return
        }
        usableBaseItems = index.usableItems
        portalIndex = index

        loginAttempted = false
        fillStarted = false
        locateAttempts = 0
        batchStart = 0
        stopping = false
        runStartedAt = System.currentTimeMillis()
        fillStartedAt = 0L
        resolutions = emptyList()
        indexReport = null

        acquireWakeLock()
        val view = ensureWebView()
        // Se o usuário já entrou e navegou até a cotação, recarregar o link de login
        // jogaria fora exatamente a página que precisa ser preenchida.
        val current = view.url.orEmpty()
        val alreadyOnPortal = current.isNotBlank() &&
            current != "about:blank" &&
            isAllowed(Uri.parse(current))
        if (alreadyOnPortal) {
            publish(
                Progress(
                    phase = Phase.LOCATING,
                    message = "Procurando a cotação na página aberta · $usableBaseItems preços de $days dias prontos…",
                    startedAt = runStartedAt
                )
            )
            handler.postDelayed(::probe, PROBE_DELAY_MS)
        } else {
            publish(
                Progress(
                    phase = Phase.LOADING,
                    message = "Abrindo $allowedHost · $usableBaseItems preços de $days dias prontos…",
                    startedAt = runStartedAt
                )
            )
            view.loadUrl(preferences.portalUrl)
        }
    }

    /** Falha antes de começar: sai do primeiro plano sem deixar notificação presa. */
    private fun failBeforeStart(message: String) {
        publish(Progress(Phase.FAILED, message))
        stopForegroundKeepingNotification()
    }

    private fun stopFilling(message: String) {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        webView?.stopLoading()
        releaseWakeLock()
        publish(progress.copy(phase = Phase.FAILED, message = message))
        stopForegroundKeepingNotification()
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        // Contexto de aplicação: o WebView sobrevive à Activity que o exibe.
        val view = WebView(applicationContext)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        // Mantém o conteúdo rasterizado mesmo sem estar visível na tela.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) view.settings.offscreenPreRaster = true
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                return !isAllowed(url)
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                if (stopping || !isAllowed(Uri.parse(url ?: ""))) return
                handler.postDelayed(::probe, PROBE_DELAY_MS)
            }

            override fun onSafeBrowsingHit(
                v: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: SafeBrowsingResponse?
            ) {
                callback?.backToSafety(true)
                stopFilling("Página bloqueada pela Navegação Segura")
            }
        }
        layoutOffscreen(view)
        view.resumeTimers()
        webView = view
        return view
    }

    /**
     * A navegação fica presa à origem cadastrada — esquema, host e porta. É isso que
     * impede o WebView de sair do portal da distribuidora enquanto trabalha sozinho.
     */
    private fun isAllowed(uri: Uri): Boolean {
        val port = if (uri.port == -1) PortalUrl.defaultPort(uri.scheme) else uri.port
        return uri.scheme.equals(allowedScheme, ignoreCase = true) &&
            uri.host.equals(allowedHost, ignoreCase = true) &&
            port == allowedPort &&
            uri.userInfo.isNullOrBlank()
    }

    /** Descobre se a página atual é o login ou já é a tabela da cotação. */
    private fun probe() {
        if (stopping) return
        evaluate(PortalScripts.probe(preferences)) { result ->
            if (stopping) return@evaluate
            when {
                result.optBoolean("loginReady") && !loginAttempted -> {
                    loginAttempted = true
                    publish(progress.copy(phase = Phase.LOGIN, message = "Entrando no portal…"))
                    performLogin()
                }
                result.optBoolean("tableReady") -> {
                    if (!fillStarted) {
                        fillStarted = true
                        prepareFill()
                    }
                }
                locateAttempts < MAX_LOCATE_ATTEMPTS -> {
                    locateAttempts++
                    publish(
                        progress.copy(
                            phase = Phase.LOCATING,
                            message = "Procurando a tabela da cotação… ($locateAttempts)"
                        )
                    )
                    handler.postDelayed(::probe, LOCATE_RETRY_MS)
                }
                else -> {
                    releaseWakeLock()
                    publish(
                        progress.copy(
                            phase = Phase.FAILED,
                            message = "Não encontrei a tabela com as colunas Código Barra e Custo"
                        )
                    )
                    stopForegroundKeepingNotification()
                }
            }
        }
    }

    private fun performLogin() {
        val email = credentials.email()
        val password = credentials.password()
        val expectedOrigin = PortalUrl.origin(allowedScheme, allowedHost, allowedPort)
        if (credentials.origin() != expectedOrigin || email.isBlank() || password.isBlank()) {
            releaseWakeLock()
            publish(progress.copy(phase = Phase.FAILED, message = "Cadastre o código e a senha do portal"))
            stopForegroundKeepingNotification()
            return
        }
        evaluate(PortalScripts.login(preferences, email, password)) { result ->
            if (stopping) return@evaluate
            if (!result.optBoolean("ok")) {
                releaseWakeLock()
                publish(
                    progress.copy(
                        phase = Phase.FAILED,
                        message = result.optString("message", "Não consegui preencher o login")
                    )
                )
                stopForegroundKeepingNotification()
                return@evaluate
            }
            handler.postDelayed(::probe, LOGIN_SETTLE_MS)
        }
    }

    /** Lê todas as linhas do portal e resolve os preços antes de escrever qualquer campo. */
    private fun prepareFill() {
        publish(progress.copy(phase = Phase.LOCATING, message = "Lendo os produtos da página…"))
        evaluate(PortalScripts.readRows()) { result ->
            if (stopping) return@evaluate
            val rowsJson = result.optJSONArray("rows") ?: JSONArray()
            val rows = buildList {
                for (position in 0 until rowsJson.length()) {
                    val row = rowsJson.optJSONObject(position) ?: continue
                    add(
                        PortalMatcher.Row(
                            index = row.optInt("index", position),
                            code = row.optString("code"),
                            barcode = row.optString("barcode"),
                            description = row.optString("description")
                        )
                    )
                }
            }
            if (rows.isEmpty()) {
                releaseWakeLock()
                publish(progress.copy(phase = Phase.FAILED, message = "A tabela do portal veio sem linhas"))
                stopForegroundKeepingNotification()
                return@evaluate
            }
            val index = portalIndex ?: return@evaluate
            val report = PortalMatcher.resolve(index, rows)
            indexReport = report
            resolutions = report.resolutions
            batchStart = 0
            fillStartedAt = System.currentTimeMillis()
            publish(
                Progress(
                    phase = Phase.FILLING,
                    message = "Preenchendo ${report.matched} de ${rows.size} produtos…",
                    processed = 0,
                    total = report.matched,
                    filled = 0,
                    skipped = rows.size - report.matched,
                    startedAt = runStartedAt
                )
            )
            fillNextBatch()
        }
    }

    private fun fillNextBatch() {
        if (stopping) return
        if (batchStart >= resolutions.size) {
            finish()
            return
        }
        val batch = resolutions.subList(batchStart, minOf(batchStart + BATCH_SIZE, resolutions.size))
        evaluate(PortalScripts.fillBatch(batch)) { result ->
            if (stopping) return@evaluate
            val confirmed = result.optInt("confirmed")
            val rejected = result.optInt("rejected")
            batchStart += batch.size
            publish(
                progress.copy(
                    phase = Phase.FILLING,
                    processed = batchStart,
                    filled = progress.filled + confirmed,
                    skipped = progress.skipped + rejected,
                    message = "Preenchendo… $batchStart de ${resolutions.size}",
                    etaSeconds = estimateRemainingSeconds(batchStart, resolutions.size)
                )
            )
            handler.postDelayed(::fillNextBatch, BATCH_PAUSE_MS)
        }
    }

    /**
     * Estimativa do tempo restante a partir do ritmo real medido nesta execução.
     *
     * A velocidade depende do tamanho da página e do quanto o próprio portal recalcula
     * a cada preço escrito, então não há constante que sirva: a média do que já foi
     * feito é a única medida honesta. Enquanto a amostra for pequena demais para
     * significar alguma coisa, o retorno é -1 e a tela não promete prazo nenhum.
     */
    private fun estimateRemainingSeconds(processed: Int, total: Int): Int {
        if (fillStartedAt <= 0L || processed <= 0 || processed >= total) return -1
        val elapsed = System.currentTimeMillis() - fillStartedAt
        if (elapsed < MIN_ELAPSED_FOR_ETA_MS) return -1
        val remaining = (total - processed).toDouble() * elapsed / processed
        return (remaining / 1000.0).toInt().coerceIn(1, MAX_ETA_SECONDS)
    }

    private fun finish() {
        releaseWakeLock()
        val report = indexReport
        val signals = report?.let {
            mapOf(
                "EAN" to it.signalCount(PortalMatcher.Signal.BARCODE),
                "EAN parcial" to it.signalCount(PortalMatcher.Signal.BARCODE_PREFIX),
                "Código" to it.signalCount(PortalMatcher.Signal.CODE),
                "Nome" to it.signalCount(PortalMatcher.Signal.NAME)
            )
        }.orEmpty()
        publish(
            progress.copy(
                phase = Phase.DONE,
                processed = resolutions.size,
                total = resolutions.size,
                bySignal = signals,
                message = "${progress.filled} preços preenchidos · revise e toque em Salvar no portal"
            )
        )
        stopForegroundKeepingNotification()
    }

    // ------------------------------------------------------------ auxiliares

    private fun evaluate(script: String, callback: (JSONObject) -> Unit) {
        val view = webView ?: return
        view.evaluateJavascript(script) { encoded ->
            val parsed = runCatching {
                JSONObject(JSONArray("[$encoded]").optString(0))
            }.getOrElse { JSONObject().put("ok", false).put("message", "Resposta inesperada da página") }
            callback(parsed)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun startInForeground() {
        val notification = buildNotification(Progress(Phase.LOADING, "Preparando o portal…"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundKeepingNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Preenchimento do portal",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mostra o andamento do preenchimento automático de preços."
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun updateNotification(update: Progress) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(update)) }
    }

    /**
     * Com a tela bloqueada, a notificação é a única indicação de que o app continua
     * trabalhando: ela carrega o mesmo percentual e a mesma estimativa da tela.
     */
    private fun notificationText(update: Progress): String {
        if (!update.hasPercent) return update.message.ifBlank { "Trabalhando…" }
        val eta = PortalProgressText.remaining(update.etaSeconds)
        return buildString {
            append(update.percent).append("% · ")
            append(PortalProgressText.count(update.processed)).append(" de ")
            append(PortalProgressText.count(update.total))
            if (eta.isNotBlank()) append(" · ").append(eta)
        }
    }

    private fun buildNotification(update: Progress): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PortalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle(
            when (update.phase) {
                Phase.DONE -> "Portal preenchido"
                Phase.FAILED -> "Preenchimento interrompido"
                else -> "Preenchendo o portal"
            }
        )
            .setContentText(notificationText(update))
            .setSmallIcon(R.drawable.ic_app)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(update.running)
        if (update.hasPercent) {
            builder.setProgress(update.total, update.processed, false)
        } else if (update.running) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.agilizaai.cotacao.PORTAL_FILL_START"
        const val ACTION_STOP = "com.agilizaai.cotacao.PORTAL_FILL_STOP"

        private const val CHANNEL_ID = "portal_fill"
        private const val NOTIFICATION_ID = 4711
        private const val WAKE_LOCK_TAG = "agiliza:portal-fill"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val PROBE_DELAY_MS = 600L
        private const val LOCATE_RETRY_MS = 900L
        private const val LOGIN_SETTLE_MS = 1_500L
        private const val MAX_LOCATE_ATTEMPTS = 25
        // Lotes menores fazem a barra andar em passos visíveis em vez de saltar de
        // 0% para 100% em três atualizações, sem custo perceptível de velocidade.
        private const val BATCH_SIZE = 40
        private const val BATCH_PAUSE_MS = 60L
        private const val MIN_ELAPSED_FOR_ETA_MS = 400L
        private const val MAX_ETA_SECONDS = 2 * 60 * 60
        private const val OFFSCREEN_WIDTH = 1080
        private const val OFFSCREEN_HEIGHT = 1920

        fun start(context: Context) {
            val intent = Intent(context, PortalFillService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PortalFillService::class.java).setAction(ACTION_STOP))
        }
    }
}
