package com.agilizaai.cotacao.data

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init {
        // O fornecedor é uma regra fixa do produto. Remove qualquer configuração
        // legada para que nomes antigos nunca reapareçam na interface.
        if (prefs.contains(KEY_LEGACY_PRICE_PROFILE_NAME)) {
            prefs.edit().remove(KEY_LEGACY_PRICE_PROFILE_NAME).apply()
        }
    }

    var targetPackage: String
        get() = prefs.getString(KEY_TARGET_PACKAGE, DEFAULT_ION_PACKAGE).orEmpty()
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGE, value.trim()).apply()

    var conditionDays: Int
        get() = prefs.getInt(KEY_CONDITION_DAYS, 7)
        set(value) = prefs.edit().putInt(KEY_CONDITION_DAYS, value).apply()

    var excelConditionDays: Int
        get() {
            if (prefs.contains(KEY_EXCEL_CONDITION_DAYS)) {
                return prefs.getInt(KEY_EXCEL_CONDITION_DAYS, DEFAULT_CONDITION_DAYS)
                    .takeIf { it in SUPPORTED_CONDITION_DAYS }
                    ?: DEFAULT_CONDITION_DAYS
            }
            val migratedDays = prefs.getInt(KEY_CONDITION_DAYS, DEFAULT_CONDITION_DAYS)
                .takeIf { it in SUPPORTED_CONDITION_DAYS }
                ?: DEFAULT_CONDITION_DAYS
            prefs.edit().putInt(KEY_EXCEL_CONDITION_DAYS, migratedDays).apply()
            return migratedDays
        }
        set(value) {
            require(value in SUPPORTED_CONDITION_DAYS) { "Prazo do Excel sem suporte: $value" }
            prefs.edit().putInt(KEY_EXCEL_CONDITION_DAYS, value).apply()
        }

    var portalConditionDays: Int
        get() {
            if (prefs.contains(KEY_PORTAL_CONDITION_DAYS)) {
                return prefs.getInt(KEY_PORTAL_CONDITION_DAYS, DEFAULT_CONDITION_DAYS)
                    .takeIf { it in SUPPORTED_CONDITION_DAYS }
                    ?: DEFAULT_CONDITION_DAYS
            }
            val migratedDays = prefs.getInt(KEY_CONDITION_DAYS, DEFAULT_CONDITION_DAYS)
                .takeIf { it in SUPPORTED_CONDITION_DAYS }
                ?: DEFAULT_CONDITION_DAYS
            prefs.edit().putInt(KEY_PORTAL_CONDITION_DAYS, migratedDays).apply()
            return migratedDays
        }
        set(value) {
            require(value in SUPPORTED_CONDITION_DAYS) { "Prazo do portal sem suporte: $value" }
            prefs.edit().putInt(KEY_PORTAL_CONDITION_DAYS, value).apply()
        }

    var captureArmed: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_CAPTURE_ARMED, value).apply()

    var captureStatus: String
        get() = prefs.getString(KEY_CAPTURE_STATUS, CAPTURE_IDLE).orEmpty()
        set(value) = prefs.edit().putString(KEY_CAPTURE_STATUS, value).apply()

    var captureStatusMessage: String
        get() = prefs.getString(KEY_CAPTURE_STATUS_MESSAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CAPTURE_STATUS_MESSAGE, value).apply()

    var lastCaptureSavedAt: Long
        get() = prefs.getLong(KEY_LAST_CAPTURE_SAVED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CAPTURE_SAVED_AT, value).apply()

    var lastCaptureSavedCount: Int
        get() = prefs.getInt(KEY_LAST_CAPTURE_SAVED_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_CAPTURE_SAVED_COUNT, value).apply()

    var accessibilityDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, value).apply()

    var lastSeenPackage: String
        get() = prefs.getString(KEY_LAST_SEEN_PACKAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_SEEN_PACKAGE, value).apply()

    var portalUrl: String
        get() = prefs.getString(KEY_PORTAL_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PORTAL_URL, value.trim()).apply()

    var loginUserSelector: String
        get() = prefs.getString(KEY_USER_SELECTOR, "input[type=email], input[name*=email i], input[name*=user i]")!!
        set(value) = prefs.edit().putString(KEY_USER_SELECTOR, value.trim()).apply()

    var loginPasswordSelector: String
        get() = prefs.getString(KEY_PASSWORD_SELECTOR, "input[type=password]")!!
        set(value) = prefs.edit().putString(KEY_PASSWORD_SELECTOR, value.trim()).apply()

    var loginSubmitSelector: String
        get() = prefs.getString(KEY_SUBMIT_SELECTOR, "button[type=submit], input[type=submit]")!!
        set(value) = prefs.edit().putString(KEY_SUBMIT_SELECTOR, value.trim()).apply()

    var tableRowSelector: String
        get() = prefs.getString(KEY_ROW_SELECTOR, "table tbody tr")!!
        set(value) = prefs.edit().putString(KEY_ROW_SELECTOR, value.trim()).apply()

    var barcodeSelector: String
        get() = prefs.getString(KEY_BARCODE_SELECTOR, "[data-barcode], .barcode, td, [role=cell]")!!
        set(value) = prefs.edit().putString(KEY_BARCODE_SELECTOR, value.trim()).apply()

    var priceInputSelector: String
        get() = prefs.getString(KEY_PRICE_SELECTOR, "input[type=number], input[name*=preco i], input[name*=price i]")!!
        set(value) = prefs.edit().putString(KEY_PRICE_SELECTOR, value.trim()).apply()

    companion object {
        const val DEFAULT_ION_PACKAGE = "br.com.ionsistemas.ionvendas"
        const val ACTION_CAPTURE_STATE_CHANGED = "com.agilizaai.cotacao.CAPTURE_STATE_CHANGED"
        const val ACTION_CAPTURE_COMMAND = "com.agilizaai.cotacao.CAPTURE_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val COMMAND_REFRESH = "refresh"
        const val COMMAND_STOP = "stop"
        const val CAPTURE_IDLE = "idle"
        const val CAPTURE_IN_PROGRESS = "in_progress"
        const val CAPTURE_COMPLETE = "complete"
        const val CAPTURE_ABORTED = "aborted"
        const val CAPTURE_TRUNCATED = "truncated"

        private const val FILE_NAME = "agiliza_preferences"
        private const val DEFAULT_CONDITION_DAYS = 7
        private val SUPPORTED_CONDITION_DAYS = setOf(7, 14, 21, 28)
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_CONDITION_DAYS = "condition_days"
        private const val KEY_EXCEL_CONDITION_DAYS = "excel_condition_days"
        private const val KEY_PORTAL_CONDITION_DAYS = "portal_condition_days"
        private const val KEY_LEGACY_PRICE_PROFILE_NAME = "price_profile_name"
        private const val KEY_CAPTURE_ARMED = "capture_armed"
        private const val KEY_CAPTURE_STATUS = "capture_status"
        private const val KEY_CAPTURE_STATUS_MESSAGE = "capture_status_message"
        private const val KEY_LAST_CAPTURE_SAVED_AT = "last_capture_saved_at"
        private const val KEY_LAST_CAPTURE_SAVED_COUNT = "last_capture_saved_count"
        private const val KEY_DISCLOSURE_ACCEPTED = "accessibility_disclosure_accepted"
        private const val KEY_LAST_SEEN_PACKAGE = "last_seen_package"
        private const val KEY_PORTAL_URL = "portal_url"
        private const val KEY_USER_SELECTOR = "portal_user_selector"
        private const val KEY_PASSWORD_SELECTOR = "portal_password_selector"
        private const val KEY_SUBMIT_SELECTOR = "portal_submit_selector"
        private const val KEY_ROW_SELECTOR = "portal_row_selector"
        private const val KEY_BARCODE_SELECTOR = "portal_barcode_selector"
        private const val KEY_PRICE_SELECTOR = "portal_price_selector"
    }
}
