package dev.tablegrip.app.ui

import org.jetbrains.jewel.ui.icon.PathIconKey

/**
 * Icons drawn for TableGrip, under the project's Apache-2.0 license, in resources/icons/. Each has a `_dark` twin that
 * Jewel picks in dark themes; they use the Int UI greys (#6C707E light, #CED0D6 dark) so tinting matches AllIconsKeys.
 */
internal object TableGripIcons {
    /** A speech bubble with a spark: the agent panel. Replaces the IntelliJ AskAI icon (see docs/plans/rust-ui-kit/icon-licence.md). */
    val Agent = PathIconKey("icons/agent.svg", TableGripIcons::class.java)
}
