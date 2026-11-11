package dev.tablegrip.app.ui

import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey

/** Icons of our own, beside the IntelliJ set; each has a `_dark` twin the dark theme picks up. */
internal object AppIcons {
    /** The agent's chat: a little robot. */
    val Agent: IconKey = PathIconKey("icons/robot.svg", AppIcons::class.java)
}
