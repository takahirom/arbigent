Specification: Arbigent MCP Server
Version: 1.0
Date: 2025-04-04

1. Introduction
   1.1. Purpose
   This document specifies the requirements and design for the Arbigent MCP Server. This server acts as a bridge between MCP (Model Context Protocol) clients (e.g., Claude for Desktop) and the arbigent-cli, enabling users to trigger Arbigent automated tests via interactions with an LLM.

1.2. Scope
Defining the server's startup mechanism via npx.

Handling command-line arguments for configuration (project.yaml, config.yaml) and the arbigent-cli path.

Locating the arbigent-cli executable.

Dynamically discovering test tags from the specified project.yaml.

Exposing MCP tools to run all tests or tests associated with specific tags.

Executing arbigent-cli as a subprocess.

Returning test results or errors back to the MCP client.

1.3. Goals
Provide a seamless way for LLMs/MCP clients to execute Arbigent tests.

Offer flexibility by allowing users to run all tests or specific test suites (tags).

Ensure robust handling of the arbigent-cli dependency.

Provide clear feedback on test execution status and results.

1.4. Target Audience
Software Developers (implementing the MCP server)

Users of MCP clients who want to integrate Arbigent testing.

2. Requirements
   2.1. Functional Requirements
   FR-01: Server Startup: The server MUST be launchable using npx <package-name> [options]. It will operate as an MCP server communicating via standard input/output (StdioServerTransport).

FR-02: Argument Parsing: The server MUST accept the following command-line arguments:

--project <path>: (Required) Path to the Arbigent project YAML file.

--config <path>: (Required) Path to the Arbigent config YAML file (containing API keys, etc.).

--arbigent-bin-path <path>: (Optional) Explicit path to the arbigent-cli executable.

FR-03: CLI Path Resolution:

If --arbigent-bin-path is provided, the server MUST use that path to locate arbigent-cli.

If --arbigent-bin-path is not provided, the server MUST attempt to find arbigent-cli in the system's PATH environment variable.

If arbigent-cli cannot be found using either method, the server MUST log a fatal error and exit immediately upon startup or connection attempt.

FR-04: Dynamic Tool Discovery: Upon startup, the server MUST parse the project.yaml file specified by the --project argument to extract all unique test tags defined within the scenarios.

FR-05: MCP Tool Exposure: The server MUST expose the following tools to the connected MCP client:

run-arbigent-test-all: A tool to run all tests defined in the project YAML.

run-arbigent-test-[tag_name]: Dynamically generated tools for each unique tag discovered in FR-04. (e.g., run-arbigent-test-login, run-arbigent-test-purchase). The description should indicate which tag it runs.

FR-06: Test Execution: When an MCP tool is invoked by the client:

The server MUST execute the arbigent-cli as a subprocess.

The necessary arguments (--project, --config, and potentially --tag <tag_name> for tag-specific tools) MUST be passed to arbigent-cli.

The server MUST capture the standard output and standard error streams from the arbigent-cli process.

FR-07: Result Reporting:

Upon successful completion of arbigent-cli, the server MUST return the captured standard output (summary of test results) as text content to the MCP client.

If arbigent-cli exits with a non-zero status code (indicating failure), the server MUST return the captured standard output and standard error as text content, clearly indicating a failure.

FR-08: Error Handling: The server MUST handle and report errors, including:

Failure to find arbigent-cli (FR-03).

Failure to parse project.yaml.

Errors during the execution of arbigent-cli (e.g., invalid config, runtime errors during tests).

Errors should be reported back to the MCP client as informative text content.

2.2. Non-Functional Requirements
NFR-01: Performance: The overhead introduced by the MCP server itself should be minimal. The primary execution time will be determined by arbigent-cli.

NFR-02: Reliability: The server should reliably manage the arbigent-cli subprocess and handle its termination signals correctly.

NFR-03: Security: The --config file path is passed as an argument. The server itself does not need to handle secrets directly, but relies on arbigent-cli's handling. Ensure no sensitive information from the config file is inadvertently logged or exposed by the MCP server itself.

NFR-04: Configurability: The paths to project/config files and the optional CLI path are configurable via command-line arguments.

