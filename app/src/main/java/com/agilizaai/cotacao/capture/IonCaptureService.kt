package com.agilizaai.cotacao.capture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.agilizaai.cotacao.data.AppPreferences
import com.agilizaai.cotacao.data.CaptureRecovery
import com.agilizaai.cotacao.data.CaptureSession
import com.agilizaai.cotacao.data.CaptureSessionStore
import com.agilizaai.cotacao.data.QuoteItem
import com.agilizaai.cotacao.data.QuoteRepository

/**
 * Automates only the read-only quotation route inside ION Vendas.
 *
 * Allowed actions: choose 7/14/21/28 days, open the TABELA tab, open a product for reading,
 * return with Android Back, and scroll the product list. It never clicks Save, the green check,
 * price shortcut buttons, quantity/value fields, or any overflow menu.
 */
class IonCaptureService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: AppPreferences
    private lateinit var repository: QuoteRepository
    private lateinit var sessionStore: CaptureSessionStore
    private lateinit var windowManager: WindowManager

    private var overlay: View? = null
    private var overlayTitle: TextView? = null
    private var overlayStatus: TextView? = null
    private var overlayAction: TextView? = null

    private var scanning = false
    private var paused = false
    private var phase = Phase.IDLE
    private var phaseAttempts = 0

    /**
     * Prazos desta coleta. Pode ser a sequência completa ou uma única categoria
     * escolhida na tela inicial.
     */
    private var conditions: List<Int> = AppPreferences.CAPTURE_DAYS
    private var sessionStartedAt = 0L
    private var resumedSession = false
    private var pendingResume: CaptureSession? = null
    private var pendingResumeLoaded = false
    private var conditionIndex = 0
    private var pageCount = 0
    private var unchangedPages = 0
    private var lastFingerprint = ""
    private var pendingScrollFingerprint: String? = null
    private var scrollLoadChecks = 0
    private var currentProduct: IonProductReader.ListProduct? = null
    private var outsideTargetChecks = 0
    private val visitedInCondition = linkedSetOf<String>()

    /**
     * Produtos que já têm preço salvo no prazo atual. Ao retomar, é o que permite
     * pular o que já foi lido mesmo quando a lista de visitados se perdeu junto com
     * o aplicativo — ou nunca chegou a existir.
     */
    private val alreadyPricedInCondition = hashSetOf<String>()
    private val conditionCounts = linkedMapOf<Int, Int>()
    private val capturedItems = LinkedHashMap<String, QuoteItem>()
    private val capturedIdentities = LinkedHashMap<String, IonProductReader.VariantIdentity>()

    /**
     * Guarda se o plano do prazo atual foi realmente escolhido. Sem isso o serviço
     * podia cair do cabeçalho direto para a TABELA e gravar os preços de 7 dias
     * como se fossem de 14, 21 ou 28.
     */
    private var planSelectionClicked = false

    /**
     * `moveTo` zera `phaseAttempts`, então o vaivém cabeçalho ↔ lista de planos
     * não é limitado por ele. Este contador é o que garante que a troca de prazo
     * desiste em vez de ficar em laço eterno.
     */
    private var planAttempts = 0
    private var listResetAttempts = 0
    private var lastListSignature = ""
    private var skippedConjunto = 0
    private var pendingDraftWrites = 0
    private var lastDraftWriteAt = 0L

    private val advanceRunnable = Runnable(::advance)
    private val visibilityRunnable = object : Runnable {
        override fun run() {
            if (overlay == null || scanning) return
            val root = rootInActiveWindow
            val activePackage = root?.packageName?.toString().orEmpty()
            root?.recycle()
            outsideTargetChecks = if (activePackage == preferences.targetPackage) 0 else outsideTargetChecks + 1
            if (outsideTargetChecks >= REQUIRED_OUTSIDE_CHECKS) {
                preferences.captureArmed = false
                preferences.captureStatus = AppPreferences.CAPTURE_IDLE
                preferences.captureStatusMessage = ""
                removeOverlay()
            } else {
                handler.postDelayed(this, VISIBILITY_CHECK_MS)
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(AppPreferences.EXTRA_COMMAND)) {
                AppPreferences.COMMAND_STOP -> cancelCapture("Captura encerrada")
                else -> {
                    // A tela inicial acabou de definir o plano/retomada desta coleta.
                    invalidatePendingResume()
                    refreshOverlayVisibility()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = AppPreferences(this)
        repository = QuoteRepository(this)
        sessionStore = CaptureSessionStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceInfo = serviceInfo.apply { packageNames = arrayOf(preferences.targetPackage) }
        val filter = IntentFilter(AppPreferences.ACTION_CAPTURE_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
        refreshOverlayVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (eventPackage != preferences.targetPackage) {
            if (!scanning) removeOverlay()
            return
        }
        preferences.lastSeenPackage = eventPackage
        if (scanning && !paused) scheduleAdvance(EVENT_SETTLE_MS) else refreshOverlayVisibility(eventPackage)
    }

    override fun onInterrupt() = cancelCapture("Captura interrompida")

    override fun onTaskRemoved(rootIntent: Intent?) {
        cancelCapture("Agiliza fechado")
        super.onTaskRemoved(rootIntent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (scanning) cancelCapture("Serviço de captura fechado")
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        runCatching { unregisterReceiver(commandReceiver) }
        return super.onUnbind(intent)
    }

    private fun refreshOverlayVisibility(currentPackage: String = preferences.lastSeenPackage) {
        val shouldShow = preferences.captureArmed && currentPackage == preferences.targetPackage
        if (shouldShow) {
            showOverlay()
            // O plano pode ter mudado na tela inicial enquanto o balão já estava
            // visível: o rótulo precisa refletir o que o toque realmente fará.
            if (!scanning) {
                overlayAction?.text = pendingActionLabel()
                overlayStatus?.text = pendingActionHint()
            }
        } else if (!scanning) {
            removeOverlay()
        }
    }

    /** Descreve o que o botão do balão fará: coleta nova, prazo único ou retomada. */
    private fun pendingActionLabel(): String {
        pendingResumeSession()?.let { return "RETOMAR ${it.currentDays} DIAS" }
        val planned = preferences.plannedConditions()
        return if (planned.size == 1) "CAPTURAR ${planned.first()} DIAS" else "CAPTURAR ${planned.size} PRAZOS"
    }

    private fun pendingActionHint(): String {
        val resume = pendingResumeSession()
        if (resume != null) {
            val afterCurrent = resume.remainingConditions.drop(1).joinToString(", ") { "$it" }
            return "Retomar ${resume.currentDays} dias · ${resume.readInCurrentCondition} já lidos" +
                if (afterCurrent.isNotBlank()) "\nDepois: $afterCurrent dias" else ""
        }
        val planned = preferences.plannedConditions()
        val plan = if (planned.size == 1) "${planned.first()} dias" else planned.joinToString(", ") { "$it" }
        return "No cabeçalho do Novo Pedido, toque abaixo\nPlano desta coleta: $plan"
    }

    /**
     * Sessão interrompida que o usuário pediu para continuar, se houver.
     *
     * O balão é reavaliado a cada evento de acessibilidade, então o arquivo é lido
     * uma vez só e guardado até a tela inicial anunciar uma mudança.
     */
    private fun pendingResumeSession(): CaptureSession? {
        if (!preferences.captureResumeRequested) return null
        if (!pendingResumeLoaded) {
            pendingResume = runCatching { sessionStore.resumable() }.getOrNull()
                // Coleta interrompida antes de existir arquivo de sessão: o ponto de
                // retomada ainda pode ser deduzido dos preços já salvos.
                ?: runCatching {
                    if (!preferences.captureWasInterrupted) null else CaptureRecovery.deduce(
                        pricedByDays = repository.stats().pricedByDays,
                        conditions = AppPreferences.CAPTURE_DAYS,
                        updatedAt = preferences.lastCaptureSavedAt
                    )
                }.getOrNull()
            pendingResumeLoaded = true
        }
        return pendingResume
    }

    private fun invalidatePendingResume() {
        pendingResume = null
        pendingResumeLoaded = false
    }

    private fun showOverlay() {
        if (overlay != null) return
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            keepScreenOn = scanning && !paused
            setPadding(dp(14), dp(11), dp(10), dp(10))
            background = roundedBackground(Color.rgb(13, 32, 58), dp(16).toFloat())
            elevation = dp(12).toFloat()
        }
        overlayTitle = TextView(this).apply {
            text = "AGILIZA · CAPTURA ION"
            setTextColor(Color.rgb(116, 232, 194))
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        overlayStatus = TextView(this).apply {
            text = pendingActionHint()
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), dp(7))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        overlayAction = actionText(pendingActionLabel()) {
            if (!scanning) startCapture() else togglePause()
        }
        val close = actionText("FECHAR") {
            cancelCapture("Captura fechada")
        }.apply { setTextColor(Color.rgb(255, 180, 180)) }
        actions.addView(overlayAction)
        actions.addView(close)
        container.addView(overlayTitle)
        container.addView(overlayStatus)
        container.addView(actions)

        val params = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(18)
        }
        runCatching {
            windowManager.addView(container, params)
            overlay = container
            outsideTargetChecks = 0
            handler.removeCallbacks(visibilityRunnable)
            handler.postDelayed(visibilityRunnable, VISIBILITY_CHECK_MS)
        }.onFailure {
            overlay = null
            overlayTitle = null
            overlayStatus = null
            overlayAction = null
        }
    }

    private fun actionText(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 11f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { action() }
    }

    private fun removeOverlay() {
        val current = overlay ?: return
        handler.removeCallbacks(visibilityRunnable)
        current.keepScreenOn = false
        runCatching { windowManager.removeView(current) }
        overlay = null
        overlayTitle = null
        overlayStatus = null
        overlayAction = null
    }

    private fun startCapture() {
        if (scanning) return
        val root = rootInActiveWindow ?: return
        val validPackage = root.packageName?.toString() == preferences.targetPackage
        val nodes = if (validPackage) ScreenScanner.snapshot(root, preferences.targetPackage) else emptyList()
        val safeStart = isOrderHeader(nodes)
        root.recycle()
        if (!validPackage || !safeStart) {
            overlayStatus?.text = "Abra Novo Pedido > CABEÇALHO antes de iniciar"
            Toast.makeText(this, "Inicie no cabeçalho do Novo Pedido", Toast.LENGTH_LONG).show()
            return
        }

        val resumeSession = pendingResumeSession()
        // A retomada não pode chamar beginNewCapture: isso promoveria o rascunho e
        // apagaria justamente os produtos que ela precisa continuar preenchendo.
        if (resumeSession == null) {
            val storageReady = runCatching { repository.beginNewCapture() }.isSuccess
            if (!storageReady) {
                overlayStatus?.text = "Não consegui preparar o salvamento automático"
                Toast.makeText(this, "Falha ao preparar o armazenamento da coleta", Toast.LENGTH_LONG).show()
                return
            }
        }
        val plannedConditions = preferences.plannedConditions()
        val session = resumeSession ?: CaptureSession(
            conditions = plannedConditions,
            startedAt = System.currentTimeMillis()
        )
        preferences.captureResumeRequested = false
        invalidatePendingResume()

        scanning = true
        paused = false
        resumedSession = resumeSession != null
        phase = Phase.OPEN_PLAN
        phaseAttempts = 0
        conditions = session.conditions.ifEmpty { AppPreferences.CAPTURE_DAYS }
        sessionStartedAt = session.startedAt
        conditionIndex = session.conditionIndex.coerceIn(0, conditions.lastIndex)
        pageCount = 0
        unchangedPages = 0
        lastFingerprint = ""
        pendingScrollFingerprint = null
        scrollLoadChecks = 0
        currentProduct = null
        visitedInCondition.clear()
        visitedInCondition += session.visitedKeys
        conditionCounts.clear()
        conditionCounts.putAll(session.conditionCounts)
        capturedItems.clear()
        capturedIdentities.clear()
        restoreCapturedBase(resume = resumeSession != null, plannedConditions = plannedConditions)
        refreshAlreadyPriced()
        planSelectionClicked = false
        planAttempts = 0
        listResetAttempts = 0
        lastListSignature = ""
        skippedConjunto = session.skippedConjunto
        pendingDraftWrites = 0
        lastDraftWriteAt = 0L
        preferences.captureStatus = AppPreferences.CAPTURE_IN_PROGRESS
        preferences.captureStatusMessage = if (resumedSession) {
            "Retomando a tabela de ${currentDays()} dias"
        } else {
            "Capturando ${conditions.joinToString(", ") { "$it" }} dias"
        }
        sessionStore.save(currentSession())
        overlayAction?.text = "PAUSAR"
        setCaptureScreenOn(true)
        val opening = if (resumedSession) "Retomando ${currentDays()} dias" else "Preparando ${currentDays()} dias"
        updateOverlay(opening, progressText())
        scheduleAdvance(200L)
    }

    /**
     * Recarrega o que já está salvo para dentro da coleta atual.
     *
     * Sem isso, retomar gravaria um rascunho contendo apenas os produtos lidos depois
     * da falha, e capturar um único prazo apagaria os preços dos outros três.
     */
    private fun restoreCapturedBase(resume: Boolean, plannedConditions: List<Int>) {
        // Retomar sempre parte do que existe: gravar um rascunho sem os produtos já
        // lidos apagaria a coleta anterior ao final da captura.
        val shouldRestore = resume || plannedConditions.size == 1
        if (!shouldRestore) return
        val items = runCatching { repository.load() }.getOrDefault(emptyList())
        items.forEach { item ->
            val identity = IonProductReader.variantIdentity(
                productCode = item.productCode,
                barcode = item.barcode,
                barcodeTruncated = false,
                description = item.description,
                packaging = item.packaging,
                unitsPerCase = item.unitsPerCase.ifBlank { item.quantity }
            )
            val key = identity.stableKey
            capturedItems[key] = capturedItems[key]?.let { mergeProduct(it, item) } ?: item
            capturedIdentities[key] = capturedIdentities[key]?.mergedWith(identity) ?: identity
        }
    }

    /**
     * Recalcula, para o prazo atual, quais produtos já têm preço gravado. Só vale numa
     * retomada: numa coleta nova o objetivo é justamente reler todos os preços.
     */
    private fun refreshAlreadyPriced() {
        alreadyPricedInCondition.clear()
        if (!resumedSession) return
        val days = currentDays()
        capturedItems.values.forEach { item ->
            if (item.priceForDays(days).isNotBlank()) {
                alreadyPricedInCondition += IonProductReader.resumeKey(item)
            }
        }
    }

    private fun togglePause() {
        if (!scanning) return
        paused = !paused
        if (paused) {
            handler.removeCallbacks(advanceRunnable)
            if (capturedItems.isNotEmpty()) persistCurrentDraft(force = true)
            overlayAction?.text = "CONTINUAR"
            setCaptureScreenOn(false)
            updateOverlay("Captura pausada", progressText())
        } else {
            overlayAction?.text = "PAUSAR"
            setCaptureScreenOn(true)
            updateOverlay("Retomando ${currentDays()} dias", progressText())
            scheduleAdvance(200L)
        }
    }

    private fun advance() {
        if (!scanning || paused) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != preferences.targetPackage) {
            root?.recycle()
            retryOrAbort("O ION saiu da tela durante a captura")
            return
        }
        val nodes = runCatching { ScreenScanner.snapshot(root, preferences.targetPackage) }.getOrDefault(emptyList())
        try {
            when (phase) {
                Phase.OPEN_PLAN -> openPaymentPlan(root, nodes)
                Phase.CHOOSE_PLAN -> choosePaymentPlan(root, nodes)
                Phase.OPEN_TABLE -> openProductTable(root, nodes)
                Phase.RESET_LIST -> resetListToTop(root, nodes)
                Phase.SCAN_LIST -> scanProductList(root, nodes)
                Phase.WAIT_DETAIL -> waitForDetail(nodes)
                Phase.READ_DETAIL -> readProductDetail(nodes)
                Phase.WAIT_LIST_RETURN -> waitForList(nodes)
                Phase.RETURN_HEADER -> returnToHeader(root, nodes)
                Phase.IDLE -> Unit
            }
        } finally {
            root.recycle()
        }
    }

    private fun openPaymentPlan(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        when {
            isPaymentPlanList(nodes) -> moveTo(Phase.CHOOSE_PLAN)
            !isOrderHeader(nodes) -> retryOrAbort("Não encontrei o cabeçalho do Novo Pedido")
            // O cabeçalho já exibe exatamente este prazo: não há o que trocar.
            headerShowsPlan(nodes, currentDays()) -> {
                planSelectionClicked = true
                updateOverlay("Plano de ${currentDays()} dias já ativo", progressText())
                moveTo(Phase.OPEN_TABLE)
            }
            // ION Vendas 1.26.7 exposes the selected plan itself as the
            // clickable control. Older versions expose one of the row IDs.
            planAttempts >= MAX_PLAN_ATTEMPTS ->
                abortCapture(
                    "Não consegui trocar para o plano de ${currentDays()} dias",
                    AppPreferences.CAPTURE_ABORTED
                )
            clickById(root, "buttonSelecionarPlPag") ||
                clickById(root, "imageButtonConfiguracoesPlanoPagamento") ||
                clickById(root, "linhaPlanoPagamento") ||
                clickById(root, "linhaDadosPlanoPagamento") ||
                clickTextExact(root, "Plano de pagamento") -> {
                planAttempts++
                updateOverlay("Selecionando ${currentDays()} dias", progressText())
                moveTo(Phase.CHOOSE_PLAN, ACTION_SETTLE_MS)
            }
            else -> retryOrAbort("Não encontrei Plano de pagamento")
        }
    }

    private fun choosePaymentPlan(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        if (isOrderHeader(nodes)) {
            // A lista de planos ainda não apareceu (ou fechou sozinha). Avançar aqui era
            // o que fazia o serviço reler a tabela do prazo anterior com o rótulo errado.
            when {
                headerShowsPlan(nodes, currentDays()) -> {
                    planSelectionClicked = true
                    moveTo(Phase.OPEN_TABLE)
                }
                planSelectionClicked -> moveTo(Phase.OPEN_TABLE)
                else -> {
                    updateOverlay("Reabrindo os planos de pagamento", progressText())
                    moveTo(Phase.OPEN_PLAN, ACTION_SETTLE_MS)
                }
            }
            return
        }
        if (!isPaymentPlanList(nodes)) {
            retryOrAbort("A lista de planos não abriu")
            return
        }
        val label = "${currentDays()} DIAS"
        if (clickTextExact(root, label)) {
            planSelectionClicked = true
            planAttempts = 0
            updateOverlay("Plano $label selecionado", progressText())
            moveTo(Phase.OPEN_TABLE, ACTION_SETTLE_MS)
        } else {
            retryOrAbort("Não encontrei o plano $label")
        }
    }

    private fun openProductTable(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        if (!planSelectionClicked) {
            // Nunca ler uma tabela sem ter confirmado de qual prazo ela é.
            updateOverlay("Confirmando o plano de ${currentDays()} dias", progressText())
            moveTo(Phase.OPEN_PLAN, ACTION_SETTLE_MS)
            return
        }
        when {
            IonProductReader.isProductList(nodes) -> moveTo(Phase.RESET_LIST)
            isOrderTabScreen(nodes) && clickTextExact(root, "TABELA") -> {
                updateOverlay("Abrindo tabela de ${currentDays()} dias", progressText())
                moveTo(Phase.RESET_LIST, ACTION_SETTLE_MS)
            }
            else -> retryOrAbort("Não encontrei a aba TABELA")
        }
    }

    /**
     * Ao trocar de prazo o ION mantém a rolagem onde estava. Sem voltar ao topo, o
     * prazo seguinte começaria no fim da lista e perderia todos os produtos acima.
     */
    private fun resetListToTop(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        if (!IonProductReader.isProductList(nodes)) {
            retryOrAbort("A lista de produtos não está visível para voltar ao topo")
            return
        }
        val signature = listSignature(nodes)
        if (listResetAttempts >= MAX_LIST_RESET_SCROLLS ||
            (listResetAttempts > 0 && signature == lastListSignature)
        ) {
            lastListSignature = ""
            listResetAttempts = 0
            updateOverlay("Tabela de ${currentDays()} dias no topo", progressText())
            moveTo(Phase.SCAN_LIST, LIST_STABILITY_MS)
            return
        }
        val scrollable = ScreenScanner.findBestScrollable(root)
        if (scrollable == null) {
            listResetAttempts = 0
            moveTo(Phase.SCAN_LIST, LIST_STABILITY_MS)
            return
        }
        val moved = runCatching {
            // Caminho rápido: pular direto para a primeira linha em vez de rolar
            // centenas de páginas para trás numa tabela de 3.000 produtos.
            val jumped = listResetAttempts == 0 && scrollable.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
            } && scrollable.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
                }
            )
            if (jumped) return@runCatching true
            val supportsScrollUp = scrollable.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }
            val action = if (supportsScrollUp) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            scrollable.performAction(action)
        }.getOrDefault(false)
        scrollable.recycle()
        lastListSignature = signature
        if (!moved) {
            listResetAttempts = 0
            lastListSignature = ""
            moveTo(Phase.SCAN_LIST, LIST_STABILITY_MS)
            return
        }
        listResetAttempts++
        updateOverlay("Voltando ao início de ${currentDays()} dias", "rolagem $listResetAttempts")
        scheduleAdvance(SCROLL_SETTLE_MS)
    }

    private fun scanProductList(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        if (IonProductReader.isProductDetail(nodes)) {
            moveTo(Phase.WAIT_DETAIL)
            return
        }
        if (!IonProductReader.isProductList(nodes)) {
            if (pendingScrollFingerprint != null && isOrderTabScreen(nodes)) {
                if (scrollLoadChecks < MAX_SCROLL_LOAD_CHECKS) {
                    scrollLoadChecks++
                    updateOverlay(
                        "Aguardando a tabela carregar",
                        "${visitedInCondition.size} lidos · verificação $scrollLoadChecks/$MAX_SCROLL_LOAD_CHECKS"
                    )
                    scheduleAdvance(SCROLL_LOAD_POLL_MS)
                } else {
                    retryOrAbort("A tabela demorou para carregar")
                }
                return
            }
            if (isOrderTabScreen(nodes) && clickTextExact(root, "TABELA")) {
                updateOverlay("Voltando para a TABELA", "Aba incorreta detectada com segurança")
                moveTo(Phase.SCAN_LIST, ACTION_SETTLE_MS)
                return
            }
            retryOrAbort("A lista de produtos não está visível")
            return
        }

        val products = IonProductReader.readList(nodes)

        // Produtos em conjunto (Und: CJ) não entram na base. Descartá-los aqui evita
        // abrir o detalhe à toa — com 3.000 itens por tabela isso economiza muito tempo.
        val conjuntos = products.filter { it.isConjunto && visitToken(it) !in visitedInCondition }
        if (conjuntos.isNotEmpty()) {
            conjuntos.forEach { visitedInCondition += visitToken(it) }
            skippedConjunto += conjuntos.size
            unchangedPages = 0
            pendingScrollFingerprint = null
            scrollLoadChecks = 0
            updateOverlay("${conjuntos.size} conjunto(s) ignorado(s)", progressText())
            scheduleAdvance(150L)
            return
        }

        // Numa retomada, o produto que já tem preço deste prazo entra direto para os
        // visitados: é o que faz a captura pular horas de leitura já feita.
        val resumed = products.filter { alreadyRead(it) && visitToken(it) !in visitedInCondition }
        if (resumed.isNotEmpty()) {
            resumed.forEach { visitedInCondition += visitToken(it) }
            unchangedPages = 0
            pendingScrollFingerprint = null
            scrollLoadChecks = 0
            updateOverlay("${resumed.size} já lido(s) neste prazo", progressText())
            scheduleAdvance(120L)
            return
        }

        val next = products.firstOrNull { visitToken(it) !in visitedInCondition }
        if (next != null) {
            pendingScrollFingerprint = null
            scrollLoadChecks = 0
            unchangedPages = 0
            currentProduct = next
            if (clickProductRow(root, next)) {
                updateOverlay("Lendo ${currentDays()} dias", "${visitedInCondition.size} lidos · ${next.description.take(28)}")
                moveTo(Phase.WAIT_DETAIL, PRODUCT_OPEN_MS)
            } else {
                visitedInCondition += visitToken(next)
                retryOrAbort("Não consegui abrir ${next.productCode}")
            }
            return
        }

        val fingerprint = listSignature(nodes)
        val beforeScroll = pendingScrollFingerprint
        if (beforeScroll != null) {
            if (fingerprint != beforeScroll) {
                // The list moved or finished an asynchronous load. Give its rows one quiet
                // interval before deciding whether another scroll is necessary.
                pendingScrollFingerprint = null
                scrollLoadChecks = 0
                unchangedPages = 0
                lastFingerprint = fingerprint
                updateOverlay("Tabela atualizada", progressText())
                scheduleAdvance(LIST_STABILITY_MS)
                return
            }

            if (scrollLoadChecks < MAX_SCROLL_LOAD_CHECKS) {
                scrollLoadChecks++
                updateOverlay(
                    "Aguardando carregar ${currentDays()} dias",
                    "${visitedInCondition.size} lidos · verificação $scrollLoadChecks/$MAX_SCROLL_LOAD_CHECKS"
                )
                scheduleAdvance(SCROLL_LOAD_POLL_MS)
                return
            }

            // Some ION builds report a successful scroll even at the physical end. Confirm
            // that the same viewport remains stable across independent vertical scrolls.
            pendingScrollFingerprint = null
            scrollLoadChecks = 0
            unchangedPages++
            if (unchangedPages >= REQUIRED_STABLE_PAGES) {
                finishCondition()
                return
            }
        } else if (lastFingerprint.isNotEmpty() && fingerprint != lastFingerprint) {
            unchangedPages = 0
        }
        lastFingerprint = fingerprint

        if (pageCount >= MAX_PAGES_PER_CONDITION) {
            abortCapture("Limite de páginas em ${currentDays()} dias; captura incompleta", AppPreferences.CAPTURE_TRUNCATED)
            return
        }
        val scrollable = ScreenScanner.findBestScrollable(root)
        if (scrollable == null) {
            retryOrAbort("Não encontrei a lista vertical de produtos")
            return
        }
        val didScroll = runCatching {
            val supportsScrollDown = scrollable.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            }
            val action = if (supportsScrollDown) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            scrollable.performAction(action)
        }.getOrDefault(false)
        scrollable.recycle()
        if (!didScroll) {
            unchangedPages++
            if (unchangedPages >= REQUIRED_STABLE_PAGES) {
                finishCondition()
            } else {
                updateOverlay("Confirmando fim de ${currentDays()} dias", progressText())
                scheduleAdvance(SCROLL_LOAD_POLL_MS)
            }
        } else {
            pageCount++
            pendingScrollFingerprint = fingerprint
            scrollLoadChecks = 0
            updateOverlay("Rolando ${currentDays()} dias", progressText())
            scheduleAdvance(SCROLL_SETTLE_MS)
        }
    }

    private fun waitForDetail(nodes: List<NodeSnapshot>) {
        if (IonProductReader.isProductDetail(nodes)) {
            moveTo(Phase.READ_DETAIL, DETAIL_SETTLE_MS)
        } else {
            retryOrAbort("O detalhe do produto não abriu")
        }
    }

    private fun readProductDetail(nodes: List<NodeSnapshot>) {
        val listProduct = currentProduct
        if (listProduct == null) {
            abortCapture("Produto atual perdido; captura interrompida", AppPreferences.CAPTURE_ABORTED)
            return
        }
        val item = IonProductReader.readDetail(
            nodes = nodes,
            listProduct = listProduct,
            conditionDays = currentDays(),
            sourcePackage = preferences.targetPackage
        )
        if (item == null) {
            retryOrAbort("Preço/detalhes de ${listProduct.productCode} não apareceram")
            return
        }

        val identity = IonProductReader.variantIdentity(listProduct, item)
        val exactKey = identity.stableKey
        // A varredura por variante compatível percorre a base inteira. Com milhares de
        // produtos já lidos ela só compensa quando a chave exata ainda não existe.
        val key = if (capturedItems.containsKey(exactKey)) {
            exactKey
        } else {
            capturedIdentities.entries
                .asSequence()
                .filter { (_, existingIdentity) -> existingIdentity.isCompatibleWith(identity) }
                .map { it.key }
                .singleOrNull()
                ?: exactKey
        }
        val eligibleStock = item.isEligibleForBase()
        if (eligibleStock) {
            capturedItems[key] = mergeProduct(capturedItems[key], item)
            capturedIdentities[key] = capturedIdentities[key]?.mergedWith(identity) ?: identity
        } else {
            // If a later pass reveals insufficient/unknown stock, remove the variant from
            // the protected draft too. Product-code duplicates remain independent by key.
            capturedItems.remove(key)
            capturedIdentities.remove(key)
        }
        if (!persistCurrentDraft()) {
            abortCapture("Não consegui salvar o rascunho no aparelho", AppPreferences.CAPTURE_ABORTED)
            return
        }
        visitedInCondition += visitToken(listProduct)
        currentProduct = null
        conditionCounts[currentDays()] = visitedInCondition.size
        val stockStatus = when {
            eligibleStock -> "produto gravado · estoque ${item.stock}"
            item.isExcludedSalesUnit() -> {
                skippedConjunto++
                "ignorado · conjunto (Und: CJ) não entra na base"
            }
            else -> "ignorado · estoque abaixo de 3 ou ilegível"
        }
        updateOverlay("${currentDays()} dias · $stockStatus", progressText())

        // The only exit from product detail is Android Back. Never click the green confirmation.
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            moveTo(Phase.WAIT_LIST_RETURN, PRODUCT_RETURN_MS)
        } else {
            abortCapture("Não consegui voltar sem confirmar o produto", AppPreferences.CAPTURE_ABORTED)
        }
    }

    private fun waitForList(nodes: List<NodeSnapshot>) {
        if (IonProductReader.isProductList(nodes)) {
            moveTo(Phase.SCAN_LIST, 150L)
        } else {
            retryOrAbort("Aguardando retorno seguro à lista")
        }
    }

    private fun finishCondition() {
        conditionCounts[currentDays()] = visitedInCondition.size
        // Grava o que foi lido neste prazo antes de trocar de plano.
        persistCurrentDraft(force = true)
        if (conditionIndex == conditions.lastIndex) {
            finishCapture()
            return
        }
        val completedDays = currentDays()
        conditionIndex++
        visitedInCondition.clear()
        refreshAlreadyPriced()
        currentProduct = null
        pageCount = 0
        unchangedPages = 0
        lastFingerprint = ""
        pendingScrollFingerprint = null
        scrollLoadChecks = 0
        // O próximo prazo precisa ser escolhido do zero no cabeçalho.
        planSelectionClicked = false
        planAttempts = 0
        listResetAttempts = 0
        lastListSignature = ""
        // O novo prazo começa com a lista de visitados vazia: gravar a sessão aqui é
        // o que faz a retomada continuar no prazo certo em vez de repetir o anterior.
        sessionStore.save(currentSession())
        updateOverlay("$completedDays dias concluído", "Próximo: ${currentDays()} dias")
        moveTo(Phase.RETURN_HEADER, 250L)
    }

    private fun returnToHeader(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        when {
            isOrderHeader(nodes) -> moveTo(Phase.OPEN_PLAN)
            clickTextExact(root, "CABEÇALHO") || clickTabByPrefix(root, "CABE") -> {
                updateOverlay("Voltando ao cabeçalho", "Preparando ${currentDays()} dias")
                moveTo(Phase.OPEN_PLAN, ACTION_SETTLE_MS)
            }
            else -> retryOrAbort("Não encontrei a aba CABEÇALHO")
        }
    }

    /**
     * A barra de abas do Novo Pedido rola na horizontal e o CABEÇALHO costuma ficar
     * cortado ("...LHO"). Este atalho encontra a aba pelo início do nome, inclusive
     * quando o rótulo visível foi truncado pelo próprio ION.
     */
    private fun clickTabByPrefix(root: AccessibilityNodeInfo, prefix: String): Boolean {
        val normalizedPrefix = normalize(prefix)
        val nodes = ScreenScanner.snapshot(root, preferences.targetPackage)
        val target = nodes.firstOrNull {
            val text = normalize(it.text)
            text.startsWith(normalizedPrefix) || normalizedPrefix.startsWith(text) && text.length >= 3
        } ?: return false
        val candidates = root.findAccessibilityNodeInfosByText(target.text)
        return try {
            candidates.asSequence()
                .mapNotNull { clickableCopy(it) }
                .firstOrNull()
                ?.let { clickable ->
                    try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { clickable.recycle() }
                } == true
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    private fun finishCapture() {
        if (!scanning) return
        scanning = false
        paused = false
        setCaptureScreenOn(false)
        handler.removeCallbacks(advanceRunnable)
        preferences.captureArmed = false
        val items = capturedItems.values.toList()
        val completeConditions = conditions.all { conditionCounts.containsKey(it) }
        val result = runCatching {
            check(items.isNotEmpty()) { "Nenhum produto foi reconhecido" }
            check(completeConditions) { "Nem todos os prazos foram concluídos" }
            repository.replace(items)
            preferences.lastCaptureSavedAt = System.currentTimeMillis()
            preferences.lastCaptureSavedCount = items.size
        }
        // Não há mais o que retomar: a sessão pendente precisa sair do caminho para a
        // tela inicial não oferecer a continuação de uma coleta já encerrada.
        sessionStore.clear()
        val finishedPlan = if (conditions.size == 1) {
            "${conditions.first()} dias concluído"
        } else {
            "${conditions.size} prazos concluídos"
        }
        val message = result.fold(
            onSuccess = { "${items.size} produtos · $finishedPlan" },
            onFailure = {
                val reason = it.message ?: "Não foi possível finalizar a captura"
                if (repository.hasDraft()) {
                    "$reason · rascunho automático mantido"
                } else {
                    "$reason · última coleta válida mantida"
                }
            }
        )
        preferences.captureStatus = if (result.isSuccess) AppPreferences.CAPTURE_COMPLETE else AppPreferences.CAPTURE_ABORTED
        preferences.captureStatusMessage = message
        overlayAction?.text = "CONCLUÍDO"
        updateOverlay(if (result.isSuccess) "CAPTURA CONCLUÍDA" else "CAPTURA INCOMPLETA", message)
        notifyState(message, items.size)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        handler.postDelayed(::removeOverlay, 5_000L)
    }

    private fun cancelCapture(message: String) {
        handler.removeCallbacksAndMessages(null)
        val hadCurrentItems = capturedItems.isNotEmpty()
        if (hadCurrentItems) persistCurrentDraft(force = true)
        // Fechar o app no meio da tabela é justamente o caso que a retomada resolve:
        // o ponto exato da coleta fica gravado antes de o serviço soltar o estado.
        if (scanning) runCatching { sessionStore.save(currentSession()) }
        scanning = false
        paused = false
        setCaptureScreenOn(false)
        phase = Phase.IDLE
        preferences.captureArmed = false
        if (preferences.captureStatus == AppPreferences.CAPTURE_IN_PROGRESS) {
            preferences.captureStatus = AppPreferences.CAPTURE_ABORTED
            preferences.captureStatusMessage = if (hadCurrentItems) {
                "$message · ${repository.load().size} produtos salvos automaticamente"
            } else {
                "$message · a coleta anterior foi mantida"
            }
        }
        notifyState(preferences.captureStatusMessage.ifBlank { message }, repository.load().size)
        removeOverlay()
    }

    private fun abortCapture(message: String, status: String) {
        if (!scanning) return
        handler.removeCallbacks(advanceRunnable)
        if (capturedItems.isNotEmpty()) persistCurrentDraft(force = true)
        runCatching { sessionStore.save(currentSession()) }
        scanning = false
        paused = false
        setCaptureScreenOn(false)
        preferences.captureArmed = false
        preferences.captureStatus = status
        val protectedCount = repository.load().size
        val protectedMessage = if (capturedItems.isNotEmpty() && protectedCount > 0) {
            "$message · $protectedCount produtos salvos automaticamente"
        } else {
            "$message · a coleta anterior foi mantida"
        }
        preferences.captureStatusMessage = protectedMessage
        overlayAction?.text = "FECHAR"
        updateOverlay("CAPTURA INTERROMPIDA", protectedMessage)
        notifyState(protectedMessage, protectedCount)
        Toast.makeText(this, protectedMessage, Toast.LENGTH_LONG).show()
        handler.postDelayed(::removeOverlay, 5_000L)
    }

    private fun retryOrAbort(message: String) {
        phaseAttempts++
        if (phaseAttempts >= MAX_PHASE_ATTEMPTS) {
            abortCapture(message, AppPreferences.CAPTURE_ABORTED)
        } else {
            updateOverlay(message, "Tentativa $phaseAttempts/$MAX_PHASE_ATTEMPTS")
            scheduleAdvance(RETRY_MS)
        }
    }

    private fun moveTo(next: Phase, delay: Long = 100L) {
        phase = next
        phaseAttempts = 0
        scheduleAdvance(delay)
    }

    private fun scheduleAdvance(delay: Long) {
        if (!scanning || paused) return
        handler.removeCallbacks(advanceRunnable)
        handler.postDelayed(advanceRunnable, delay)
    }

    private fun currentDays(): Int = conditions.getOrElse(conditionIndex) { conditions.last() }

    /** Token curto e estável de um produto da lista, do tamanho que cabe em disco. */
    private fun visitToken(product: IonProductReader.ListProduct): String =
        IonProductReader.shortHash(product.stableKey)

    /** O produto já tem preço gravado no prazo que está sendo lido agora. */
    private fun alreadyRead(product: IonProductReader.ListProduct): Boolean =
        alreadyPricedInCondition.isNotEmpty() &&
            IonProductReader.resumeKey(product) in alreadyPricedInCondition

    private fun currentSession(now: Long = System.currentTimeMillis()) = CaptureSession(
        conditions = conditions,
        conditionIndex = conditionIndex,
        visitedKeys = LinkedHashSet(visitedInCondition),
        conditionCounts = LinkedHashMap(conditionCounts),
        skippedConjunto = skippedConjunto,
        startedAt = sessionStartedAt,
        updatedAt = now
    )

    private fun progressText(): String {
        val terms = conditions.joinToString(" ") { days ->
            if (days == currentDays()) "[$days]" else days.toString()
        }
        val saved = if (capturedItems.isNotEmpty()) " · ✓ salvo" else ""
        val conjuntos = if (skippedConjunto > 0) " · $skippedConjunto CJ fora" else ""
        return "$terms · ${visitedInCondition.size} neste prazo · ${capturedItems.size} produtos$conjuntos$saved"
    }

    /**
     * Regravar a base inteira a cada produto é O(n²): no fim de uma tabela de 3.000
     * itens cada leitura reserializava 3.000 registros e a captura arrastava. O
     * rascunho agora é gravado em lotes, e sempre de forma completa nos pontos
     * críticos (fim de prazo, pausa, cancelamento e conclusão).
     */
    private fun persistCurrentDraft(force: Boolean = false): Boolean {
        pendingDraftWrites++
        val now = System.currentTimeMillis()
        val due = force ||
            pendingDraftWrites >= DRAFT_WRITE_EVERY ||
            now - lastDraftWriteAt >= DRAFT_WRITE_INTERVAL_MS
        if (!due) return true
        val snapshot = capturedItems.values.toList()
        return runCatching {
            repository.replaceDraft(snapshot)
            // A sessão é gravada depois do rascunho de propósito. Se o aplicativo cair
            // entre as duas gravações, a retomada relê alguns produtos — o inverso
            // marcaria como lido algo que nunca chegou ao disco.
            sessionStore.save(currentSession(now))
            pendingDraftWrites = 0
            lastDraftWriteAt = now
            if (snapshot.isNotEmpty()) {
                preferences.lastCaptureSavedAt = now
                preferences.lastCaptureSavedCount = snapshot.size
                preferences.captureStatusMessage = "Rascunho protegido · ${snapshot.size} produtos"
            } else {
                preferences.captureStatusMessage =
                    "Nenhum item elegível ainda · última coleta válida mantida"
            }
        }.isSuccess
    }

    private fun updateOverlay(title: String, status: String) {
        overlayTitle?.text = "AGILIZA · $title"
        overlayStatus?.text = status
    }

    private fun setCaptureScreenOn(enabled: Boolean) {
        overlay?.keepScreenOn = enabled
    }

    private fun notifyState(message: String, count: Int) {
        sendBroadcast(
            Intent(AppPreferences.ACTION_CAPTURE_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra("message", message)
                .putExtra("count", count)
        )
    }

    private fun mergeProduct(current: QuoteItem?, candidate: QuoteItem): QuoteItem {
        if (current == null) return candidate
        fun choose(existing: String, incoming: String): String = existing.ifBlank { incoming }
        val mergedPrices = current.pricesByDays + candidate.pricesByDays
        val mergedCasePrices = current.casePricesByDays + candidate.casePricesByDays
        val primaryDays = if (7 in mergedCasePrices) 7 else {
            mergedCasePrices.keys.minOrNull() ?: candidate.conditionDays
        }
        return current.copy(
            capturedAt = maxOf(current.capturedAt, candidate.capturedAt),
            conditionDays = primaryDays,
            productCode = choose(current.productCode, candidate.productCode),
            barcode = choose(current.barcode, candidate.barcode),
            description = choose(current.description, candidate.description),
            packaging = choose(current.packaging, candidate.packaging),
            quantity = choose(current.quantity, candidate.quantity),
            unitPrice = mergedPrices[primaryDays].orEmpty(),
            rawCells = (current.rawCells + candidate.rawCells).distinct(),
            pricesByDays = mergedPrices,
            unitsPerCase = choose(current.unitsPerCase, candidate.unitsPerCase),
            stock = candidate.stock.ifBlank { current.stock },
            casePricesByDays = mergedCasePrices,
            salesUnit = choose(current.salesUnit, candidate.salesUnit)
        )
    }

    private fun isOrderHeader(nodes: List<NodeSnapshot>): Boolean =
        nodes.any { normalize(it.text) == "NOVO PEDIDO" } &&
            (nodes.any { normalize(it.text).contains("PLANO DE PAGAMENTO") } ||
                nodes.any { it.viewId.endsWith(":id/linhaPlanoPagamento") })

    private fun isOrderTabScreen(nodes: List<NodeSnapshot>): Boolean =
        nodes.any { normalize(it.text) == "NOVO PEDIDO" } &&
            nodes.any { normalize(it.text) == "TABELA" } &&
            nodes.any { normalize(it.text).startsWith("ITENS") }

    private fun isPaymentPlanList(nodes: List<NodeSnapshot>): Boolean =
        nodes.any { normalize(it.text).contains("SELECIONAR PL PAGAMENTO") } ||
            ALL_CONDITIONS.count { days -> nodes.any { normalize(it.text) == "$days DIAS" } } >= 3

    /**
     * O cabeçalho mostra o plano ativo. Serve para confirmar a troca de prazo sem
     * depender só do clique ter dado certo. Exige limite de palavra para que
     * "28 DIAS" nunca seja reconhecido dentro de "128 DIAS".
     */
    private fun headerShowsPlan(nodes: List<NodeSnapshot>, days: Int): Boolean {
        if (isPaymentPlanList(nodes)) return false
        return PaymentPlanPolicy.matchesAny(nodes.map { normalize(it.text) }, days)
    }

    /**
     * Assinatura só dos produtos visíveis. O fingerprint da tela inteira mudava com o
     * total do pedido e com as imagens carregando, e o fim da lista nunca era aceito.
     */
    private fun listSignature(nodes: List<NodeSnapshot>): String =
        IonProductReader.readList(nodes).joinToString("|") { it.stableKey }

    private fun clickById(root: AccessibilityNodeInfo, idSuffix: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId("${preferences.targetPackage}:id/$idSuffix")
        return try {
            nodes.firstNotNullOfOrNull { clickableCopy(it) }?.let { clickable ->
                try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { clickable.recycle() }
            } == true
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun clickTextExact(root: AccessibilityNodeInfo, target: String): Boolean {
        val normalizedTarget = normalize(target)
        val candidates = root.findAccessibilityNodeInfosByText(target)
        return try {
            candidates.asSequence()
                .filter { normalize(it.text?.toString().orEmpty()) == normalizedTarget }
                .mapNotNull(::clickableCopy)
                .firstOrNull()
                ?.let { clickable ->
                    try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { clickable.recycle() }
                } == true
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    private fun clickProductRow(root: AccessibilityNodeInfo, product: IonProductReader.ListProduct): Boolean {
        val codeNodes = listOf("textViewCodigoProduto", "textViewRowCustom1")
            .flatMap { id ->
                root.findAccessibilityNodeInfosByViewId("${preferences.targetPackage}:id/$id")
            }
        val bounds = Rect()
        return try {
            val code = codeNodes.minByOrNull { node ->
                node.getBoundsInScreen(bounds)
                val textPenalty = if (node.text?.toString()?.trim() == product.productCode) 0 else 100_000
                textPenalty + kotlin.math.abs(bounds.centerY() - product.centerY)
            } ?: return false
            val clickable = clickableCopy(code, maxParents = 6) ?: return false
            try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { clickable.recycle() }
        } finally {
            codeNodes.forEach { it.recycle() }
        }
    }

    private fun clickableCopy(node: AccessibilityNodeInfo, maxParents: Int = 4): AccessibilityNodeInfo? {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(maxParents + 1) {
            if (current.isClickable && current.isEnabled) return current
            val parent = current.parent ?: return null.also { current.recycle() }
            current.recycle()
            current = parent
        }
        current.recycle()
        return null
    }

    private fun normalize(value: String): String = java.text.Normalizer
        .normalize(value.uppercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
        setStroke(dp(1), Color.rgb(64, 224, 178))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private enum class Phase {
        IDLE, OPEN_PLAN, CHOOSE_PLAN, OPEN_TABLE, RESET_LIST, SCAN_LIST, WAIT_DETAIL, READ_DETAIL,
        WAIT_LIST_RETURN, RETURN_HEADER
    }

    companion object {
        /**
         * Todos os prazos que o ION oferece. A coleta pode ler só um deles, mas a
         * tela de planos continua mostrando os quatro — é assim que ela é reconhecida.
         */
        private val ALL_CONDITIONS = AppPreferences.CAPTURE_DAYS
        private const val EVENT_SETTLE_MS = 220L
        private const val ACTION_SETTLE_MS = 900L
        private const val PRODUCT_OPEN_MS = 700L
        private const val DETAIL_SETTLE_MS = 350L
        private const val PRODUCT_RETURN_MS = 650L
        private const val SCROLL_SETTLE_MS = 900L
        private const val SCROLL_LOAD_POLL_MS = 600L
        private const val LIST_STABILITY_MS = 350L
        private const val RETRY_MS = 650L
        private const val MAX_PHASE_ATTEMPTS = 18
        private const val MAX_PAGES_PER_CONDITION = 2_000
        private const val MAX_SCROLL_LOAD_CHECKS = 8
        private const val REQUIRED_STABLE_PAGES = 3
        private const val REQUIRED_OUTSIDE_CHECKS = 2
        private const val VISIBILITY_CHECK_MS = 900L
        private const val MAX_LIST_RESET_SCROLLS = 1_200
        private const val MAX_PLAN_ATTEMPTS = 6
        private const val DRAFT_WRITE_EVERY = 25
        private const val DRAFT_WRITE_INTERVAL_MS = 20_000L
    }
}
