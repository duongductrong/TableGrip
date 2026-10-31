package dev.datagrok.app.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.ui.parseHexColor
import dev.datagrok.app.ui.toHex
import dev.datagrok.core.settings.Settings

/**
 * Edits settings live: a choice applies at once, a typed number as soon as it is valid. Invalid text stays in its field
 * with an error and never reaches the app, which keeps its last good value.
 */
class SettingsForm(private val current: () -> Settings, private val update: (Settings) -> Unit) {
    private val texts = mutableMapOf<SettingField, TextFieldState>()

    /** Fields whose text can't be applied, with why. */
    var errors by mutableStateOf<Map<SettingField, String>>(emptyMap())
        private set

    fun text(field: IntField): TextFieldState = texts.getOrPut(field) { TextFieldState(field.get(current()).toString()) }

    fun text(field: StringField): TextFieldState = texts.getOrPut(field) { TextFieldState(field.get(current())) }

    fun text(field: ColorField): TextFieldState = texts.getOrPut(field) { TextFieldState(field.get(current())) }

    fun toggle(field: BooleanField, value: Boolean) = update(field.set(current(), value))

    /** Applies [field]'s text once it is a colour or blank; called whenever it changes. */
    fun commit(field: ColorField) {
        val text = text(field).text.toString().trim()
        val error = field.error(text)
        errors = if (error == null) errors - field else errors + (field to error)
        if (error == null) {
            val value = if (text.isEmpty()) "" else parseHexColor(text)!!.toHex()
            if (field.get(current()) != value) update(field.set(current(), value))
        }
    }

    /** Sets [field] to [color] from a swatch, or back to its fallback for null. */
    fun pick(field: ColorField, color: androidx.compose.ui.graphics.Color?) {
        text(field).setTextAndPlaceCursorAtEnd(color?.toHex() ?: "")
        commit(field)
    }

    fun commit(field: StringField) {
        val value = text(field).text.toString()
        val error = field.error(value)
        errors = if (error == null) errors - field else errors + (field to error)
        if (error == null && field.get(current()) != value) update(field.set(current(), value))
    }

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
                is StringField -> {
                    text(field).setTextAndPlaceCursorAtEnd(field.get(defaults))
                    commit(field)
                }
                is BooleanField -> toggle(field, field.get(defaults))
                is ColorField -> {
                    text(field).setTextAndPlaceCursorAtEnd(field.get(defaults))
                    commit(field)
                }
            }
        }
        page.sections.forEach { section -> section.reset?.let { update(it(current(), defaults)) } }
    }

    private fun <T> resetChoice(field: ChoiceField<T>, defaults: Settings) = choose(field, field.get(defaults))
}