NFR-05: Logging: The server SHOULD log key events to standard error for debugging purposes (e.g., server start, CLI path resolved, executing CLI command, CLI completion/error).

2.3. Compatibility Requirements
CR-01: MCP Protocol: Must be compatible with the version used by @modelcontextprotocol/sdk.

CR-02: Node.js: Requires Node.js version [Specify Minimum Version, e.g., 18.x or higher].

CR-03: Arbigent CLI: Designed to work with arbigent-cli version [Specify Target Version Range, if known, e.g., 1.x]. Compatibility with future versions depends on the stability of the CLI's arguments and output.

3. High-Level Design
   The server will be a Node.js application using the @modelcontextprotocol/sdk.

It will utilize StdioServerTransport for communication.

Command-line arguments will be parsed using a library like yargs or commander.

YAML parsing will be done using a library like js-yaml.

The child_process module (specifically spawn or execFile) will be used to run arbigent-cli.

Tool definitions will be dynamically generated based on parsed YAML tags during server initialization.

4. Implementation Steps
   Phase 1: Setup & Core Server
   Initialize Node.js project (npm init).

Install dependencies: @modelcontextprotocol/sdk, zod, js-yaml, argument parsing library (e.g., yargs), TypeScript and types (typescript, @types/node, @types/js-yaml, etc.) if using TypeScript.

Set up tsconfig.json and package.json (including type: "module", build script, and bin entry for npx).

Implement basic MCP server structure using McpServer and StdioServerTransport.

Implement command-line argument parsing.

Phase 2: CLI Interaction & Tool Discovery
Implement arbigent-cli path resolution logic (check argument, then PATH). Add error handling if not found.

Implement project.yaml parsing logic to extract unique tags. Add error handling for file reading/parsing.

Dynamically register MCP tools (run-arbigent-test-all and run-arbigent-test-[tag]) with appropriate names, descriptions, and input schemas (likely no input parameters needed for these tools initially).

Phase 3: Test Execution Logic
Implement the execution handler for the MCP tools.

Inside the handler, construct the correct arguments for arbigent-cli based on the invoked tool (e.g., add --tag argument if needed).

Use child_process.spawn to execute arbigent-cli with the resolved path and constructed arguments.

Capture stdout and stderr from the subprocess.

Wait for the subprocess to exit and check its exit code.

Phase 4: Result Formatting & Error Handling
Implement logic to format the captured stdout/stderr into the MCP response content (text).

Distinguish between successful runs (exit code 0) and failures (non-zero exit code) in the response.

Implement comprehensive error handling for subprocess errors (e.g., command not found even after path resolution, execution permissions).

Add logging for diagnostics.

Phase 5: Testing & Packaging
Write unit tests for argument parsing, tag extraction, and CLI path resolution.

Write integration tests (potentially requiring a mock arbigent-cli or specific test YAMLs) to verify tool registration and execution flow.

Test manually with a real arbigent-cli and an MCP client (like Claude for Desktop or a custom client).

Prepare the package for publishing to npm (if intended).

5. MCP Tool Specification
   5.1. run-arbigent-test-all
   Name: run-arbigent-test-all

Description: Runs all Arbigent tests defined in the configured project YAML file.

Input Schema: None (No parameters needed).

Output: Text content containing the summary output from arbigent-cli. Indicates success or failure.

Execution: Calls arbigent-cli --project <path> --config <path>.

5.2. run-arbigent-test-[tag_name]
Name: run-arbigent-test-[tag_name] (e.g., run-arbigent-test-login)

Description: Runs Arbigent tests associated with the '[tag_name]' tag from the configured project YAML file.

Input Schema: None (No parameters needed).

Output: Text content containing the summary output from arbigent-cli. Indicates success or failure.

Execution: Calls arbigent-cli --project <path> --config <path> --tag [tag_name].

6. Error Handling Summary
   Startup:

Missing required arguments (--project, --config): Error message and exit.

Cannot find arbigent-cli: Error message and exit.

Cannot read/parse project.yaml: Error message and exit.

Tool Execution:

