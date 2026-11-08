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
import java.net.URI

class PortalConfigActivity : Activity() {
    private lateinit var preferences: AppPreferences
    private lateinit var urlField: EditText
    private lateinit var baseGroup: RadioGroup
    private lateinit var rowSelector: EditText
    private lateinit var barcodeSelector: EditText
    private lateinit var priceSelector: EditText
    private lateinit var advanced: LinearLayout
    private var cleartextAccepted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setStatusBarColor(color(R.color.navy_dark))
        window.decorView.systemUiVisibility = 0
        preferences = AppPreferences(this)
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
            text = "O acesso fica restrito ao domínio informado. Login, confirmações e envio final permanecem totalmente manuais."
            setTextColor(color(R.color.muted))
            textSize = 14f
            setPadding(0, dp(7), 0, dp(18))
        })

        val accessCard = card("Acesso")
        urlField = field("Link do portal (ex.: http://177.94.206.10:8081/php/vrcotacao/login.php)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        accessCard.addView(urlField)
        accessCard.addView(TextView(this).apply {
            text = "Cole o link da tela de login. O site será exibido dentro do app, mas você deverá digitar as credenciais e tocar em Entrar manualmente."
            setTextColor(color(R.color.muted))
            textSize = 12f
            setPadding(dp(3), dp(4), dp(3), dp(5))
        })
        root.addView(accessCard)
        root.addView(space(12))

        val baseCard = card("Base de preços do portal")
        baseCard.addView(TextView(this).apply {
            text = "Escolha a coleta que será consultada. O portal recebe o preço unitário desse prazo, cruzado por código de barras, código do produto e nome."
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
            text = "Esta escolha é exclusiva do portal e não altera a base unitária escolhida para o Excel."
            setTextColor(color(R.color.muted))
            textSize = 12f
            setPadding(dp(3), dp(7), dp(3), 0)
        })
        root.addView(baseCard)
        root.addView(space(12))

        val mappingCard = card("Mapeamento da página")
        mappingCard.addView(TextView(this).apply {
            text = "O app identifica as colunas Código, Código Barra, Descrição e Custo pelos títulos da tabela. Os seletores abaixo são apenas uma primeira tentativa para portais fora do padrão."
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
        rowSelector = field("Seletor CSS das linhas da cotação")
        barcodeSelector = field("Seletor CSS do código de barras")
        priceSelector = field("Seletor CSS do campo de preço")
        listOf(rowSelector, barcodeSelector, priceSelector).forEach(advanced::addView)
        mappingCard.addView(advanced)
        root.addView(mappingCard)
        root.addView(space(14))

        root.addView(primaryButton("SALVAR E ABRIR PORTAL") {
            attemptSave { startActivity(Intent(this, PortalActivity::class.java)) }
        })
        root.addView(secondaryButton("SALVAR") { attemptSave { finish() } })
        root.addView(textButton("Voltar") { finish() })
        return scroll
    }

    private fun loadValues() {
        urlField.setText(preferences.portalUrl)
        rowSelector.setText(preferences.tableRowSelector)
        barcodeSelector.setText(preferences.barcodeSelector)
        priceSelector.setText(preferences.priceInputSelector)
        val selectedDays = preferences.portalConditionDays.takeIf { it in listOf(7, 14, 21, 28) } ?: 7
        (0 until baseGroup.childCount)
            .mapNotNull { baseGroup.getChildAt(it) as? RadioButton }
            .firstOrNull { it.tag == selectedDays }
            ?.isChecked = true
    }

    /**
     * Um portal interno sem certificado é aceito, mas as credenciais digitadas
     * manualmente no site trafegam em texto. O aviso aparece uma vez por visita.
     */
    private fun attemptSave(onSaved: () -> Unit) {
        val uri = PortalUrl.parse(urlField.text.toString())
        if (uri == null) {
            toast("Informe um link válido começando com http:// ou https://")
            return
        }
        if (PortalUrl.isCleartext(uri) && !cleartextAccepted) {
            AlertDialog.Builder(this)
                .setTitle("Portal sem criptografia")
                .setMessage(
                    "O link ${uri.host} usa http://, sem HTTPS. As credenciais digitadas no site vão trafegar " +
                        "em texto pela rede e podem ser lidos por quem estiver nela.\n\n" +
                        "Use apenas na rede da empresa. Se o portal tiver um endereço https://, prefira ele."
                )
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Entendi, continuar") { _, _ ->
                    cleartextAccepted = true
                    attemptSave(onSaved)
                }
                .show()
            return
        }
        if (save()) onSaved()
    }

    private fun save(): Boolean {
        val url = urlField.text.toString().trim()
        if (listOf(rowSelector, barcodeSelector, priceSelector).any { it.text.isBlank() }) {
            toast("Os seletores avançados não podem ficar vazios")
            return false
        }

        val selectedDays = baseGroup.findViewById<RadioButton>(baseGroup.checkedRadioButtonId)?.tag as? Int
        if (selectedDays == null || selectedDays !in listOf(7, 14, 21, 28)) {
            toast("Escolha a base de preços: 7, 14, 21 ou 28 dias")
            return false
        }

        preferences.portalUrl = url
        preferences.tableRowSelector = rowSelector.text.toString()
        preferences.barcodeSelector = barcodeSelector.text.toString()
        preferences.priceInputSelector = priceSelector.text.toString()
        preferences.portalConditionDays = selectedDays

        toast("Configuração salva")
        return true
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
