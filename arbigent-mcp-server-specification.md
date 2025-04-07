Specification: Arbigent MCP Server
Version: 1.2 (Revised @argfile handling)
Date: 2025-04-04

1. Introduction
   1.1. Purpose
   This document specifies the requirements and design for the Arbigent MCP Server. This server acts as a bridge between MCP (Model Context Protocol) clients (e.g., Claude for Desktop) and the arbigent-cli, enabling users to trigger Arbigent automated tests via interactions with an LLM.

1.2. Scope
Defining the server's startup mechanism via npx.

Handling specific command-line arguments: --project <path>, --argfile <path>, and --arbigent-bin-path <path>.

Locating the arbigent-cli executable.

Dynamically discovering test tags using the --project argument.

Exposing MCP tools to run all tests or tests associated with specific tags.

Executing arbigent-cli as a subprocess, passing the @<argfile_path> argument for arbigent-cli to process.

Returning test results or errors back to the MCP client.

1.3. Goals
Provide a seamless way for LLMs/MCP clients to execute Arbigent tests via arbigent-cli.

Leverage arbigent-cli's potential @argfile capability for argument management.

Offer flexibility by allowing users to run all tests or specific test suites (tags) discovered by the MCP server.

Ensure robust handling of the arbigent-cli dependency.

Provide clear feedback on test execution status and results.

1.4. Target Audience
Software Developers (implementing the MCP server)

Users of MCP clients who want to integrate Arbigent testing.

2. Requirements
   2.1. Functional Requirements
   FR-01: Server Startup: The server MUST be launchable using npx <package-name> --project <path> --argfile <path> [--arbigent-bin-path <path>]. It will operate as an MCP server communicating via standard input/output (StdioServerTransport).

FR-02: Argument Parsing:

The server MUST accept the following command-line arguments:

--project <path>: (Required) Path to the Arbigent project YAML file. Used by the MCP server for tag discovery.

--argfile <path>: (Required) Path to the argument file that will be passed to arbigent-cli.

--arbigent-bin-path <path>: (Optional) Explicit path to the arbigent-cli executable.

The MCP server DOES NOT parse the content of the file specified by --argfile.

FR-03: CLI Path Resolution:

The server MUST determine the path to arbigent-cli based on the --arbigent-bin-path argument if provided.

Otherwise, the server MUST attempt to find arbigent-cli in the system's PATH.

If arbigent-cli cannot be found, the server MUST log a fatal error and exit.

FR-04: Dynamic Tool Discovery: Upon startup, using the --project <path> argument provided directly to the MCP server, the server MUST parse the specified project YAML file to extract unique test tags.

FR-05: MCP Tool Exposure: The server MUST expose the following tools:

run-arbigent-test-all: Runs all tests (by invoking arbigent-cli with @argfile).

run-arbigent-test-[tag_name]: Dynamically generated tools for each unique tag (by invoking arbigent-cli with @argfile and --tag).

FR-06: Test Execution: When an MCP tool is invoked:

The server MUST execute the resolved arbigent-cli as a subprocess.

The arguments passed to arbigent-cli MUST include @<path_specified_by_--argfile>.

If a tag-specific tool (e.g., run-arbigent-test-login) is invoked, the server MUST also pass the corresponding --tag <tag_name> argument to arbigent-cli. Note: This assumes arbigent-cli handles precedence correctly if --tag is also present inside the argfile.

The server MUST capture standard output and standard error from arbigent-cli.

FR-07: Result Reporting: (Unchanged) Reports results based on arbigent-cli output and exit code.

FR-08: Error Handling: (Updated) The server MUST handle and report errors, including:

Missing required arguments (--project, --argfile).

File specified by --argfile not found or not readable by the MCP server (basic check).

Failure to find arbigent-cli.

Failure to parse the project YAML specified by --project.

Errors during arbigent-cli execution (reported back from the subprocess).

Note: Errors related to the content or parsing of the argfile are the responsibility of arbigent-cli.

2.2. Non-Functional Requirements
NFR-01: Performance: MCP server overhead is minimal. Performance depends on arbigent-cli's startup and execution time, including its argfile parsing.

NFR-02: Reliability: (Unchanged) Reliable subprocess management.

NFR-03: Security: (Unchanged) Relies on arbigent-cli. File permissions of the argfile are the user's responsibility.

NFR-04: Configurability: Configuration primarily managed via the argfile passed to arbigent-cli. MCP server requires --project, --argfile, and optional --arbigent-bin-path.

NFR-05: Logging: (Unchanged) Log key events.

2.3. Compatibility Requirements
(Unchanged)

3. High-Level Design
   (Updated) Argument parsing layer is simplified, only needing to handle --project, --argfile, --arbigent-bin-path. No complex expansion logic needed in the MCP server.