arbigent-cli execution fails (e.g., permission denied): Report error via MCP response.

arbigent-cli exits non-zero: Report failure and include stdout/stderr via MCP response.

7. Assumptions & Dependencies
   The environment running the MCP server has Node.js installed (version specified in CR-02).

The arbigent-cli executable is either present in the system PATH or its path is provided via the --arbigent-bin-path argument.

The provided project.yaml and config.yaml files are valid and accessible by the server process.

The arbigent-cli itself functions correctly when called with the appropriate arguments.

8. Implementation Example (TypeScript)
   Below is a basic implementation example in TypeScript, demonstrating the core concepts outlined in this specification. Note that this is a simplified example and requires further development, particularly in argument parsing and error handling.

#!/usr/bin/env node
// Import necessary packages
import { McpServer, McpRequest, McpResponse, McpTool } from "@modelcontextprotocol/sdk/server";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio";
import { z } from "zod"; // For parameter validation
import { spawn } from "child_process"; // For executing external commands
import * as fs from "fs"; // For file system operations
import * as yaml from "js-yaml"; // For YAML parsing
import * as path from "path"; // For path manipulation
// import { ArgumentParser } from 'argparse'; // Consider using an argument parser like yargs or commander

// --- Constants ---
// (Define constants if needed)

// --- Argument Parsing (Example) ---
// TODO: Implement robust argument parsing using yargs, commander, or similar
// Example:
// const args = {
//   project: process.argv[2] || 'arbigent-project.yaml', // Temporary default
//   config: process.argv[3] || 'arbigent-config.yaml',   // Temporary default
//   arbigentBinPath: process.argv[4] || null             // Temporary default
// };
// console.error("Arguments:", args); // For debugging

// --- Helper Functions ---

/**
* Resolves the executable path for arbigent-cli.
* @param explicitPath Path provided via --arbigent-bin-path (or null).
* @returns The executable path string, or null if not found.
  */
  function resolveArbigentCliPath(explicitPath: string | null): string | null {
  if (explicitPath) {
  // TODO: Verify if explicitPath actually exists and is executable
  console.error(`Using explicit path: ${explicitPath}`);
  return explicitPath;
  }

// Search in PATH environment variable (may need cross-platform handling)
const command = "arbigent-cli"; // Or the actual command name
const paths = process.env.PATH?.split(path.delimiter) || [];
for (const p of paths) {
const fullPath = path.join(p, command);
// TODO: Consider .exe suffix on Windows
try {
fs.accessSync(fullPath, fs.constants.X_OK); // Check for execute permission
console.error(`Found in PATH: ${fullPath}`);
return fullPath;
} catch (e) {
// Not found or not executable
}
}
console.error(`${command} not found in PATH.`);
return null;
}

/**
* Extracts tags from project.yaml (Placeholder implementation).
* @param projectYamlPath Path to the project YAML file.
* @returns An array of tag names.
  */
  function extractTagsFromYaml(projectYamlPath: string): string[] {
  try {
  const fileContents = fs.readFileSync(projectYamlPath, 'utf8');
  const data = yaml.load(fileContents) as any; // Adjust type based on YAML structure
  const tags = new Set<string>();
  // TODO: Implement logic to extract tags based on the actual YAML structure
  // Example: data.scenarios?.forEach(s => s.tags?.forEach(t => tags.add(t)));
  console.error("Extracted tags:", Array.from(tags));
  return Array.from(tags);
  } catch (error) {
  console.error(`Error reading or parsing project YAML: ${projectYamlPath}`, error);
  return []; // Return empty array on error
  }
  }

