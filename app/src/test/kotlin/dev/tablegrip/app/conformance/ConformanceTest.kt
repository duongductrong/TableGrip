package dev.tablegrip.app.conformance

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.tablegrip.app.ui.TableGripTheme
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.fail

/**
 * Runs every scenario in `ui-kit/conformance/scenarios` against the components the app uses today. A scenario belongs
 * in the suite only once it passes here: the Kotlin app is the reference the Rust UI kit is held to.
 *
 * `./gradlew :app:test --tests '*ConformanceTest*'`
 */
@OptIn(ExperimentalTestApi::class)
class ConformanceTest {
    @TestFactory
    fun scenarios(): List<DynamicNode> =
        Scenarios.load().map { file ->
            DynamicContainer.dynamicContainer(file.source.name, file.cases.map { case -> DynamicTest.dynamicTest(case.name) { run(file.component, case) } })
        }

    /** Guards against a runner that passes everything: each of these expectations is wrong and must fail. */
    @Test
    fun `the runner rejects wrong expectations`() {
        val wrong =
            listOf(
                Triple("checkbox", listOf(Step.Click()), mapOf("checked" to "false")),
                Triple("checkbox", listOf(Step.Focus), mapOf("focused" to "false")),
                Triple("button", listOf(Step.Click()), mapOf("clicks" to "2")),
                Triple("text-field", listOf(Step.Focus, Step.Type("a")), mapOf("text" to "")),
            )
        for ((component, steps, expect) in wrong) {
            val case = ScenarioCase("wrong", JsonObject(emptyMap()), steps, expect.mapValues { JsonPrimitive(it.value) })
            assertFailsWith<AssertionError>("$component $steps $expect should fail") { run(component, case) }
        }
    }

    private fun run(component: String, case: ScenarioCase) = runComposeUiTest {
        val probe = Probe()
        setContent { TableGripTheme(isDark = true) { Subject(component, case.setup, probe) } }
        waitForIdle()
        for (step in case.steps) {
            perform(step)
            waitForIdle()
        }
        val actual =
            case.expect.keys.associateWith { key ->
                when {
                    key == "focused" -> focusedWithin(SubjectTag).toString()
                    key == "nextFocused" -> focusedWithin(NextTag).toString()
                    // `shown:<tag>`: whether a node with that tag is on screen (a tooltip, a popup, a menu item).
                    key.startsWith("shown:") -> onAllNodes(hasTestTag(key.removePrefix("shown:"))).fetchSemanticsNodes().isNotEmpty().toString()
                    else -> runOnIdle { probe.readers[key]?.invoke() } ?: fail("Subject '$component' can't report '$key'")
                }
            }
        assertEquals(case.expect.mapValues { it.value.normalized() }, actual, "${case.name} after ${case.steps}")
    }

    private fun ComposeUiTest.perform(step: Step) {
        when (step) {
            is Step.Click -> node(step.tag).performClick()
            is Step.DoubleClick -> node(step.tag).performMouseInput { doubleClick() }
            is Step.RightClick -> node(step.tag).performMouseInput { rightClick() }
            is Step.Hover -> node(step.tag).performMouseInput { moveTo(center) }
            Step.Unhover -> onRoot().performMouseInput { moveTo(Offset(-1f, -1f)) }
            is Step.Wait -> mainClock.advanceTimeBy(step.ms)
            Step.Focus -> onAllNodes(within(SubjectTag) and SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)).onFirst().requestFocus()
            is Step.Key -> target().performKeyInput { withMods(step.mods) { pressKey(keyOf(step.key)) } }
            // A control that can't take text (disabled, say) has no text action: typing into it does nothing.
            is Step.Type -> {
                val focused = onAllNodes(isFocused() and SemanticsMatcher.keyIsDefined(SemanticsActions.InsertTextAtCursor)).fetchSemanticsNodes()
                if (focused.isNotEmpty()) onNode(isFocused()).performTextInput(step.text)
            }
        }
    }

    /** The subject, or the first node tagged [tag]. */
    private fun ComposeUiTest.node(tag: String?): SemanticsNodeInteraction = onAllNodes(hasTestTag(tag ?: SubjectTag)).onFirst()

    /** The focused node, where key presses go; the subject when nothing has focus. */
    private fun ComposeUiTest.target(): SemanticsNodeInteraction =
        if (onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()) onNode(isFocused()) else onNode(hasTestTag(SubjectTag))

    private fun ComposeUiTest.focusedWithin(tag: String): Boolean =
        onAllNodes(within(tag) and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().isNotEmpty()

    private fun within(tag: String) = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))

    private fun KeyInjectionScope.withMods(mods: List<String>, block: KeyInjectionScope.() -> Unit) {
        if (mods.isEmpty()) return block()
        withKeyDown(modifierKey(mods.first())) { withMods(mods.drop(1), block) }
    }

    private fun modifierKey(name: String): Key =
        when (name) {
            "primary" -> if (hostOs == OS.MacOS) Key.MetaLeft else Key.CtrlLeft
            "shift" -> Key.ShiftLeft
            "alt" -> Key.AltLeft
            "ctrl" -> Key.CtrlLeft
            "meta" -> Key.MetaLeft
            else -> error("Unknown modifier '$name'")
        }

    private fun keyOf(name: String): Key =
        when (name) {
            "Space" -> Key.Spacebar
            "Enter" -> Key.Enter
            "Tab" -> Key.Tab
            "Escape" -> Key.Escape
            "Backspace" -> Key.Backspace
            "Delete" -> Key.Delete
            "Home" -> Key.MoveHome
            "End" -> Key.MoveEnd
            "Left" -> Key.DirectionLeft
            "Right" -> Key.DirectionRight
            "Up" -> Key.DirectionUp
            "Down" -> Key.DirectionDown
            "PageUp" -> Key.PageUp
            "PageDown" -> Key.PageDown
            "F2" -> Key.F2
            else -> if (name.length == 1 && name[0] in 'A'..'Z') Letters[name[0] - 'A'] else error("Unknown key '$name'")
        }

    private companion object {
        val Letters =
            listOf(
                Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
                Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
            )
    }
}
