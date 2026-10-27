package dev.datagrok.app.ui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal fun copyToClipboard(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