(Rest unchanged)

4. Implementation Steps
   Phase 1: Setup & Core Server
   Initialize Node.js project.

Install dependencies (@modelcontextprotocol/sdk, zod, js-yaml, argument parser like yargs).

Set up tsconfig.json and package.json.

Implement Argument Parsing: Use the chosen library to strictly parse --project, --argfile, and optional --arbigent-bin-path. Ensure required arguments are present. Perform a basic check if the --argfile path exists.

Implement basic MCP server structure.

Phase 2: CLI Interaction & Tool Discovery
Implement arbigent-cli path resolution using the parsed --arbigent-bin-path or PATH.

Implement project.yaml parsing using the parsed --project path to extract tags.

Dynamically register MCP tools.

Phase 3: Test Execution Logic
Implement the execution handler.

Construct arguments for arbigent-cli: Always include @<argfile_path>. If it's a tag-specific tool, also include --tag <tag_name>.

Use child_process.spawn to execute arbigent-cli.

Capture output streams.

Wait for exit.

Phase 4: Result Formatting & Error Handling
Implement result formatting.

Implement error handling for MCP server specific issues (missing args, CLI not found, project YAML parse error, argfile path not found).

Add logging.

Phase 5: Testing & Packaging
Write unit tests for argument parsing and tag extraction.

Write integration tests verifying that @<argfile_path> and --tag are correctly passed to the (mocked or real) arbigent-cli.

Test manually, ensuring arbigent-cli handles the @argfile correctly.

Prepare package.

5. MCP Tool Specification
   5.1. run-arbigent-test-all

Execution: Calls arbigent-cli @<argfile_path>

5.2. run-arbigent-test-[tag_name]

Execution: Calls arbigent-cli @<argfile_path> --tag <tag_name>

6. Error Handling Summary
   Startup:

Missing required arguments (--project, --argfile).

File specified by --argfile not found/readable.

Cannot find arbigent-cli.

Cannot read/parse project YAML specified by --project.

Tool Execution:

Errors reported by arbigent-cli (via stderr or non-zero exit code).

7. Assumptions & Dependencies
   (Updated) Crucially assumes that arbigent-cli correctly implements and handles the @argfile syntax specified by the --argfile path.

(Updated) Assumes arbigent-cli handles potential conflicts if --tag is passed both on the command line (by MCP server) and within the argfile.

(Rest unchanged)

8. Implementation Example (TypeScript)
   (Note: This example reflects the simplified argument handling in the MCP server.)

#!/usr/bin/env node
// Import necessary packages
import { McpServer, McpResponse } from "@modelcontextprotocol/sdk/server";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio";
import { z } from "zod";
import { spawn } from "child_process";
import * as fs from "fs";
import * as yaml from "js-yaml";
import * as path from "path";
import yargs from 'yargs'; // Recommended for robust parsing
import { hideBin } from 'yargs/helpers';

// --- Helper Functions ---
// resolveArbigentCliPath, extractTagsFromYaml, runArbigentCli (modified)

/**
* Resolves the executable path for arbigent-cli.
* @param explicitPath Path provided via --arbigent-bin-path (or null).
* @returns The executable path string, or null if not found.
  */
  function resolveArbigentCliPath(explicitPath: string | null): string | null {
  // (Implementation remains the same as previous version)
  if (explicitPath) {
  try {
  fs.accessSync(explicitPath, fs.constants.X_OK);
  console.error(`Using explicit path: ${explicitPath}`);
  return explicitPath;
  } catch (e) {
  console.error(`Explicit path not found or not executable: ${explicitPath}`);
  return null;
  }
  }
  const command = "arbigent-cli";
  const paths = process.env.PATH?.split(path.delimiter) || [];
  for (const p of paths) {
  const fullPath = path.join(p, command);
  try {
  fs.accessSync(fullPath, fs.constants.X_OK);
  console.error(`Found in PATH: ${fullPath}`);
  return fullPath;
  } catch (e) {}
  }
  console.error(`${command} not found in PATH.`);
  return null;
  }

/**
* Extracts tags from project.yaml.
* @param projectYamlPath Path to the project YAML file.
* @returns An array of tag names. Throws error if file cannot be read/parsed.
  */
  function extractTagsFromYaml(projectYamlPath: string): string[] {
  try {
  const fileContents = fs.readFileSync(projectYamlPath, 'utf8');
  const data = yaml.load(fileContents) as any; // Adjust type based on YAML structure
  const tags = new Set<string>();
  // TODO: Implement logic to extract tags based on the actual YAML structure
  // Example: data.scenarios?.forEach((s: any) => s.tags?.forEach((t: string) => tags.add(t)));
  console.error("Extracted tags:", Array.from(tags));
  return Array.from(tags);
  } catch (error: any) {
  console.error(`Error reading or parsing project YAML: ${projectYamlPath}`, error);
  throw new Error(`Failed to read or parse project YAML: ${projectYamlPath}`); // Throw error
  }
  }

