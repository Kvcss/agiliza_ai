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
    private lateinit var windowManager: WindowManager

    private var overlay: View? = null
    private var overlayTitle: TextView? = null
    private var overlayStatus: TextView? = null
    private var overlayAction: TextView? = null

    private var scanning = false
    private var paused = false
    private var phase = Phase.IDLE
    private var phaseAttempts = 0
    private var conditionIndex = 0
    private var pageCount = 0
    private var unchangedPages = 0
    private var lastFingerprint = ""
    private var pendingScrollFingerprint: String? = null
    private var scrollLoadChecks = 0
    private var currentProduct: IonProductReader.ListProduct? = null
    private var outsideTargetChecks = 0
    private val visitedInCondition = linkedSetOf<String>()
    private val conditionCounts = linkedMapOf<Int, Int>()
    private val capturedItems = LinkedHashMap<String, QuoteItem>()
    private val capturedIdentities = LinkedHashMap<String, IonProductReader.VariantIdentity>()

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
                else -> refreshOverlayVisibility()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = AppPreferences(this)
        repository = QuoteRepository(this)
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
        if (shouldShow) showOverlay() else if (!scanning) removeOverlay()
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
            text = "No cabeçalho do Novo Pedido, toque abaixo"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), dp(7))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        overlayAction = actionText("CAPTURAR 4 PRAZOS") {
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

        val storageReady = runCatching { repository.beginNewCapture() }.isSuccess
        if (!storageReady) {
            overlayStatus?.text = "Não consegui preparar o salvamento automático"
            Toast.makeText(this, "Falha ao preparar o armazenamento da coleta", Toast.LENGTH_LONG).show()
            return
        }

        scanning = true
        paused = false
        phase = Phase.OPEN_PLAN
        phaseAttempts = 0
        conditionIndex = 0
        pageCount = 0
        unchangedPages = 0
        lastFingerprint = ""
        pendingScrollFingerprint = null
        scrollLoadChecks = 0
        currentProduct = null
        visitedInCondition.clear()
        conditionCounts.clear()
        capturedItems.clear()
        capturedIdentities.clear()
        preferences.captureStatus = AppPreferences.CAPTURE_IN_PROGRESS
        preferences.captureStatusMessage = "Capturando as tabelas de 7, 14, 21 e 28 dias"
        overlayAction?.text = "PAUSAR"
        setCaptureScreenOn(true)
        updateOverlay("Preparando 7 dias", "0 produtos")
        scheduleAdvance(200L)
    }

    private fun togglePause() {
        if (!scanning) return
        paused = !paused
        if (paused) {
            handler.removeCallbacks(advanceRunnable)
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
            // ION Vendas 1.26.7 exposes the selected plan itself as the
            // clickable control. Older versions expose one of the row IDs.
            clickById(root, "buttonSelecionarPlPag") ||
                clickById(root, "imageButtonConfiguracoesPlanoPagamento") ||
                clickById(root, "linhaPlanoPagamento") ||
                clickById(root, "linhaDadosPlanoPagamento") ||
                clickTextExact(root, "Plano de pagamento") -> {
                updateOverlay("Selecionando ${currentDays()} dias", progressText())
                moveTo(Phase.CHOOSE_PLAN, ACTION_SETTLE_MS)
            }
            else -> retryOrAbort("Não encontrei Plano de pagamento")
        }
    }

    private fun choosePaymentPlan(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        if (isOrderHeader(nodes)) {
            moveTo(Phase.OPEN_TABLE)
            return
        }
        if (!isPaymentPlanList(nodes)) {
            retryOrAbort("A lista de planos não abriu")
            return
        }
        val label = "${currentDays()} DIAS"
        if (clickTextExact(root, label)) {
            updateOverlay("Plano $label selecionado", progressText())
            moveTo(Phase.OPEN_TABLE, ACTION_SETTLE_MS)
        } else {
            retryOrAbort("Não encontrei o plano $label")
        }
    }

    private fun openProductTable(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        when {
            IonProductReader.isProductList(nodes) -> moveTo(Phase.SCAN_LIST)
            isOrderTabScreen(nodes) && clickTextExact(root, "TABELA") -> {
                updateOverlay("Abrindo tabela de ${currentDays()} dias", progressText())
                moveTo(Phase.SCAN_LIST, ACTION_SETTLE_MS)
            }
            else -> retryOrAbort("Não encontrei a aba TABELA")
        }
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
        val next = products.firstOrNull { it.stableKey !in visitedInCondition }
        if (next != null) {
            pendingScrollFingerprint = null
            scrollLoadChecks = 0
            unchangedPages = 0
            currentProduct = next
            if (clickProductRow(root, next)) {
                updateOverlay("Lendo ${currentDays()} dias", "${visitedInCondition.size} lidos · ${next.description.take(28)}")
                moveTo(Phase.WAIT_DETAIL, PRODUCT_OPEN_MS)
            } else {
                visitedInCondition += next.stableKey
                retryOrAbort("Não consegui abrir ${next.productCode}")
            }
            return
        }

        val fingerprint = ScreenScanner.fingerprint(nodes)
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
        val compatibleKey = capturedIdentities.entries
            .asSequence()
            .filter { (_, existingIdentity) -> existingIdentity.isCompatibleWith(identity) }
            .map { it.key }
            .singleOrNull()
        val key = when {
            capturedItems.containsKey(exactKey) -> exactKey
            compatibleKey != null -> compatibleKey
            else -> exactKey
        }
        capturedItems[key] = mergeProduct(capturedItems[key], item)
        capturedIdentities[key] = capturedIdentities[key]?.mergedWith(identity) ?: identity
        if (!persistCurrentDraft()) {
            abortCapture("Não consegui salvar o rascunho no aparelho", AppPreferences.CAPTURE_ABORTED)
            return
        }
        visitedInCondition += listProduct.stableKey
        currentProduct = null
        conditionCounts[currentDays()] = visitedInCondition.size
        updateOverlay("${currentDays()} dias · produto gravado", progressText())

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
        if (conditionIndex == CONDITIONS.lastIndex) {
            finishCapture()
            return
        }
        conditionIndex++
        visitedInCondition.clear()
        currentProduct = null
        pageCount = 0
        unchangedPages = 0
        lastFingerprint = ""
        pendingScrollFingerprint = null
        scrollLoadChecks = 0
        updateOverlay("${CONDITIONS[conditionIndex - 1]} dias concluído", "Próximo: ${currentDays()} dias")
        moveTo(Phase.RETURN_HEADER, 250L)
    }

    private fun returnToHeader(root: AccessibilityNodeInfo, nodes: List<NodeSnapshot>) {
        when {
            isOrderHeader(nodes) -> moveTo(Phase.OPEN_PLAN)
            clickTextExact(root, "CABEÇALHO") -> {
                updateOverlay("Voltando ao cabeçalho", "Preparando ${currentDays()} dias")
                moveTo(Phase.OPEN_PLAN, ACTION_SETTLE_MS)
            }
            else -> retryOrAbort("Não encontrei a aba CABEÇALHO")
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
        val completeConditions = CONDITIONS.all { conditionCounts.containsKey(it) }
        val result = runCatching {
            check(items.isNotEmpty()) { "Nenhum produto foi reconhecido" }
            check(completeConditions) { "Nem todos os prazos foram concluídos" }
            repository.replace(items)
            preferences.lastCaptureSavedAt = System.currentTimeMillis()
            preferences.lastCaptureSavedCount = items.size
        }
        val message = result.fold(
            onSuccess = { "${items.size} produtos · 4 prazos concluídos" },
            onFailure = {
                val reason = it.message ?: "Não foi possível finalizar a captura"
                "$reason · rascunho automático mantido"
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
        if (hadCurrentItems) persistCurrentDraft()
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
        if (capturedItems.isNotEmpty()) persistCurrentDraft()
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

    private fun currentDays(): Int = CONDITIONS[conditionIndex]

    private fun progressText(): String {
        val terms = CONDITIONS.joinToString(" ") { days ->
            if (days == currentDays()) "[$days]" else days.toString()
        }
        val saved = if (capturedItems.isNotEmpty()) " · ✓ salvo" else ""
        return "$terms · ${visitedInCondition.size} neste prazo · ${capturedItems.size} produtos$saved"
    }

    private fun persistCurrentDraft(): Boolean {
        if (capturedItems.isEmpty()) return true
        val snapshot = capturedItems.values.toList()
        return runCatching {
            repository.replaceDraft(snapshot)
            preferences.lastCaptureSavedAt = System.currentTimeMillis()
            preferences.lastCaptureSavedCount = snapshot.size
            preferences.captureStatusMessage = "Rascunho protegido · ${snapshot.size} produtos"
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
        val primaryDays = if (7 in mergedPrices) 7 else mergedPrices.keys.minOrNull() ?: candidate.conditionDays
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
            unitsPerCase = choose(current.unitsPerCase, candidate.unitsPerCase)
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
            CONDITIONS.count { days -> nodes.any { normalize(it.text) == "$days DIAS" } } >= 3

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
        IDLE, OPEN_PLAN, CHOOSE_PLAN, OPEN_TABLE, SCAN_LIST, WAIT_DETAIL, READ_DETAIL,
        WAIT_LIST_RETURN, RETURN_HEADER
    }

    companion object {
        private val CONDITIONS = listOf(7, 14, 21, 28)
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
    }
}
