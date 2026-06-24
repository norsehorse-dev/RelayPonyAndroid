package com.relaypony.android.ui

/**
 * Locale codes that have a complete translation shipped and are therefore selectable in the
 * language pickers. Codes here must match the LANGUAGES list (code -> native name) and
 * res/xml/locales_config.xml. All seven shipped locales are now live.
 */
object Locales {
    val LIVE = setOf("en", "hi", "es", "de", "fr", "ja", "pt-BR")
}
