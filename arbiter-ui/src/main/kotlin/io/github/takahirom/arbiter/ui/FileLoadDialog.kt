package io.github.takahirom.arbiter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.AwtWindow
import java.awt.FileDialog
import java.awt.Frame
import java.io.File


@Composable
fun FileLoadDialog(
  parent: Frame? = null,
  title: String = "Choose a file",
  onCloseRequest: (File?) -> Unit
) = AwtWindow(
  create = {
    object : FileDialog(parent, title, LOAD) {
      override fun setVisible(value: Boolean) {
        super.setVisible(value)
        if (value) {
          if (this.file != null) {
            onCloseRequest(File(directory, this.file))
          } else {
            onCloseRequest(null)
          }
        }
      }
    }
  },
  dispose = FileDialog::dispose
)

@Composable
fun FileSaveDialog(
  parent: Frame? = null,
  title: String = "Save a file",
  file: String? = null,
  onCloseRequest: (File?) -> Unit
) = AwtWindow(
  create = {
    val fileDialog = object : FileDialog(parent, title, SAVE) {
      override fun setVisible(value: Boolean) {
        super.setVisible(value)
        if (value) {
          if (this.file != null) {
            onCloseRequest(File(directory, this.file))
          } else {
            onCloseRequest(null)
          }
        }
      }
    }
    fileDialog
  },
  dispose = FileDialog::dispose
)