/**
* Executes the arbigent-cli command, passing the @argfile argument.
* @param cliPath Path to the arbigent-cli executable.
* @param argfilePath Path to the argument file.
* @param tag Optional tag to execute (passed in addition to @argfile).
* @returns A promise resolving with the execution result.
  */
  function runArbigentCli(
  cliPath: string,
  argfilePath: string,
  tag?: string
  ): Promise<{ stdout: string; stderr: string; exitCode: number | null }> {
  return new Promise((resolve) => {
  // Arguments for arbigent-cli: @argfile and potentially --tag
  const args = [`@${argfilePath}`];
  if (tag) {
  args.push('--tag', tag);
  }
  console.error(`Executing: ${cliPath} ${args.join(' ')}`);

  const proc = spawn(cliPath, args);
  let stdout = '';
  let stderr = '';

  proc.stdout.on('data', (data) => { stdout += data.toString(); });
  proc.stderr.on('data', (data) => { stderr += data.toString(); console.error(`stderr: ${data}`); }); // Log stderr immediately
  proc.on('close', (code) => {
  console.error(`arbigent-cli process exited with code ${code}`);
  resolve({ stdout, stderr, exitCode: code });
  });
  proc.on('error', (err) => {
  console.error('Failed to start arbigent-cli subprocess.', err);
  resolve({ stdout: '', stderr: `MCP Server Error: Failed to start arbigent-cli subprocess: ${err.message}`, exitCode: -1 });
  });
  });
  }

// --- Main Execution ---
async function main() {
// --- Argument Parsing using yargs ---
const argv = await yargs(hideBin(process.argv))
.option('project', {
alias: 'p',
type: 'string',
description: 'Path to the Arbigent project YAML file (for tag discovery)',
demandOption: true, // Make it required
})
.option('argfile', {
alias: 'a',
type: 'string',
description: 'Path to the argument file for arbigent-cli (@ syntax)',
demandOption: true, // Make it required
})
.option('arbigent-bin-path', {
type: 'string',
description: 'Optional path to the arbigent-cli executable',
nargs: 1, // Expects one argument
})
.help()
.alias('h', 'help')
.strict() // Report errors for unknown options
.argv;

const projectPath = argv.project;
const argfilePath = argv.argfile;
const explicitCliPath = argv.arbigentBinPath || null;

console.error("Parsed Arguments:", { projectPath, argfilePath, explicitCliPath });

// --- Basic check if argfile exists ---
try {
fs.accessSync(argfilePath, fs.constants.R_OK); // Check read access
} catch (error) {
console.error(`Error: Cannot access argfile: ${argfilePath}`);
process.exit(1);
}


// --- Resolve CLI Path ---
const cliPath = resolveArbigentCliPath(explicitCliPath);
if (!cliPath) {
console.error("Error: Could not find or access arbigent-cli executable.");
process.exit(1); // Exit on error
}

// --- Extract Tags ---
let tags: string[] = [];
try {
tags = extractTagsFromYaml(projectPath);
} catch (error: any) {
console.error(error.message); // Error already logged in function
process.exit(1); // Exit if project file is invalid
}

// --- Create MCP Server Instance ---
const server = new McpServer({
name: "arbigent-runner", version: "1.2.0", // Updated version
capabilities: { resources: {}, tools: {} },
});

// --- Dynamic Tool Registration ---
// 'run-arbigent-test-all' tool
server.tool( "run-arbigent-test-all", "Runs Arbigent tests using the provided argfile.", {},
async (): Promise<McpResponse> => {
console.error("Executing tool: run-arbigent-test-all");
const result = await runArbigentCli(cliPath, argfilePath); // Pass argfilePath
const outputText = `Exit Code: ${result.exitCode}\n\nSTDOUT:\n${result.stdout}\n\nSTDERR:\n${result.stderr}`;
return { content: [{ type: "text", text: outputText }] };
}
);
// Tools for each tag
tags.forEach(tag => {
const toolName = `run-arbigent-test-${tag.replace(/[^a-zA-Z0-9_]/g, '_')}`;
server.tool( toolName, `Runs Arbigent tests with tag '${tag}' using the provided argfile.`, {},
async (): Promise<McpResponse> => {
console.error(`Executing tool: ${toolName} (tag: ${tag})`);
const result = await runArbigentCli(cliPath, argfilePath, tag); // Pass argfilePath and tag
const outputText = `Exit Code: ${result.exitCode}\n\nSTDOUT:\n${result.stdout}\n\nSTDERR:\n${result.stderr}`;
return { content: [{ type: "text", text: outputText }] };
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
