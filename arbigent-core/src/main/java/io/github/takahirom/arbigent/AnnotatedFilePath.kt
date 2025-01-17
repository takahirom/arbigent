package io.github.takahirom.arbigent

import java.io.File

public fun File.getAnnotatedFilePath(): String =
  absolutePath.substringBeforeLast(".") + "_annotated." + extension

public fun File.toAnnotatedFile(): File = File(getAnnotatedFilePath())
