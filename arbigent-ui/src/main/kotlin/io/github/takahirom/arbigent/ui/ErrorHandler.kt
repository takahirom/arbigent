package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.arbigentErrorLog
import io.github.takahirom.arbigent.errorHandler
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*
import kotlin.concurrent.thread

@OptIn(ArbigentInternalApi::class)
fun plantErrorDialog() {
  val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
  errorHandler = { e ->
    thread {
      showErrorDialog(e)
    }
    arbigentErrorLog(e.stackTraceToString())
  }
  Thread.setDefaultUncaughtExceptionHandler { t, e ->
    showErrorDialog(e)
    defaultUncaughtExceptionHandler?.uncaughtException(t, e)
  }
}

private var lastErrorString = ""

internal fun showErrorDialog(e: Throwable) {
  val errorString = e.stackTraceToString()
  if (errorString == lastErrorString) {
    return
  }
  lastErrorString = errorString
  val errorText = """An unexpected error occurred.

${e.message}

${e.stackTraceToString()}"""

  SwingUtilities.invokeLater {
    val dialog = JDialog(null as JFrame?, "Error", true)
    dialog.layout = FlowLayout()

    val textArea = JTextArea(errorText, 15, 50)
    textArea.isEditable = false
    val scrollPane = JScrollPane(textArea)
    dialog.add(scrollPane)

    val copyButton = JButton("Copy Stacktrace")
    copyButton.addActionListener {
      val clipboard = Toolkit.getDefaultToolkit().systemClipboard
      val strSel = StringSelection(e.stackTraceToString())
      clipboard.setContents(strSel, null)
      JOptionPane.showMessageDialog(dialog, "Stacktrace copied to clipboard.")
    }
    dialog.add(copyButton)

    val closeButton = JButton("Close")
    closeButton.addActionListener { dialog.dispose() }
    dialog.add(closeButton)

    dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    dialog.pack()
    dialog.setLocationRelativeTo(null)
    dialog.isVisible = true
  }
}