/**
* Executes the arbigent-cli command.
* @param cliPath Path to the arbigent-cli executable.
* @param projectYaml Path to the project YAML.
* @param configYaml Path to the config YAML.
* @param tag Optional tag to execute.
* @returns A promise resolving with the execution result.
  */
  function runArbigentCli(
  cliPath: string,
  projectYaml: string,
  configYaml: string,
  tag?: string
  ): Promise<{ stdout: string; stderr: string; exitCode: number | null }> {
  return new Promise((resolve) => {
  const args = ['--project', projectYaml, '--config', configYaml];
  if (tag) {
  args.push('--tag', tag);
  }
  console.error(`Executing: ${cliPath} ${args.join(' ')}`);

  const process = spawn(cliPath, args);
  let stdout = '';
  let stderr = '';

  process.stdout.on('data', (data) => {
  stdout += data.toString();
  console.log(`stdout: ${data}`); // Real-time log (optional)
  });

  process.stderr.on('data', (data) => {
  stderr += data.toString();
  console.error(`stderr: ${data}`); // Real-time log (optional)
  });

  process.on('close', (code) => {
  console.error(`arbigent-cli process exited with code ${code}`);
  resolve({ stdout, stderr, exitCode: code });
  });

  process.on('error', (err) => {
  console.error('Failed to start subprocess.', err);
  // Error during process spawning itself
  resolve({ stdout: '', stderr: `Failed to start subprocess: ${err.message}`, exitCode: -1 });
  });
  });
  }


// --- Main Execution ---
async function main() {
// --- Argument Parsing (Actual Implementation) ---
// Use yargs or commander to parse --project, --config, --arbigent-bin-path
// Using placeholder values for this example
const args = {
project: 'arbigent-project.yaml', // Replace with actual parsed value
config: 'arbigent-config.yaml',   // Replace with actual parsed value
arbigentBinPath: null             // Replace with actual parsed value (can be null)
};
console.error("Parsed Arguments (Example):", args);

if (!args.project || !args.config) {
console.error("Error: --project and --config arguments are required.");
process.exit(1);
}

// --- Resolve CLI Path ---
const cliPath = resolveArbigentCliPath(args.arbigentBinPath);
if (!cliPath) {
console.error("Error: Could not find arbigent-cli executable.");
process.exit(1); // Exit on error
}

// --- Extract Tags ---
const tags = extractTagsFromYaml(args.project);
if (!tags) {
console.error("Error: Could not read or parse project YAML.");
// Decide whether to continue without tag-specific tools or exit
// process.exit(1);
}


// --- Create MCP Server Instance ---
const server = new McpServer({
name: "arbigent-runner", // Server name
version: "0.1.0",       // Server version
capabilities: {
resources: {}, // Not using resources in this example
tools: {},     // Tools will be registered dynamically
},
});

// --- Dynamic Tool Registration ---

// 'run-arbigent-test-all' tool
server.tool(
"run-arbigent-test-all", // Tool name
"Runs all Arbigent tests defined in the project YAML.", // Tool description
{}, // No input parameters
async (params: z.infer<typeof z.object({}) >): Promise<McpResponse> => {
console.error("Executing tool: run-arbigent-test-all");
const result = await runArbigentCli(cliPath, args.project, args.config);
const outputText = `Exit Code: ${result.exitCode}\n\nSTDOUT:\n${result.stdout}\n\nSTDERR:\n${result.stderr}`;
return {
content: [{ type: "text", text: outputText }],
};
}
);

// Tools for each tag
tags.forEach(tag => {
const toolName = `run-arbigent-test-${tag.replace(/[^a-zA-Z0-9_]/g, '_')}`; // Sanitize tag for tool name
server.tool(
toolName,
`Runs Arbigent tests with the tag '${tag}'.`,
{}, // No input parameters
async (params: z.infer<typeof z.object({})>): Promise<McpResponse> => {
console.error(`Executing tool: ${toolName} (tag: ${tag})`);
const result = await runArbigentCli(cliPath, args.project, args.config, tag);
const outputText = `Exit Code: ${result.exitCode}\n\nSTDOUT:\n${result.stdout}\n\nSTDERR:\n${result.stderr}`;
return {
content: [{ type: "text", text: outputText }],
};
}
);
console.error(`Registered tool: ${toolName}`);
});


// --- Connect Server & Run ---
const transport = new StdioServerTransport();
try {
await server.connect(transport);
console.error("Arbigent MCP Server running on stdio...");
} catch (error) {
console.error("Failed to connect server:", error);
process.exit(1);
}
}

// Run the main function
main().catch((error) => {
console.error("Fatal error in main():", error);
process.exit(1);
});
