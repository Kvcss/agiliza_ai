package com.agilizaai.cotacao.web

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.agilizaai.cotacao.R
import com.agilizaai.cotacao.data.AppPreferences
import com.agilizaai.cotacao.security.SecureCredentialStore
import java.net.URI

class PortalConfigActivity : Activity() {
    private lateinit var preferences: AppPreferences
    private lateinit var credentials: SecureCredentialStore
    private lateinit var urlField: EditText
    private lateinit var emailField: EditText
    private lateinit var passwordField: EditText
    private lateinit var baseGroup: RadioGroup
    private lateinit var userSelector: EditText
    private lateinit var passwordSelector: EditText
    private lateinit var submitSelector: EditText
    private lateinit var rowSelector: EditText
    private lateinit var barcodeSelector: EditText
    private lateinit var priceSelector: EditText
    private lateinit var advanced: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setStatusBarColor(color(R.color.navy_dark))
        window.decorView.systemUiVisibility = 0
        preferences = AppPreferences(this)
        credentials = SecureCredentialStore(this)
        setContentView(buildContent())
        loadValues()
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.surface)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Configurar portal"
            setTextColor(color(R.color.ink))
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "O acesso fica restrito ao domínio informado. O envio final da cotação permanece manual para você revisar."
            setTextColor(color(R.color.muted))
            textSize = 14f
            setPadding(0, dp(7), 0, dp(18))
        })

        val accessCard = card("Acesso")
        urlField = field("Link HTTPS do portal", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        emailField = field("E-mail ou usuário", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        passwordField = field("Senha", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        accessCard.addView(urlField)
        accessCard.addView(emailField)
        accessCard.addView(passwordField)
        accessCard.addView(TextView(this).apply {
            text = "A senha é protegida por uma chave do Android Keystore e nunca entra no Excel."
            setTextColor(color(R.color.muted))
            textSize = 12f
            setPadding(dp(3), dp(4), dp(3), dp(5))
        })
        root.addView(accessCard)
        root.addView(space(12))

        val baseCard = card("Base de preços do portal")
        baseCard.addView(TextView(this).apply {
            text = "Escolha a coleta que será consultada por código de barras. O portal recebe o preço unitário desse prazo."
            setTextColor(color(R.color.muted))
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        baseGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(7, 14, 21, 28).forEach { days ->
            baseGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = days
                text = "${days}d"
                setTextColor(color(R.color.ink))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
            }, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        baseCard.addView(baseGroup)
        baseCard.addView(TextView(this).apply {
            text = "Esta escolha é exclusiva do portal e não altera a base usada no Excel por caixa."
            setTextColor(color(R.color.muted))
            textSize = 12f
            setPadding(dp(3), dp(7), dp(3), 0)
        })
        root.addView(baseCard)
        root.addView(space(12))

        val mappingCard = card("Mapeamento da página")
        mappingCard.addView(TextView(this).apply {
            text = "Os seletores abaixo serão ajustados ao site que você enviar. Os valores iniciais funcionam em tabelas HTML comuns."
            setTextColor(color(R.color.muted))
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        val advancedButton = textButton("Mostrar configuração avançada") {
            advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        mappingCard.addView(advancedButton)
        advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        userSelector = field("Seletor CSS do usuário")
        passwordSelector = field("Seletor CSS da senha")
        submitSelector = field("Seletor CSS do botão Entrar")
        rowSelector = field("Seletor CSS das linhas da cotação")
        barcodeSelector = field("Seletor CSS do código de barras")
        priceSelector = field("Seletor CSS do campo de preço")
        listOf(userSelector, passwordSelector, submitSelector, rowSelector, barcodeSelector, priceSelector).forEach(advanced::addView)
        mappingCard.addView(advanced)
        root.addView(mappingCard)
        root.addView(space(14))

        root.addView(primaryButton("SALVAR E ABRIR PORTAL") {
            if (save()) startActivity(Intent(this, PortalActivity::class.java))
        })
        root.addView(secondaryButton("SALVAR") { if (save()) finish() })
        root.addView(textButton("Apagar credencial salva") { confirmClearCredentials() })
        root.addView(textButton("Voltar") { finish() })
        return scroll
    }

    private fun loadValues() {
        urlField.setText(preferences.portalUrl)
        emailField.setText(credentials.email())
        userSelector.setText(preferences.loginUserSelector)
        passwordSelector.setText(preferences.loginPasswordSelector)
        submitSelector.setText(preferences.loginSubmitSelector)
        rowSelector.setText(preferences.tableRowSelector)
        barcodeSelector.setText(preferences.barcodeSelector)
        priceSelector.setText(preferences.priceInputSelector)
        val selectedDays = preferences.portalConditionDays.takeIf { it in listOf(7, 14, 21, 28) } ?: 7
        (0 until baseGroup.childCount)
            .mapNotNull { baseGroup.getChildAt(it) as? RadioButton }
            .firstOrNull { it.tag == selectedDays }
            ?.isChecked = true
        if (credentials.password().isNotBlank()) passwordField.hint = "Senha protegida já cadastrada"
    }

    private fun save(): Boolean {
        val url = urlField.text.toString().trim()
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            toast("Informe um link HTTPS válido")
            return false
        }
        if (listOf(userSelector, passwordSelector, submitSelector, rowSelector, barcodeSelector, priceSelector).any { it.text.isBlank() }) {
            toast("Os seletores avançados não podem ficar vazios")
            return false
        }

        val selectedDays = baseGroup.findViewById<RadioButton>(baseGroup.checkedRadioButtonId)?.tag as? Int
        if (selectedDays == null || selectedDays !in listOf(7, 14, 21, 28)) {
            toast("Escolha a base de preços: 7, 14, 21 ou 28 dias")
            return false
        }

        val email = emailField.text.toString().trim()
        val password = passwordField.text.toString()
        val origin = normalizedOrigin(uri)
        val originChanged = credentials.origin().isNotBlank() && credentials.origin() != origin
        if (originChanged && password.isBlank()) {
            toast("O domínio do portal mudou. Digite a senha novamente por segurança.")
            return false
        }

        preferences.portalUrl = url
        preferences.loginUserSelector = userSelector.text.toString()
        preferences.loginPasswordSelector = passwordSelector.text.toString()
        preferences.loginSubmitSelector = submitSelector.text.toString()
        preferences.tableRowSelector = rowSelector.text.toString()
        preferences.barcodeSelector = barcodeSelector.text.toString()
        preferences.priceInputSelector = priceSelector.text.toString()
        preferences.portalConditionDays = selectedDays

        if (password.isNotBlank()) {
            credentials.save(origin, email, password)
        } else if (email != credentials.email() && credentials.password().isNotBlank()) {
            credentials.save(origin, email, credentials.password())
        }
        toast("Configuração salva")
        return true
    }

    private fun confirmClearCredentials() {
        AlertDialog.Builder(this)
            .setTitle("Apagar login salvo?")
            .setMessage("O link e o mapeamento do portal serão mantidos.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                credentials.clear()
                emailField.setText("")
                passwordField.setText("")
                passwordField.hint = "Senha"
                toast("Credencial apagada")
            }
            .show()
    }

    private fun normalizedOrigin(uri: URI): String {
        val port = if (uri.port == -1) 443 else uri.port
        return "https://${uri.host.lowercase()}:$port"
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(color(R.color.card), dp(16).toFloat(), color(R.color.line))
        addView(TextView(this@PortalConfigActivity).apply {
            text = title
            setTextColor(color(R.color.ink))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(9))
        })
    }

    private fun field(hintText: String, input: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
        hint = hintText
        inputType = input
        textSize = 14f
        setTextColor(color(R.color.ink))
        setHintTextColor(color(R.color.muted))
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.WHITE, dp(9).toFloat(), color(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(51)).apply {
            bottomMargin = dp(9)
        }
    }

    private fun primaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color(R.color.blue), dp(12).toFloat())
        minHeight = dp(52)
        setOnClickListener { click() }
    }

    private fun secondaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(color(R.color.blue))
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color(R.color.blue_soft), dp(12).toFloat())
        minHeight = dp(50)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        }
    }

    private fun textButton(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(color(R.color.blue))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(10))
        setOnClickListener { click() }
    }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun color(id: Int): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id) else resources.getColor(id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
