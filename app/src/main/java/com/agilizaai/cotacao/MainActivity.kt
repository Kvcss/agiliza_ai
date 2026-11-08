package com.agilizaai.cotacao

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.agilizaai.cotacao.data.AppPreferences
import com.agilizaai.cotacao.data.CaptureRecovery
import com.agilizaai.cotacao.data.CaptureSession
import com.agilizaai.cotacao.data.CaptureSessionStore
import com.agilizaai.cotacao.data.QuoteItem
import com.agilizaai.cotacao.data.QuoteRepository
import com.agilizaai.cotacao.data.QuoteStats
import com.agilizaai.cotacao.export.QuoteDocumentStreams
import com.agilizaai.cotacao.export.QuoteWorkbookFiller
import com.agilizaai.cotacao.export.XlsxExporter
import com.agilizaai.cotacao.web.PortalActivity
import com.agilizaai.cotacao.web.PortalConfigActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val excelWorkflowPreferences by lazy {
        getSharedPreferences(EXCEL_WORKFLOW_PREFERENCES, MODE_PRIVATE)
    }
    private lateinit var preferences: AppPreferences
    private lateinit var repository: QuoteRepository
    private lateinit var sessionStore: CaptureSessionStore
    private lateinit var serviceStatus: TextView
    private lateinit var ionStatus: TextView
    private lateinit var captureSummary: TextView
    private lateinit var saveStatus: TextView
    private lateinit var itemCount: TextView
    private lateinit var previewContainer: LinearLayout
    private lateinit var exportButton: Button
    private lateinit var consolidatedCount: TextView
    private val termCountLabels = linkedMapOf<Int, TextView>()
    private val termExportButtons = linkedMapOf<Int, Button>()
    private lateinit var captureButton: Button
    private lateinit var captureModeGroup: RadioGroup
    private lateinit var sequentialModeButton: RadioButton
    private lateinit var singleModeButton: RadioButton
    private lateinit var captureDaysGroup: RadioGroup
    private lateinit var captureModeSummary: TextView
    private lateinit var resumePanel: LinearLayout
    private lateinit var resumeSummary: TextView
    private lateinit var resumeButton: Button
    private lateinit var excelConditionGroup: RadioGroup
    private lateinit var excelBaseSummary: TextView
    private lateinit var quoteStepLabel: TextView
    private lateinit var quoteFileLabel: TextView
    private lateinit var fillQuoteButton: Button
    private lateinit var quoteSecondaryAction: TextView
    private lateinit var portalSummary: TextView
    private var selectedQuoteUri: Uri? = null
    private var generatedQuoteUri: Uri? = null
    private var generatedQuoteName: String = ""
    private var generatedQuoteSummary: String = ""
    private var quoteIsProcessing = false
    private var quoteIsValidating = false
    private var quoteValidationName = ""
    private var quoteValidationError = ""
    private var selectedQuoteTemplateSummary = ""
    private var launchingIon = false
    private var cachedStats: QuoteStats = QuoteStats.EMPTY
    private var cachedPreview: List<QuoteItem> = emptyList()
    private var pendingSession: CaptureSession? = null
    private var dataLoadInFlight = false
    private var dataLoadRequested = false
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val stopIdleCapture = Runnable {
        if (!launchingIon && preferences.captureArmed) sendCaptureCommand(AppPreferences.COMMAND_STOP)
    }
    private var receiverRegistered = false

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("message")?.let(::toast)
            refreshState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        repository = QuoteRepository(this)
        sessionStore = CaptureSessionStore(this)
        restoreExcelWorkflow()
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        lifecycleHandler.removeCallbacks(stopIdleCapture)
        val filter = IntentFilter(AppPreferences.ACTION_CAPTURE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(captureReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        launchingIon = false
        refreshState()
    }

    override fun onDestroy() {
        if (isFinishing && !launchingIon) sendCaptureCommand(AppPreferences.COMMAND_STOP)
        super.onDestroy()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(captureReceiver)
            receiverRegistered = false
        }
        if (!launchingIon && !isChangingConfigurations) {
            lifecycleHandler.postDelayed(stopIdleCapture, 700L)
        }
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> exportCapturedTable(uri, conditionDays = null)
            REQUEST_EXPORT_7 -> exportCapturedTable(uri, conditionDays = 7)
            REQUEST_EXPORT_14 -> exportCapturedTable(uri, conditionDays = 14)
            REQUEST_EXPORT_21 -> exportCapturedTable(uri, conditionDays = 21)
            REQUEST_EXPORT_28 -> exportCapturedTable(uri, conditionDays = 28)
            REQUEST_IMPORT_QUOTE -> selectQuoteFile(uri, data.flags)
            REQUEST_FILLED_QUOTE -> fillSelectedQuote(uri, data.flags)
        }
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.surface))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(buildHeader())
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), 0)
        }
        root.addView(body)

        body.addView(buildStatusCard())
        body.addView(space(12))
        body.addView(buildCaptureCard())
        body.addView(space(12))
        body.addView(buildDataCard())
        body.addView(space(12))
        body.addView(buildExcelQuoteCard())
        body.addView(space(12))
        body.addView(buildPortalCard())
        body.addView(space(18))
        body.addView(TextView(this).apply {
            text = "Os dados ficam neste aparelho. Revise códigos e preços antes de enviar uma cotação."
            setTextColor(color(R.color.muted))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        })
        return scroll
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(30), dp(20), dp(26))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color(R.color.navy_dark), color(R.color.navy), color(R.color.green))
        )
        addView(TextView(this@MainActivity).apply {
            text = "  AGILIZA  •  COTAÇÕES  "
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            background = rounded(
                Color.argb(38, 255, 255, 255),
                dp(20).toFloat(),
                Color.argb(62, 255, 255, 255)
            )
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        addView(TextView(this@MainActivity).apply {
            text = "Sua tabela de preços,\nsempre protegida"
            setTextColor(Color.WHITE)
            textSize = 29f
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 0.96f)
            setPadding(0, dp(14), 0, dp(8))
        })
        addView(TextView(this@MainActivity).apply {
            text = "Capture no ION, acompanhe o progresso e recupere até uma coleta interrompida."
            setTextColor(Color.rgb(220, 235, 240))
            textSize = 14f
            setLineSpacing(0f, 1.18f)
        })
    }

    private fun buildStatusCard(): View = card("Painel da coleta").apply {
        captureSummary = TextView(this@MainActivity).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        addView(captureSummary)
        saveStatus = bodyText("").apply {
            setPadding(dp(2), dp(10), dp(2), dp(9))
        }
        addView(saveStatus)
        addView(divider())
        serviceStatus = statusLine("Serviço de captura", "Verificando…")
        ionStatus = statusLine("Ion Vendas", "Verificando…")
        addView(serviceStatus)
        addView(divider())
        addView(ionStatus)
    }

    private fun buildCaptureCard(): View = card("1 · Nova coleta no ION").apply {
        addView(bodyText("No ION: CLIENTES > escolha um cliente > carrinho > Novo Pedido. Fique na aba CABEÇALHO. O Agiliza escolhe o prazo, abre cada produto apenas para leitura e volta sem confirmar."))
        addView(infoPanel(
            "SALVAMENTO AUTOMÁTICO",
            "Cada produto é protegido no aparelho assim que for lido. Se algo fechar, a coleta pode continuar do mesmo ponto."
        ))

        resumeSummary = TextView(this@MainActivity).apply {
            setTextColor(color(R.color.ink))
            textSize = 13f
            setLineSpacing(0f, 1.16f)
            setPadding(0, dp(4), 0, 0)
        }
        resumeButton = primaryButton("RETOMAR COLETA") { confirmResumeCapture() }
        resumePanel = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(13), dp(11), dp(13), dp(13))
            background = rounded(color(R.color.amber_soft), dp(13).toFloat())
            addView(TextView(this@MainActivity).apply {
                text = "COLETA INTERROMPIDA"
                setTextColor(color(R.color.amber))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
            })
            addView(resumeSummary)
            addView(resumeButton)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(4)
            }
        }
        addView(resumePanel)

        addView(TextView(this@MainActivity).apply {
            text = "Como capturar"
            setTextColor(color(R.color.ink))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(14), dp(2), dp(2))
        })
        captureModeGroup = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        sequentialModeButton = RadioButton(this@MainActivity).apply {
            id = View.generateViewId()
            text = "Sequencial · 7, 14, 21 e 28 dias"
            textSize = 14f
            setTextColor(color(R.color.ink))
            setOnCheckedChangeListener { _, checked -> if (checked) selectCaptureMode(AppPreferences.CAPTURE_MODE_SEQUENTIAL) }
        }
        singleModeButton = RadioButton(this@MainActivity).apply {
            id = View.generateViewId()
            text = "Somente uma categoria"
            textSize = 14f
            setTextColor(color(R.color.ink))
            setOnCheckedChangeListener { _, checked -> if (checked) selectCaptureMode(AppPreferences.CAPTURE_MODE_SINGLE) }
        }
        captureModeGroup.addView(sequentialModeButton)
        captureModeGroup.addView(singleModeButton)
        addView(captureModeGroup)

        captureDaysGroup = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(22), 0, 0, dp(2))
        }
        CAPTURE_DAYS.forEach { days ->
            captureDaysGroup.addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "$days"
                textSize = 14f
                setTextColor(color(R.color.ink))
                isChecked = days == preferences.captureSingleDays
                setOnCheckedChangeListener { _, checked ->
                    if (checked && days != preferences.captureSingleDays) {
                        preferences.captureSingleDays = days
                        refreshState()
                    }
                }
            })
        }
        addView(captureDaysGroup)
        captureModeSummary = bodyText("")
        addView(captureModeSummary)

        captureButton = primaryButton("INICIAR CAPTURA NO ION") { confirmNewCapture() }
        addView(captureButton)
        addView(textButton("Configurar permissão de captura") { showAccessibilityDisclosure() })
    }

    private fun selectCaptureMode(mode: String) {
        if (preferences.captureMode == mode) return
        preferences.captureMode = mode
        refreshState()
    }

    private fun buildDataCard(): View = card("2 · Última coleta salva").apply {
        itemCount = TextView(this@MainActivity).apply {
            setTextColor(color(R.color.ink))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        addView(itemCount)
        addView(bodyText("Cada prazo fica disponível assim que tiver preços salvos. Exporte um prazo separado ou baixe o consolidado com as quatro colunas."))
        CAPTURE_DAYS.forEach { days ->
            addView(buildCollectionExportRow(days))
            addView(space(7))
        }
        addView(buildCollectionExportRow(conditionDays = null))
        addView(TextView(this@MainActivity).apply {
            text = "Amostra da coleta"
            setTextColor(color(R.color.ink))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(18), dp(2), dp(2))
        })
        previewContainer = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        addView(previewContainer)
        addView(textButton("Apagar captura atual") { confirmClear() })
    }

    private fun buildCollectionExportRow(conditionDays: Int?): View {
        val isConsolidated = conditionDays == null
        val countLabel = TextView(this).apply {
            text = "Aguardando captura"
            setTextColor(color(R.color.muted))
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        }
        if (conditionDays != null) termCountLabels[conditionDays] = countLabel else consolidatedCount = countLabel

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = conditionDays?.let { "$it dias" } ?: "Consolidado"
                setTextColor(color(R.color.ink))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(countLabel)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val button = if (isConsolidated) {
            primaryButton("Exportar") { createExcelDocument(conditionDays = null) }
        } else {
            secondaryButton("Exportar") { createExcelDocument(conditionDays) }
        }.apply {
            minWidth = dp(102)
            minHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(dp(108), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        }
        if (conditionDays != null) termExportButtons[conditionDays] = button else exportButton = button

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(11), dp(11), dp(11))
            background = rounded(
                color(if (isConsolidated) R.color.green_soft else R.color.blue_soft),
                dp(14).toFloat()
            )
            addView(details)
            addView(button)
        }
    }

    private fun buildExcelQuoteCard(): View = card("3 · Cotação Excel por unidade").apply {
        addView(bodyText("Envie o modelo recebido. O Agiliza encontra cada produto pelo nome e preenche o preço unitário mostrado na tabela do ION, sem multiplicar pela embalagem."))
        addView(infoPanel(
            "TRÊS PASSOS",
            "1. Selecione o Excel da cotação\n2. Escolha a base de 7, 14, 21 ou 28 dias\n3. Gere o arquivo e compartilhe"
        ))
        quoteStepLabel = TextView(this@MainActivity).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(8))
        }
        addView(quoteStepLabel)
        quoteFileLabel = bodyText("Nenhum arquivo selecionado").apply {
            setTextColor(color(R.color.ink))
            setPadding(dp(2), dp(11), dp(2), dp(3))
        }
        addView(quoteFileLabel)
        fillQuoteButton = primaryButton("SELECIONAR EXCEL DA COTAÇÃO") { handleQuotePrimaryAction() }
        addView(fillQuoteButton)
        quoteSecondaryAction = textButton("Trocar arquivo da cotação") { chooseQuoteFile() }.apply {
            visibility = View.GONE
        }
        addView(quoteSecondaryAction)
        addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        })
        addView(TextView(this@MainActivity).apply {
            text = "Base de preços para este Excel"
            setTextColor(color(R.color.ink))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, dp(2), dp(2))
        })
        addView(bodyText("Escolha qual tabela capturada fornecerá o preço unitário para este Excel."))
        excelConditionGroup = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        CAPTURE_DAYS.forEach { days ->
            excelConditionGroup.addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                tag = days
                text = "$days dias"
                textSize = 14f
                setTextColor(color(R.color.ink))
                isChecked = days == preferences.excelConditionDays
                setOnCheckedChangeListener { _, checked ->
                    if (checked && days != preferences.excelConditionDays) {
                        preferences.excelConditionDays = days
                        clearGeneratedQuote()
                        refreshState()
                    }
                }
            })
        }
        addView(excelConditionGroup)
        excelBaseSummary = bodyText("")
        addView(excelBaseSummary)
    }

    private fun buildPortalCard(): View = card("4 · Preencher o portal").apply {
        addView(bodyText("Cadastre o link e abra o portal dentro do app. Você faz o login e navega manualmente; depois o app compara os produtos e preenche somente os campos de preço. Salvar e qualquer confirmação continuam manuais."))
        addView(infoPanel(
            "CONTINUA COM A TELA BLOQUEADA",
            "O preenchimento roda em segundo plano com uma notificação de progresso. Sair do app ou bloquear o celular não interrompe mais o processo."
        ))
        portalSummary = bodyText("").apply {
            setTextColor(color(R.color.ink))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(7), dp(2), dp(10))
        }
        addView(portalSummary)
        addView(primaryButton("ABRIR E PREENCHER PORTAL") {
            if (preferences.portalUrl.isBlank()) {
                toast("Cadastre o link do portal antes de abrir")
                startActivity(Intent(this@MainActivity, PortalConfigActivity::class.java))
            } else {
                startActivity(Intent(this@MainActivity, PortalActivity::class.java))
            }
        })
        addView(secondaryButton("CONFIGURAR PORTAL") {
            startActivity(Intent(this@MainActivity, PortalConfigActivity::class.java))
        })
    }

    /**
     * Uma base com 3.000 produtos por prazo não pode ser lida na thread principal:
     * era isso que travava a abertura do aplicativo. A tela desenha imediatamente com
     * o último resumo conhecido e o disco é lido em segundo plano.
     */
    private fun refreshState() {
        renderState(cachedStats)
        if (dataLoadInFlight) {
            dataLoadRequested = true
            return
        }
        dataLoadInFlight = true
        Thread {
            val stats = runCatching { repository.stats() }.getOrDefault(QuoteStats.EMPTY)
            val preview = runCatching { repository.load().take(PREVIEW_LIMIT) }.getOrDefault(emptyList())
            val session = runCatching { sessionStore.resumable() }.getOrNull()
                ?: runCatching {
                    if (!preferences.captureWasInterrupted) null else CaptureRecovery.deduce(
                        pricedByDays = stats.pricedByDays,
                        conditions = AppPreferences.CAPTURE_DAYS,
                        updatedAt = preferences.lastCaptureSavedAt
                    )
                }.getOrNull()
            runOnUiThread {
                dataLoadInFlight = false
                cachedStats = stats
                cachedPreview = preview
                pendingSession = session
                renderState(stats)
                if (dataLoadRequested) {
                    dataLoadRequested = false
                    refreshState()
                }
            }
        }.start()
    }

    private fun renderState(stats: QuoteStats) {
        val accessibilityEnabled = isCaptureServiceEnabled()
        serviceStatus.text = if (accessibilityEnabled) "●  Captura autorizada" else "●  Permissão ainda não ativada"
        serviceStatus.setTextColor(color(if (accessibilityEnabled) R.color.green else R.color.amber))

        val ionLaunchIntent = packageManager.getLaunchIntentForPackage(preferences.targetPackage)
        val ionInstalled = ionLaunchIntent != null
        ionStatus.text = if (ionInstalled) "●  Ion Vendas encontrado" else "●  Ion Vendas não encontrado"
        ionStatus.setTextColor(color(if (ionInstalled) R.color.green else R.color.amber))
        captureButton.isEnabled = ionInstalled
        renderCapturePlan(ionInstalled)

        val totalItems = stats.total
        val captureComplete = preferences.captureStatus == AppPreferences.CAPTURE_COMPLETE
        val hasDraft = stats.hasDraft
        val summaryStyle = when {
            preferences.captureStatus == AppPreferences.CAPTURE_IN_PROGRESS ->
                Triple("●  COLETA EM ANDAMENTO", R.color.blue, R.color.blue_soft)
            captureComplete ->
                Triple("✓  COLETA COMPLETA", R.color.green, R.color.green_soft)
            hasDraft && totalItems > 0 ->
                Triple("●  RASCUNHO PROTEGIDO", R.color.amber, R.color.amber_soft)
            totalItems > 0 ->
                Triple("✓  ÚLTIMA COLETA SALVA", R.color.green, R.color.green_soft)
            else ->
                Triple("PRONTO PARA CAPTURAR", R.color.blue, R.color.blue_soft)
        }
        captureSummary.text = summaryStyle.first
        captureSummary.setTextColor(color(summaryStyle.second))
        captureSummary.background = rounded(color(summaryStyle.third), dp(12).toFloat())
        val savedAt = preferences.lastCaptureSavedAt
        saveStatus.text = if (totalItems == 0) {
            "Nenhum dado salvo ainda. A coleta fica somente neste aparelho."
        } else {
            val whenSaved = if (savedAt > 0L) {
                SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(savedAt))
            } else {
                "horário não registrado"
            }
            val kind = if (hasDraft) "Rascunho atualizado" else "Última coleta atualizada"
            "$kind em $whenSaved · $totalItems produtos disponíveis"
        }
        itemCount.text = when (totalItems) {
            0 -> "Nenhum item capturado"
            1 -> "1 item capturado"
            else -> "$totalItems itens capturados"
        }
        if (totalItems > 0 && !captureComplete) {
            itemCount.text = "${itemCount.text}\nRascunho disponível para exportação"
            itemCount.setTextColor(color(R.color.amber))
        } else {
            itemCount.setTextColor(color(R.color.ink))
        }
        CAPTURE_DAYS.forEach { days ->
            val count = stats.pricedFor(days)
            termCountLabels[days]?.text = when (count) {
                0 -> "Aguardando captura"
                1 -> "1 produto com preço salvo"
                else -> "$count produtos com preço salvo"
            }
            termExportButtons[days]?.apply {
                isEnabled = count > 0
                alpha = if (isEnabled) 1f else 0.45f
            }
        }
        consolidatedCount.text = when (totalItems) {
            0 -> "Aguardando captura"
            1 -> "1 produto · quatro colunas de prazo"
            else -> "$totalItems produtos · quatro colunas de prazo"
        }
        exportButton.isEnabled = totalItems > 0
        exportButton.alpha = if (exportButton.isEnabled) 1f else 0.45f
        val excelConditionDays = preferences.excelConditionDays
        val excelPriceCount = stats.pricedFor(excelConditionDays)
        excelBaseSummary.text =
            "Base selecionada: $excelConditionDays dias · $excelPriceCount preços disponíveis\nFornecedor fixo: $FIXED_SUPPLIER_NAME"
        if (::portalSummary.isInitialized) {
            val portalDays = preferences.portalConditionDays
            val portalPriceCount = minOf(stats.withPortalBarcode, stats.pricedFor(portalDays))
            portalSummary.text = "Base do portal: $portalDays dias · $portalPriceCount preços unitários com EAN"
        }
        refreshExcelWorkflow(
            hasItems = totalItems > 0,
            selectedTableReady = excelPriceCount > 0
        )
        renderPreview(cachedPreview, totalItems)
    }

    private fun refreshExcelWorkflow(hasItems: Boolean, selectedTableReady: Boolean) {
        if (!::fillQuoteButton.isInitialized) return
        val excelConditionDays = preferences.excelConditionDays
        if (::excelConditionGroup.isInitialized) {
            for (index in 0 until excelConditionGroup.childCount) {
                excelConditionGroup.getChildAt(index).isEnabled = !quoteIsProcessing && !quoteIsValidating
            }
        }
        quoteSecondaryAction.visibility = if (!quoteIsValidating && (selectedQuoteUri != null || generatedQuoteUri != null)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        when {
            quoteIsValidating -> {
                styleQuoteStep("VALIDANDO O MODELO…", R.color.blue, R.color.blue_soft)
                quoteFileLabel.text = "Conferindo $quoteValidationName\nProcurando o cabeçalho 'nome' e a lista de produtos."
                fillQuoteButton.text = "VALIDANDO EXCEL…"
                fillQuoteButton.isEnabled = false
            }
            quoteIsProcessing -> {
                styleQuoteStep("PREENCHENDO O ARQUIVO…", R.color.blue, R.color.blue_soft)
                quoteFileLabel.text = "Processando ${selectedQuoteUri?.let(::displayName).orEmpty()}"
                fillQuoteButton.text = "Gerando Excel…"
                fillQuoteButton.isEnabled = false
            }
            generatedQuoteUri != null -> {
                styleQuoteStep("✓ ARQUIVO PRONTO PARA ENVIAR", R.color.green, R.color.green_soft)
                quoteFileLabel.text = buildString {
                    append(generatedQuoteName.ifBlank { "Cotação preenchida.xlsx" })
                    if (generatedQuoteSummary.isNotBlank()) append("\n").append(generatedQuoteSummary)
                }
                fillQuoteButton.text = "COMPARTILHAR EXCEL PREENCHIDO"
                fillQuoteButton.isEnabled = true
                quoteSecondaryAction.text = "Usar outro arquivo da cotação"
            }
            quoteValidationError.isNotBlank() -> {
                styleQuoteStep("ARQUIVO NÃO COMPATÍVEL", R.color.amber, R.color.amber_soft)
                quoteFileLabel.text = quoteValidationError
                fillQuoteButton.text = "SELECIONAR O MODELO ORIGINAL"
                fillQuoteButton.isEnabled = true
                quoteSecondaryAction.visibility = View.GONE
            }
            selectedQuoteUri == null -> {
                styleQuoteStep("PASSO 1 DE 3 · ESCOLHA O MODELO", R.color.blue, R.color.blue_soft)
                quoteFileLabel.text = "Nenhum arquivo selecionado"
                fillQuoteButton.text = "SELECIONAR EXCEL DA COTAÇÃO"
                fillQuoteButton.isEnabled = true
                quoteSecondaryAction.visibility = View.GONE
            }
            !hasItems -> {
                styleQuoteStep("MODELO SELECIONADO", R.color.amber, R.color.amber_soft)
                quoteFileLabel.text = "${displayName(selectedQuoteUri!!)}\nFaça uma coleta antes de preencher."
                fillQuoteButton.text = "AGUARDANDO UMA COLETA"
                fillQuoteButton.isEnabled = false
                quoteSecondaryAction.text = "Trocar arquivo da cotação"
            }
            !selectedTableReady -> {
                styleQuoteStep("PASSO 2 DE 3 · ESCOLHA UMA BASE DISPONÍVEL", R.color.amber, R.color.amber_soft)
                quoteFileLabel.text = "${displayName(selectedQuoteUri!!)}\nA tabela de $excelConditionDays dias ainda não possui preços salvos."
                fillQuoteButton.text = "BASE DE $excelConditionDays DIAS INDISPONÍVEL"
                fillQuoteButton.isEnabled = false
                quoteSecondaryAction.text = "Trocar arquivo da cotação"
            }
            else -> {
                styleQuoteStep("PASSO 3 DE 3 · PRONTO PARA GERAR", R.color.green, R.color.green_soft)
                quoteFileLabel.text = buildString {
                    append("Selecionado: ").append(documentDescription(selectedQuoteUri!!))
                    if (selectedQuoteTemplateSummary.isNotBlank()) {
                        append("\n").append(selectedQuoteTemplateSummary)
                    }
                    append("\nBase de dados: ").append(excelConditionDays)
                        .append(" dias · fornecedor: ").append(FIXED_SUPPLIER_NAME)
                }
                fillQuoteButton.text = "GERAR EXCEL PREENCHIDO"
                fillQuoteButton.isEnabled = true
                quoteSecondaryAction.text = "Trocar arquivo da cotação"
            }
        }
    }

    private fun styleQuoteStep(text: String, textColor: Int, backgroundColor: Int) {
        quoteStepLabel.text = text
        quoteStepLabel.setTextColor(color(textColor))
        quoteStepLabel.background = rounded(color(backgroundColor), dp(11).toFloat())
    }

    private fun handleQuotePrimaryAction() {
        when {
            generatedQuoteUri != null -> shareGeneratedQuote()
            quoteValidationError.isNotBlank() -> chooseQuoteFile()
            selectedQuoteUri == null -> chooseQuoteFile()
            else -> createFilledQuoteDocument()
        }
    }

    private fun renderPreview(items: List<QuoteItem>, totalItems: Int) {
        previewContainer.removeAllViews()
        if (items.isEmpty()) {
            previewContainer.addView(bodyText("Abra a tabela desejada no Ion e use o botão de captura."))
            return
        }
        items.take(PREVIEW_LIMIT).forEachIndexed { index, item ->
            if (index > 0) previewContainer.addView(divider())
            previewContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(TextView(this@MainActivity).apply {
                    text = item.description.ifBlank { "Produto sem descrição" }
                    setTextColor(color(R.color.ink))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    val barcode = item.barcode.ifBlank { "código de barras pendente" }
                    val prices = listOf(7, 14, 21, 28).joinToString(" · ") { days ->
                        "$days: ${item.displayPrice(days).ifBlank { "—" }}"
                    }
                    text = "$barcode\n$prices · caixa ${item.unitsPerCase.ifBlank { "1" }}"
                    setTextColor(color(R.color.muted))
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }
        if (totalItems > PREVIEW_LIMIT) {
            previewContainer.addView(bodyText("Mais ${totalItems - PREVIEW_LIMIT} itens serão incluídos no Excel."))
        }
    }

    /** Reflete na tela o plano escolhido e a coleta interrompida que pode continuar. */
    private fun renderCapturePlan(ionInstalled: Boolean) {
        if (!::captureModeGroup.isInitialized) return
        val singleMode = preferences.captureMode == AppPreferences.CAPTURE_MODE_SINGLE
        if (sequentialModeButton.isChecked == singleMode) sequentialModeButton.isChecked = !singleMode
        if (singleModeButton.isChecked != singleMode) singleModeButton.isChecked = singleMode
        for (index in 0 until captureDaysGroup.childCount) {
            captureDaysGroup.getChildAt(index).apply {
                isEnabled = singleMode
                alpha = if (singleMode) 1f else 0.45f
            }
        }
        val singleDays = preferences.captureSingleDays
        captureModeSummary.text = if (singleMode) {
            "Somente a tabela de $singleDays dias será lida. Os preços já salvos dos outros prazos continuam na base."
        } else {
            "As quatro tabelas serão lidas em sequência, uma após a outra, substituindo a base atual."
        }
        captureButton.text = if (singleMode) {
            "CAPTURAR SOMENTE $singleDays DIAS"
        } else {
            "INICIAR CAPTURA SEQUENCIAL"
        }

        val session = pendingSession
        if (session == null) {
            resumePanel.visibility = View.GONE
            return
        }
        resumePanel.visibility = View.VISIBLE
        val afterCurrent = session.remainingConditions.drop(1)
        val interrupted = if (session.updatedAt > 0L) {
            SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(session.updatedAt))
        } else {
            ""
        }
        resumeSummary.text = buildString {
            append("Parou na tabela de ").append(session.currentDays).append(" dias com ")
            append(session.readInCurrentCondition).append(" produtos já percorridos neste prazo.")
            if (afterCurrent.isNotEmpty()) {
                append("\nDepois dele ainda faltam: ")
                append(afterCurrent.joinToString(", ") { "$it dias" }).append(".")
            }
            if (interrupted.isNotBlank()) append("\nÚltimo registro em ").append(interrupted).append(".")
            append("\nRetomar continua deste ponto, sem repetir o que já foi lido.")
        }
        resumeButton.text = "RETOMAR DE ${session.currentDays} DIAS"
        resumeButton.isEnabled = ionInstalled
        resumeButton.alpha = if (ionInstalled) 1f else 0.45f
    }

    private fun confirmResumeCapture() {
        val session = pendingSession ?: return
        AlertDialog.Builder(this)
            .setTitle("Retomar a coleta de ${session.currentDays} dias?")
            .setMessage(
                "O Agiliza volta ao início da tabela, pula os ${session.readInCurrentCondition} produtos " +
                    "já lidos neste prazo e continua a partir daí. Nada do que já foi salvo será perdido."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Retomar") { _, _ -> beginCapture(resume = true) }
            .show()
    }

    private fun confirmNewCapture() {
        val session = pendingSession
        if (session == null) {
            beginCapture(resume = false)
            return
        }
        // Começar do zero descarta o ponto de retomada. Sem este aviso, um toque
        // no botão errado jogaria fora horas de leitura já feitas.
        AlertDialog.Builder(this)
            .setTitle("Descartar a coleta interrompida?")
            .setMessage(
                "Existe uma coleta parada na tabela de ${session.currentDays} dias. " +
                    "Iniciar uma nova captura descarta esse ponto de retomada — os produtos já salvos continuam na base."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Iniciar do zero") { _, _ -> beginCapture(resume = false) }
            .show()
    }

    private fun beginCapture(resume: Boolean) {
        if (!isCaptureServiceEnabled()) {
            showAccessibilityDisclosure()
            return
        }
        launchIonForCapture(resume)
    }

    private fun launchIonForCapture(resume: Boolean) {
        val session = pendingSession
        preferences.captureResumeRequested = resume && session != null
        preferences.captureArmed = true
        preferences.captureStatus = AppPreferences.CAPTURE_IN_PROGRESS
        preferences.captureStatusMessage = "Captura em andamento"
        sendCaptureCommand(AppPreferences.COMMAND_REFRESH)
        val launchIntent = packageManager.getLaunchIntentForPackage(preferences.targetPackage)
        if (launchIntent == null) {
            preferences.captureArmed = false
            preferences.captureResumeRequested = false
            toast("Ion Vendas não encontrado neste aparelho")
            return
        }
        val action = when {
            preferences.captureResumeRequested -> "RETOMAR ${session?.currentDays} DIAS"
            preferences.captureMode == AppPreferences.CAPTURE_MODE_SINGLE ->
                "CAPTURAR ${preferences.captureSingleDays} DIAS"
            else -> "CAPTURAR 4 PRAZOS"
        }
        toast("No Novo Pedido, fique em CABEÇALHO e toque em $action")
        launchingIon = true
        startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Permissão para ler o Ion Vendas")
            .setMessage(
                "Para montar sua cotação, o Agiliza precisa usar a Acessibilidade do Android. " +
                    "Somente depois do seu toque em CAPTURAR 4 PRAZOS, ele escolhe os planos 7/14/21/28, lê os campos visíveis, abre cada produto, volta e rola a lista. " +
                    "Ele não toca em SALVAR, no botão verde, em quantidades, valores ou menus.\n\n" +
                    "A leitura fica limitada ao pacote do Ion Vendas. Senhas não são lidas e os dados permanecem neste aparelho. " +
                    "Você pode desativar a permissão a qualquer momento."
            )
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Aceitar e continuar") { _, _ ->
                preferences.accessibilityDisclosureAccepted = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun isCaptureServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName &&
                info.resolveInfo.serviceInfo.name.endsWith("IonCaptureService")
        }
    }

    private fun createExcelDocument(conditionDays: Int? = null) {
        val items = repository.load()
        if (conditionDays == null && items.isEmpty()) return
        if (conditionDays != null && items.none { it.priceForDays(conditionDays).isNotBlank() }) return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val isComplete = preferences.captureStatus == AppPreferences.CAPTURE_COMPLETE
        val prefix = if (conditionDays != null) {
            if (isComplete) "tabela_${conditionDays}_dias" else "rascunho_${conditionDays}_dias"
        } else {
            if (isComplete) "tabela_consolidada" else "rascunho_consolidado"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, "${prefix}_$timestamp.xlsx")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, exportRequestCode(conditionDays))
    }

    private fun exportCapturedTable(uri: Uri, conditionDays: Int?) {
        val items = repository.load()
        exportButtonFor(conditionDays).isEnabled = false
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri, QuoteDocumentStreams.TRUNCATING_OUTPUT_MODE)?.use { output ->
                    XlsxExporter.write(
                        output = output,
                        items = items,
                        supplierName = FIXED_SUPPLIER_NAME,
                        conditionDays = conditionDays
                    )
                } ?: error("Não foi possível abrir o arquivo")
            }
            runOnUiThread {
                refreshState()
                val label = conditionDays?.let { "$it dias" } ?: "consolidado"
                toast(if (result.isSuccess) "Excel de $label exportado" else "Falha ao exportar o Excel")
            }
        }.start()
    }

    private fun exportRequestCode(conditionDays: Int?): Int = when (conditionDays) {
        null -> REQUEST_EXPORT
        7 -> REQUEST_EXPORT_7
        14 -> REQUEST_EXPORT_14
        21 -> REQUEST_EXPORT_21
        28 -> REQUEST_EXPORT_28
        else -> error("Prazo sem suporte: $conditionDays")
    }

    private fun exportButtonFor(conditionDays: Int?): Button = conditionDays
        ?.let { termExportButtons.getValue(it) }
        ?: exportButton

    private fun chooseQuoteFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMPORT_QUOTE)
    }

    private fun selectQuoteFile(uri: Uri, resultFlags: Int) {
        persistDocumentPermission(uri, resultFlags)
        clearGeneratedQuote()
        selectedQuoteUri = null
        selectedQuoteTemplateSummary = ""
        quoteValidationError = ""
        quoteValidationName = documentDescription(uri)
        quoteIsValidating = true
        excelWorkflowPreferences.edit()
            .remove(KEY_SELECTED_QUOTE_URI)
            .remove(KEY_SELECTED_QUOTE_SUMMARY)
            .apply()
        refreshState()
        Thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use(QuoteWorkbookFiller::inspectTemplate)
                    ?: error("Não foi possível abrir o arquivo selecionado")
            }
            runOnUiThread {
                quoteIsValidating = false
                result.onSuccess { template ->
                    selectedQuoteUri = uri
                    selectedQuoteTemplateSummary =
                        "Modelo confirmado: ${template.targetSheet}!${template.headerCell} · coluna ${template.targetColumn}"
                    excelWorkflowPreferences.edit()
                        .putString(KEY_SELECTED_QUOTE_URI, uri.toString())
                        .putString(KEY_SELECTED_QUOTE_SUMMARY, selectedQuoteTemplateSummary)
                        .apply()
                    refreshState()
                }.onFailure { error ->
                    quoteValidationError = buildString {
                        append("Arquivo aberto: ").append(quoteValidationName).append("\n")
                        append(error.message ?: "modelo incompatível").append("\n")
                        append("Selecione o modelo original que possui 'nome' na coluna H.")
                    }
                    refreshState()
                }
            }
        }.start()
    }

    private fun createFilledQuoteDocument() {
        val excelConditionDays = preferences.excelConditionDays
        if (selectedQuoteUri == null || repository.load().none { it.priceForDays(excelConditionDays).isNotBlank() }) return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, "cotacao_unidade_${excelConditionDays}d_$timestamp.xlsx")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_FILLED_QUOTE)
    }

    private fun fillSelectedQuote(outputUri: Uri, resultFlags: Int) {
        val inputUri = selectedQuoteUri ?: return
        val items = repository.load()
        val excelConditionDays = preferences.excelConditionDays
        if (items.none { it.priceForDays(excelConditionDays).isNotBlank() }) return
        persistDocumentPermission(outputUri, resultFlags)
        quoteIsProcessing = true
        refreshExcelWorkflow(hasItems = items.isNotEmpty(), selectedTableReady = true)
        Thread {
            val result = runCatching {
                QuoteDocumentStreams.withSnapshot(
                    openInput = { contentResolver.openInputStream(inputUri) },
                    openOutput = { mode -> contentResolver.openOutputStream(outputUri, mode) }
                ) { input, output ->
                    QuoteWorkbookFiller.fill(
                        input = input,
                        output = output,
                        items = items,
                        conditionDays = excelConditionDays,
                        supplierName = FIXED_SUPPLIER_NAME
                    )
                }
            }
            runOnUiThread {
                quoteIsProcessing = false
                result.onSuccess { report ->
                    generatedQuoteUri = outputUri
                    generatedQuoteName = displayName(outputUri)
                    generatedQuoteSummary = "Base $excelConditionDays dias · ${report.matched} preenchidos · ${report.unmatched} sem correspondência · aba ${report.targetSheet}, coluna ${report.targetColumn}"
                    excelWorkflowPreferences.edit()
                        .putString(KEY_GENERATED_QUOTE_URI, outputUri.toString())
                        .putString(KEY_GENERATED_QUOTE_NAME, generatedQuoteName)
                        .putString(KEY_GENERATED_QUOTE_SUMMARY, generatedQuoteSummary)
                        .apply()
                    refreshExcelWorkflow(hasItems = true, selectedTableReady = true)
                    showGeneratedQuoteDialog()
                }.onFailure { error ->
                    toast("Não foi possível preencher o Excel")
                    refreshState()
                    styleQuoteStep("NÃO FOI POSSÍVEL GERAR", R.color.amber, R.color.amber_soft)
                    quoteFileLabel.text = buildString {
                        append("Falha em ").append(documentDescription(inputUri)).append(":\n")
                        append(error.message ?: "modelo incompatível").append("\n")
                        append("Confira o arquivo e tente novamente.")
                    }
                }
            }
        }.start()
    }

    private fun showGeneratedQuoteDialog() {
        AlertDialog.Builder(this)
            .setTitle("Excel pronto para enviar")
            .setMessage("O arquivo foi preenchido e salvo. Você pode enviá-lo pelo WhatsApp, e-mail ou outro aplicativo.")
            .setNegativeButton("Compartilhar depois", null)
            .setPositiveButton("Compartilhar agora") { _, _ -> shareGeneratedQuote() }
            .show()
    }

    private fun shareGeneratedQuote() {
        val uri = generatedQuoteUri ?: return
        val fileName = generatedQuoteName.ifBlank { displayName(uri) }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = XLSX_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cotação preenchida")
            clipData = ClipData.newUri(contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Enviar cotação preenchida").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(chooser) }
            .onFailure { toast("Não encontrei um aplicativo para compartilhar o Excel") }
    }

    private fun persistDocumentPermission(uri: Uri, resultFlags: Int) {
        val takeFlags = resultFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (takeFlags != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, takeFlags) }
        }
    }

    private fun restoreExcelWorkflow() {
        if (!excelWorkflowPreferences.getBoolean(KEY_FIXED_SUPPLIER_MIGRATED, false)) {
            // Um arquivo preenchido com a configuração legada não pode ser
            // reaproveitado como se tivesse sido gerado para o fornecedor atual.
            excelWorkflowPreferences.edit()
                .remove(KEY_GENERATED_QUOTE_URI)
                .remove(KEY_GENERATED_QUOTE_NAME)
                .remove(KEY_GENERATED_QUOTE_SUMMARY)
                .putBoolean(KEY_FIXED_SUPPLIER_MIGRATED, true)
                .apply()
        }
        val storedInput = excelWorkflowPreferences.getString(KEY_SELECTED_QUOTE_URI, null)?.let(Uri::parse)
        val storedOutput = excelWorkflowPreferences.getString(KEY_GENERATED_QUOTE_URI, null)?.let(Uri::parse)
        selectedQuoteUri = storedInput?.takeIf(::canReadDocument)
        selectedQuoteTemplateSummary = excelWorkflowPreferences
            .getString(KEY_SELECTED_QUOTE_SUMMARY, "")
            .orEmpty()
        generatedQuoteUri = storedOutput?.takeIf(::canReadDocument)
        generatedQuoteName = excelWorkflowPreferences.getString(KEY_GENERATED_QUOTE_NAME, "").orEmpty()
        generatedQuoteSummary = excelWorkflowPreferences.getString(KEY_GENERATED_QUOTE_SUMMARY, "").orEmpty()
        if (selectedQuoteUri == null || (generatedQuoteUri == null && storedOutput != null)) {
            excelWorkflowPreferences.edit().apply {
                if (selectedQuoteUri == null) {
                    remove(KEY_SELECTED_QUOTE_URI)
                    remove(KEY_SELECTED_QUOTE_SUMMARY)
                }
                if (generatedQuoteUri == null) {
                    remove(KEY_GENERATED_QUOTE_URI)
                    remove(KEY_GENERATED_QUOTE_NAME)
                    remove(KEY_GENERATED_QUOTE_SUMMARY)
                }
            }.apply()
        }
    }

    private fun canReadDocument(uri: Uri): Boolean = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun clearGeneratedQuote() {
        generatedQuoteUri = null
        generatedQuoteName = ""
        generatedQuoteSummary = ""
        excelWorkflowPreferences.edit()
            .remove(KEY_GENERATED_QUOTE_URI)
            .remove(KEY_GENERATED_QUOTE_NAME)
            .remove(KEY_GENERATED_QUOTE_SUMMARY)
            .apply()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "cotação.xlsx" }

    private fun documentDescription(uri: Uri): String {
        var name = ""
        var size: Long? = null
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val resolvedName = name.ifBlank { displayName(uri) }
        val resolvedSize = size?.takeIf { it >= 0L } ?: return resolvedName
        return "$resolvedName · ${formatFileSize(resolvedSize)}"
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.forLanguageTag("pt-BR"), "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.forLanguageTag("pt-BR"), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun confirmClear() {
        if (repository.load().isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Apagar captura atual?")
            .setMessage("A última coleta e o rascunho automático serão removidos deste aparelho. O Excel já exportado não será apagado.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                repository.clear()
                preferences.captureStatus = AppPreferences.CAPTURE_IDLE
                preferences.captureStatusMessage = ""
                preferences.lastCaptureSavedAt = 0L
                preferences.lastCaptureSavedCount = 0
                refreshState()
            }
            .show()
    }

    private fun sendCaptureCommand(command: String) {
        sendBroadcast(
            Intent(AppPreferences.ACTION_CAPTURE_COMMAND)
                .setPackage(packageName)
                .putExtra(AppPreferences.EXTRA_COMMAND, command)
        )
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(17))
        background = rounded(color(R.color.card), dp(20).toFloat(), color(R.color.line))
        elevation = dp(3).toFloat()
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(color(R.color.ink))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun statusLine(label: String, value: String): TextView = TextView(this).apply {
        text = "$label\n$value"
        setTextColor(color(R.color.muted))
        textSize = 14f
        setLineSpacing(0f, 1.18f)
        setPadding(0, dp(7), 0, dp(7))
    }

    private fun bodyText(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(color(R.color.muted))
        textSize = 14f
        setLineSpacing(0f, 1.18f)
        setPadding(0, dp(3), 0, dp(8))
    }

    private fun infoPanel(title: String, message: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(color(R.color.green_soft), dp(13).toFloat())
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(color(R.color.green))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        })
        addView(TextView(this@MainActivity).apply {
            text = message
            setTextColor(color(R.color.ink))
            textSize = 13f
            setLineSpacing(0f, 1.16f)
            setPadding(0, dp(4), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        }
    }

    private fun primaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color(R.color.green), dp(14).toFloat())
        minHeight = dp(52)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    private fun secondaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(color(R.color.blue))
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color(R.color.blue_soft), dp(12).toFloat())
        minHeight = dp(50)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    private fun textButton(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(color(R.color.blue))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(14), dp(10), dp(8))
        setOnClickListener { click() }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(color(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun color(id: Int): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val EXCEL_WORKFLOW_PREFERENCES = "excel_workflow"
        private const val KEY_SELECTED_QUOTE_URI = "selected_quote_uri"
        private const val KEY_SELECTED_QUOTE_SUMMARY = "selected_quote_summary"
        private const val KEY_GENERATED_QUOTE_URI = "generated_quote_uri"
        private const val KEY_GENERATED_QUOTE_NAME = "generated_quote_name"
        private const val KEY_GENERATED_QUOTE_SUMMARY = "generated_quote_summary"
        private const val KEY_FIXED_SUPPLIER_MIGRATED = "fixed_supplier_vila_nova_migrated"
        private const val REQUEST_EXPORT = 8101
        private const val REQUEST_EXPORT_7 = 8104
        private const val REQUEST_EXPORT_14 = 8105
        private const val REQUEST_EXPORT_21 = 8106
        private const val REQUEST_EXPORT_28 = 8107
        private const val REQUEST_IMPORT_QUOTE = 8102
        private const val REQUEST_FILLED_QUOTE = 8103
        private const val PREVIEW_LIMIT = 5
        private const val FIXED_SUPPLIER_NAME = "Vila Nova"
        private val CAPTURE_DAYS = listOf(7, 14, 21, 28)
    }
}
