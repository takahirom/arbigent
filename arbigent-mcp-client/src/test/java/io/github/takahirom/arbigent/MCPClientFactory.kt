package io.github.takahirom.arbigent

@Suppress("TestFunctionName")
fun MCPClient(): MCPClient {
  return MCPClient(
    jsonString = "{}",
    appSettings = DefaultArbigentAppSettings
  )
}