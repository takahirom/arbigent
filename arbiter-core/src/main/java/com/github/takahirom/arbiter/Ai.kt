package com.github.takahirom.arbiter

interface Ai {
  fun decideWhatToDo(
    arbiterContext: ArbiterContext,
    dumpHierarchy: String,
    screenshot: String?,
    agentCommandMap: Map<String, AgentCommand>,
    screenshotFileName: String,
  ): AgentCommand
}
