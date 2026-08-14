package com.agilizaai.cotacao.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.agilizaai.cotacao.R
import com.agilizaai.cotacao.data.AppPreferences
import com.agilizaai.cotacao.data.QuoteRepository
import com.agilizaai.cotacao.security.SecureCredentialStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class PortalActivity : Activity() {
    private lateinit var preferences: AppPreferences
    private lateinit var credentials: SecureCredentialStore
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var allowedHost = ""
    private var allowedPort = 443
    private var shuttingDown = false
    private var automaticLoginAttempted = false
    private var automaticFillAttempted = false
    private var automaticProbeAttempts = 0
    private var lastAutomaticRowCount = -1
    private var stableAutomaticTableReads = 0
    private val automaticProbe = Runnable { probeAutomaticFlow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        credentials = SecureCredentialStore(this)
        val uri = runCatching { URI(preferences.portalUrl) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Configure um link HTTPS antes de abrir o portal", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        allowedHost = uri.host.lowercase()
        allowedPort = if (uri.port == -1) 443 else uri.port
        setContentView(buildContent())
        configureWebView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::handleBack
            )
        }
        webView.loadUrl(preferences.portalUrl)
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        shuttingDown = true
        if (::webView.isInitialized) {
            webView.removeCallbacks(automaticProbe)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(10))
            setBackgroundColor(color(R.color.navy))
        }
        header.addView(TextView(this).apply {
            text = "Portal de cotação"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        status = TextView(this).apply {
            text = "Abrindo conexão segura · base ${preferences.portalConditionDays} dias…"
            setTextColor(Color.rgb(205, 217, 236))
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        }
        header.addView(status)
        root.addView(header)

        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        actions.addView(actionButton("ENTRAR") { login() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        actions.addView(actionButton("PREENCHER") { fillPrices() }, LinearLayout.LayoutParams(0, dp(48), 1.25f).apply { marginStart = dp(5) })
        root.addView(actions)
        return root
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                return if (isAllowed(url)) {
                    false
                } else {
                    status.text = "Navegação bloqueada fora de $allowedHost"
                    toast("Link externo bloqueado por segurança")
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val current = Uri.parse(url ?: "")
                if (!isAllowed(current)) return
                status.text = "Conectado a $allowedHost · base ${preferences.portalConditionDays} dias · envio manual"
                startAutomaticFlow()
            }

            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: SafeBrowsingResponse?
            ) {
                callback?.backToSafety(true)
                status.text = "Página bloqueada pela Navegação Segura"
            }
        }
    }

    private fun isAllowed(uri: Uri): Boolean {
        val port = if (uri.port == -1) 443 else uri.port
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(allowedHost, ignoreCase = true) && port == allowedPort && uri.userInfo.isNullOrBlank()
    }

    private fun startAutomaticFlow() {
        if (shuttingDown || automaticFillAttempted) return
        webView.removeCallbacks(automaticProbe)
        automaticProbeAttempts = 0
        lastAutomaticRowCount = -1
        stableAutomaticTableReads = 0
        scheduleAutomaticProbe(INITIAL_AUTOMATIC_PROBE_DELAY_MS)
    }

    private fun scheduleAutomaticProbe(delayMs: Long) {
        if (shuttingDown || automaticFillAttempted || automaticProbeAttempts >= MAX_AUTOMATIC_PROBES) return
        webView.removeCallbacks(automaticProbe)
        webView.postDelayed(automaticProbe, delayMs)
    }

    /** Detects only login fields and quote rows. It never looks for or clicks a quote Save/Send button. */
    private fun probeAutomaticFlow() {
        if (shuttingDown || automaticFillAttempted) return
        val current = Uri.parse(webView.url ?: "")
        if (!isAllowed(current)) return
        automaticProbeAttempts++
        val script = """
            (() => {
              const one = (selector, root = document) => { try { return root.querySelector(selector); } catch (_) { return null; } };
              const user = one(${json(preferences.loginUserSelector)});
              const pass = one(${json(preferences.loginPasswordSelector)});
              const loginScope = pass?.form || pass?.closest('form, [role="form"], [class*="login" i], [id*="login" i]');
              const submit = loginScope ? one(${json(preferences.loginSubmitSelector)}, loginScope) : null;
              const visible = (element) => {
                if (!element || !element.isConnected || element.disabled) return false;
                const style = getComputedStyle(element);
                const rect = element.getBoundingClientRect();
                return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
              };
              let rows;
              try { rows = Array.from(document.querySelectorAll(${json(preferences.tableRowSelector)})); }
              catch (_) { return JSON.stringify({ok:false, message:'Seletor de linhas inválido'}); }
              let priceInputs = 0;
              for (const row of rows) {
                if (one(${json(preferences.priceInputSelector)}, row) || one(${json(COST_INPUT_FALLBACK_SELECTOR)}, row)) priceInputs++;
              }
              const loginReady = Boolean(loginScope && loginScope.contains(user) && visible(user) && visible(pass) && visible(submit) && priceInputs === 0);
              return JSON.stringify({ok:true, loginReady, rows:rows.length, priceInputs});
            })();
        """.trimIndent()
        evaluate(script) { result ->
            if (shuttingDown || automaticFillAttempted) return@evaluate
            if (!result.optBoolean("ok")) {
                status.text = result.optString("message", "Não foi possível detectar a tabela")
                return@evaluate
            }
            if (result.optBoolean("loginReady")) {
                if (!automaticLoginAttempted) {
                    automaticLoginAttempted = true
                    status.text = "Login detectado · entrando automaticamente…"
                    login(automatic = true)
                } else if (automaticProbeAttempts < MAX_AUTOMATIC_PROBES) {
                    status.text = "Aguardando o portal concluir o login…"
                    scheduleAutomaticProbe(AUTOMATIC_PROBE_DELAY_MS)
                } else {
                    status.text = "O login automático não avançou · confira os dados e use ENTRAR"
                }
                return@evaluate
            }

            val rowCount = result.optInt("rows")
            val priceInputCount = result.optInt("priceInputs")
            if (rowCount > 0 && priceInputCount > 0) {
                stableAutomaticTableReads = if (lastAutomaticRowCount == rowCount) {
                    stableAutomaticTableReads + 1
                } else {
                    0
                }
                lastAutomaticRowCount = rowCount
                if (stableAutomaticTableReads >= REQUIRED_STABLE_TABLE_READS) {
                    automaticFillAttempted = true
                    status.text = "Tabela detectada · preenchendo automaticamente com a base ${preferences.portalConditionDays} dias…"
                    fillPrices(automatic = true)
                } else {
                    status.text = "Tabela detectada · aguardando terminar de carregar…"
                    scheduleAutomaticProbe(AUTOMATIC_PROBE_DELAY_MS)
                }
                return@evaluate
            }

            if (automaticProbeAttempts < MAX_AUTOMATIC_PROBES) {
                status.text = "Portal aberto · procurando a tabela da cotação…"
                scheduleAutomaticProbe(AUTOMATIC_PROBE_DELAY_MS)
            } else {
                status.text = "Tabela não detectada automaticamente · use PREENCHER quando ela estiver visível"
            }
        }
    }

    private fun login(automatic: Boolean = false) {
        if (!isCurrentPageAllowed()) return
        val email = credentials.email()
        val password = credentials.password()
        val expectedOrigin = "https://$allowedHost:$allowedPort"
        if (credentials.origin() != expectedOrigin || email.isBlank() || password.isBlank()) {
            val message = "Cadastre o usuário e a senha na configuração do portal"
            status.text = message
            if (!automatic) toast(message)
            return
        }
        val script = """
            (() => {
              const pick = (selector, root = document) => { try { return root.querySelector(selector); } catch (_) { return null; } };
              const user = pick(${json(preferences.loginUserSelector)});
              const pass = pick(${json(preferences.loginPasswordSelector)});
              const loginScope = pass?.form || pass?.closest('form, [role="form"], [class*="login" i], [id*="login" i]');
              const submit = loginScope ? pick(${json(preferences.loginSubmitSelector)}, loginScope) : null;
              const visible = (element) => {
                if (!element || !element.isConnected || element.disabled) return false;
                const style = getComputedStyle(element);
                const rect = element.getBoundingClientRect();
                return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
              };
              if (!loginScope || !loginScope.contains(user) || !visible(user) || !visible(pass) || !visible(submit)) {
                return JSON.stringify({ok:false, message:'Formulário seguro de login não encontrado'});
              }
              const setValue = (element, value) => {
                const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(element, value); else element.value = value;
                element.dispatchEvent(new Event('input', {bubbles:true}));
                element.dispatchEvent(new Event('change', {bubbles:true}));
              };
              setValue(user, ${json(email)});
              setValue(pass, ${json(password)});
              submit.click();
              return JSON.stringify({ok:true, message:'Login acionado'});
            })();
        """.trimIndent()
        evaluate(script) { result ->
            status.text = result.optString("message", "Login processado")
            if (!result.optBoolean("ok") && !automatic) toast(status.text.toString())
            if (automatic && result.optBoolean("ok")) scheduleAutomaticProbe(AUTOMATIC_PROBE_DELAY_MS)
        }
    }

    private fun fillPrices(automatic: Boolean = false) {
        if (!isCurrentPageAllowed()) return
        val selectedDays = preferences.portalConditionDays
        val partialCapture = preferences.captureStatus != AppPreferences.CAPTURE_COMPLETE
        val priceIndex = PortalPriceIndex.build(QuoteRepository(this).load(), selectedDays)
        if (priceIndex.entries.isEmpty()) {
            val conflicts = priceIndex.conflictingBarcodes.size
            val suffix = if (conflicts > 0) "; $conflicts EAN(s) possuem preços conflitantes" else ""
            val message = "Nenhum item seguro com código de barras e preço real de $selectedDays dias foi capturado$suffix"
            status.text = message
            toast(message)
            return
        }
        val payload = JSONArray().apply {
            priceIndex.entries.forEach { entry ->
                put(JSONObject().put("barcode", entry.barcode).put("price", entry.price))
            }
        }
        val conflictPayload = JSONArray(priceIndex.conflictingBarcodes.toList())
        val script = """
            (async () => {
              const items = $payload;
              const prices = new Map(items.map(item => [String(item.barcode).replace(/\D/g, ''), String(item.price)]));
              const conflictingBarcodes = new Set($conflictPayload);
              let rows;
              try { rows = Array.from(document.querySelectorAll(${json(preferences.tableRowSelector)})); }
              catch (_) { return JSON.stringify({ok:false, message:'Seletor de linhas inválido'}); }
              let attempted = 0, confirmed = 0, withoutCode = 0, withoutInput = 0, ambiguous = 0, priceConflict = 0;
              const setValue = (element, value) => {
                const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(element, value); else element.value = value;
                element.dispatchEvent(new Event('input', {bubbles:true}));
                element.dispatchEvent(new Event('change', {bubbles:true}));
                element.style.outline = '3px solid #12805C';
                element.style.outlineOffset = '2px';
              };
              for (const row of rows) {
                let barcodeAreas;
                try { barcodeAreas = Array.from(row.querySelectorAll(${json(preferences.barcodeSelector)})); } catch (_) { barcodeAreas = []; }
                const source = barcodeAreas.length
                  ? barcodeAreas.map(area => area?.dataset?.barcode || area?.textContent || '').join(' ')
                  : (row.textContent || '');
                const rowBarcodes = Array.from(new Set((source.match(/\d{8,14}/g) || []).map(value => value.replace(/\D/g, ''))));
                if (rowBarcodes.some(code => conflictingBarcodes.has(code))) { priceConflict++; continue; }
                const candidates = rowBarcodes.filter(code => prices.has(code));
                if (candidates.length === 0) { withoutCode++; continue; }
                if (candidates.length > 1) { ambiguous++; continue; }
                let input;
                try { input = row.querySelector(${json(preferences.priceInputSelector)}); } catch (_) { input = null; }
                if (!input) {
                  try { input = row.querySelector(${json(COST_INPUT_FALLBACK_SELECTOR)}); } catch (_) { input = null; }
                }
                if (!input) { withoutInput++; continue; }
                const raw = prices.get(candidates[0]);
                const value = String(input.type).toLowerCase() === 'number' ? raw : raw.replace('.', ',');
                setValue(input, value);
                attempted++;
                await new Promise(resolve => setTimeout(resolve, 0));
                const actual = String(input.value || '').trim().replace(/[^0-9,.-]/g, '').replace(',', '.');
                const expected = String(raw).replace(',', '.');
                if (Number.isFinite(Number(actual)) && Math.abs(Number(actual) - Number(expected)) < 0.005) {
                  confirmed++;
                } else {
                  input.style.outline = '3px solid #B45309';
                }
              }
              return JSON.stringify({ok:true, attempted, confirmed, rows:rows.length, withoutCode, withoutInput, ambiguous, priceConflict});
            })();
        """.trimIndent()
        evaluate(script) { result ->
            if (!result.optBoolean("ok")) {
                val message = result.optString("message", "Não foi possível mapear a página")
                status.text = message
                toast(message)
                return@evaluate
            }
            val attempted = result.optInt("attempted")
            val filled = result.optInt("confirmed")
            val total = result.optInt("rows")
            val conflictRows = result.optInt("priceConflict")
            val conflictBarcodes = priceIndex.conflictingBarcodes.size
            val skipped = result.optInt("withoutCode") + result.optInt("withoutInput") +
                result.optInt("ambiguous") + conflictRows
            val partialLabel = if (partialCapture) " · base parcial" else ""
            status.text = "$filled confirmadas de $attempted tentativas · $skipped pendentes · $conflictBarcodes EAN(s) conflitantes$partialLabel"
            val partialWarning = if (partialCapture) {
                " A base selecionada é uma coleta parcial; produtos ainda não capturados permaneceram em branco."
            } else {
                ""
            }
            AlertDialog.Builder(this)
                .setTitle(if (automatic) "Preenchimento automático concluído" else "Preenchimento concluído")
                .setMessage("$filled de $attempted alterações foram confirmadas em $total linhas. $skipped linhas ficaram pendentes. $conflictBarcodes EAN(s) com preços divergentes foram bloqueados com segurança ($conflictRows linha(s) da página).$partialWarning Campos confirmados ficam verdes; campos rejeitados ficam laranja. Revise tudo e envie manualmente no portal.")
                .setPositiveButton("Revisar", null)
                .show()
        }
    }

    private fun isCurrentPageAllowed(): Boolean {
        val current = Uri.parse(webView.url ?: "")
        if (isAllowed(current)) return true
        toast("A página atual não pertence ao domínio autorizado")
        return false
    }

    private fun evaluate(script: String, callback: (JSONObject) -> Unit) {
        webView.evaluateJavascript(script) { encoded ->
            val parsed = runCatching {
                val decoded = JSONArray("[$encoded]").optString(0)
                JSONObject(decoded)
            }.getOrElse { JSONObject().put("ok", false).put("message", "Resposta inesperada da página") }
            callback(parsed)
        }
    }

    private fun json(value: String): String = JSONObject.quote(value)

    private fun actionButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundColor(color(R.color.blue))
        setOnClickListener { click() }
    }

    private fun color(id: Int): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val INITIAL_AUTOMATIC_PROBE_DELAY_MS = 300L
        private const val AUTOMATIC_PROBE_DELAY_MS = 750L
        private const val MAX_AUTOMATIC_PROBES = 24
        private const val REQUIRED_STABLE_TABLE_READS = 1
        private const val COST_INPUT_FALLBACK_SELECTOR = "input[name*=custo i], input[id*=custo i]"
    }
}
