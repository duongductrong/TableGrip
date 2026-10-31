package dev.datagrok.app.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.datagrok.app.ui.LocalIslands
import dev.datagrok.core.agent.AgentAdapter
import dev.datagrok.core.agent.AgentModel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/** One row of the model list: the agent's own default, a model it offers, or what the user typed. */
internal sealed interface ModelChoice {
    /** What picking it stores; null goes back to the agent's default. */
    val model: String?

    data object Default : ModelChoice {
        override val model: String? = null
    }

    data class Offered(val offered: AgentModel) : ModelChoice {
        override val model: String = offered.id
    }

    data class Typed(override val model: String) : ModelChoice
}

/**
 * What the list shows for [query]: what was typed (unless it names an offered model exactly), the default when nothing
 * is typed, then the offered models whose id or name contains every word of it.
 */
internal fun modelChoices(models: List<AgentModel>, query: String): List<ModelChoice> {
    val typed = query.trim()
    if (typed.isEmpty()) return listOf(ModelChoice.Default) + models.map { ModelChoice.Offered(it) }
    val words = typed.lowercase().split(Regex("\\s+"))
    val matches = models.filter { model -> words.all { word -> model.id.lowercase().contains(word) || model.label?.lowercase()?.contains(word) == true } }
    val exact = models.any { it.id.equals(typed, ignoreCase = true) }
    return (if (exact) emptyList() else listOf(ModelChoice.Typed(typed))) + matches.map { ModelChoice.Offered(it) }
}

/**
 * The selected agent's model, beside the agent picker; shown only for agents that can be told one. The list is what the
 * agent says it offers, and the field above it narrows it or takes any model by hand, for agents that can't list theirs
 * or a model the list doesn't know yet.
 */
@Composable
internal fun ModelPicker(agent: AgentViewModel) {
    val selected = agent.selected?.takeIf { it.choosesModel } ?: return
    val islands = LocalIslands.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var open by remember(selected) { mutableStateOf(false) }
    val model = agent.model
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (hovered || open) islands.chipHover else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model ?: "Default model",
                Modifier.widthIn(max = 160.dp),
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(AllIconsKeys.General.ChevronDown, null, Modifier.size(12.dp))
        }
        if (open) {
            ModelList(agent, selected, model, onDismiss = { open = false })
        }
    }
}

@Composable
private fun ModelList(agent: AgentViewModel, selected: AgentAdapter, current: String?, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val query = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    // Null while the agent is still being asked; an adapter keeps what it found, so opening again is instant.
    val models by produceState<List<AgentModel>?>(null, selected) { value = agent.models(selected) }
    val choices = modelChoices(models.orEmpty(), query.text.toString())
    var highlighted by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(query.text) { highlighted = 0 }
    LaunchedEffect(highlighted) { if (choices.isNotEmpty()) listState.animateScrollToItem((highlighted - 2).coerceAtLeast(0)) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun pick(choice: ModelChoice) {
        agent.chooseModel(choice.model)
        onDismiss()
    }

    Popup(popupPositionProvider = AboveAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(320.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextField(
                query,
                Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val last = choices.lastIndex
                    when (event.key) {
                        Key.Escape -> true.also { onDismiss() }
                        Key.DirectionDown -> true.also { if (last >= 0) highlighted = if (highlighted >= last) 0 else highlighted + 1 }
                        Key.DirectionUp -> true.also { if (last >= 0) highlighted = if (highlighted <= 0) last else highlighted - 1 }
                        Key.Enter, Key.NumPadEnter -> true.also { choices.getOrNull(highlighted.coerceIn(0, maxOf(last, 0)))?.let(::pick) }
                        else -> false
                    }
                },
                placeholder = { Text(if (models.isNullOrEmpty()) "Type a model id" else "Search or type a model id") },
            )
            when {
                models == null ->
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp))
                        Text("Asking ${selected.descriptor.name} for its models…", color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
                    }
                models.isNullOrEmpty() ->
                    Text(
                        "${selected.descriptor.name} didn't list its models. Type one and press Enter.",
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = JewelTheme.globalColors.text.info,
                        fontSize = 11.sp,
                    )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), state = listState) {
                items(choices.size) { index ->
                    val choice = choices[index]
                    ModelRow(choice, active = choice.model == current, highlighted = index == highlighted) { pick(choice) }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(choice: ModelChoice, active: Boolean, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (highlighted) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (active) Icon(AllIconsKeys.Actions.Checked, null, Modifier.size(14.dp))
        }
        val (title, detail) =
            when (choice) {
                ModelChoice.Default -> "Default" to "the agent's own setting"
                is ModelChoice.Offered -> choice.offered.id to choice.offered.label?.takeIf { it != choice.offered.id }
                is ModelChoice.Typed -> "Use “${choice.model}”" to null
            }
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }
    }
}
