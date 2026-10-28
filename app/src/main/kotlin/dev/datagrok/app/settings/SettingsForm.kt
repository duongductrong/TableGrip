package dev.datagrok.app.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.core.settings.Settings

/**
 * Edits settings live: a choice applies at once, a typed number as soon as it is valid. Invalid text stays in its field
 * with an error and never reaches the app, which keeps its last good value.
 */
class SettingsForm(private val current: () -> Settings, private val update: (Settings) -> Unit) {
    private val texts = mutableMapOf<IntField, TextFieldState>()

    /** Fields whose text can't be applied, with why. */
    var errors by mutableStateOf<Map<SettingField, String>>(emptyMap())
        private set

    fun text(field: IntField): TextFieldState = texts.getOrPut(field) { TextFieldState(field.get(current()).toString()) }

    fun <T> choose(field: ChoiceField<T>, value: T) = update(field.set(current(), value))

    /** Applies [field]'s text if it is valid; called whenever it changes. */
    fun commit(field: IntField) {
        val text = text(field).text.toString()
        val error = field.error(text)
        errors = if (error == null) errors - field else errors + (field to error)
        if (error == null) {
            val value = text.trim().toInt()
            if (field.get(current()) != value) update(field.set(current(), value))
        }
    }

    /** Puts [page]'s settings back to their defaults. */
    fun reset(page: SettingsPage) {
        val defaults = Settings()
        page.fields.forEach { field ->
            when (field) {
                is ChoiceField<*> -> resetChoice(field, defaults)
                is IntField -> {
                    text(field).setTextAndPlaceCursorAtEnd(field.get(defaults).toString())
                    commit(field)
                }
            }
        }
    }

    private fun <T> resetChoice(field: ChoiceField<T>, defaults: Settings) = choose(field, field.get(defaults))
}
