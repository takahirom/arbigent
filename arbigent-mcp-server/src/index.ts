#!/usr/bin/env node
// Import necessary packages
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod"; // For parameter validation
import { spawn } from "child_process"; // For executing external commands
import * as fs from "fs"; // For file system operations
import * as yaml from "js-yaml"; // For YAML parsing
import * as path from "path"; // For path manipulation
import yargs from "yargs";
import { hideBin } from "yargs/helpers";

// --- Argument Parsing ---
const argv = yargs(hideBin(process.argv))
  .option("project", {
    alias: "p",
    type: "string",
    description: "Path to the Arbigent project YAML file",
    demandOption: true
  })
  .option("config", {
    alias: "c",
    type: "string",
    description: "Path to the Arbigent config YAML file",
    demandOption: true
  })
  .option("arbigent-bin-path", {
    alias: "a",
    type: "string",
    description: "Explicit path to the arbigent-cli executable",
    demandOption: false
  })
  .help()
  .alias("help", "h")
  .parseSync();

// --- Helper Functions ---

/**
 * Extracts tags from project.yaml.
 * @param projectYamlPath Path to the project YAML file.
 * @returns An array of tag names.
 */
function extractTagsFromYaml(projectYamlPath: string): string[] {
  try {
    const fileContents = fs.readFileSync(projectYamlPath, 'utf8');
    const data = yaml.load(fileContents) as any; // Adjust type based on YAML structure
    const tags = new Set<string>();

    // Extract tags from scenarios
    if (data.scenarios && Array.isArray(data.scenarios)) {
      data.scenarios.forEach((scenario: any) => {
        if (scenario.tags && Array.isArray(scenario.tags)) {
          scenario.tags.forEach((tag: string) => tags.add(tag));
        }
      });
    }

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

/**
 * Resolves the executable path for arbigent-cli.
 * @param explicitPath Path provided via --arbigent-bin-path (or null).
 * @returns The executable path string, or null if not found.
 */
function resolveArbigentCliPath(explicitPath: string | undefined): string | null {
  if (explicitPath) {
    // Verify if explicitPath actually exists and is executable
    try {
      fs.accessSync(explicitPath, fs.constants.X_OK);
      console.error(`Using explicit path: ${explicitPath}`);
      return explicitPath;
    } catch (e) {
      console.error(`Provided path is not executable: ${explicitPath}`);
      return null;
    }
  }

  // Search in PATH environment variable (may need cross-platform handling)
  const command = "arbigent-cli"; // Or the actual command name
  const isWindows = process.platform === "win32";
  const exeExtension = isWindows ? ".exe" : "";

  const paths = process.env.PATH?.split(path.delimiter) || [];
  for (const p of paths) {
    const fullPath = path.join(p, command + exeExtension);
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

// --- Main Execution ---
async function main() {
  console.error("Arbigent MCP Server starting...");
  console.error("Arguments:", argv);

  // --- Resolve CLI Path ---
  const cliPath = resolveArbigentCliPath(argv["arbigent-bin-path"]);
  if (!cliPath) {
    console.error("Error: Could not find arbigent-cli executable.");
    process.exit(1); // Exit on error
  }

  // --- Extract Tags from Project YAML ---
  const projectPath = argv.project as string;
  const configPath = argv.config as string;

  console.error(`Reading project YAML: ${projectPath}`);
  const tags = extractTagsFromYaml(projectPath);
  if (tags.length === 0) {
    console.error("Warning: No tags found in project YAML. Only 'run-arbigent-test-all' tool will be available.");
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

  // --- Register Tools ---

  // Register 'run-arbigent-test-all' tool
  server.tool(
    "run-arbigent-test-all",
    "Runs all Arbigent tests defined in the project YAML.",
    {}, // No input parameters
    async (): Promise<CallToolResult> => {
      console.error("Executing tool: run-arbigent-test-all");
      const result = await runArbigentCli(cliPath, projectPath, configPath);

      // Format the response
      const status = result.exitCode === 0 ? "Success" : "Failure";
      const outputText = `Test Execution Status: ${status}\n\nExit Code: ${result.exitCode}\n\nOutput:\n${result.stdout}\n\nErrors:\n${result.stderr}`;

      return {
        content: [{ type: "text", text: outputText }],
      };
    }
  );

  // Register tools for each tag
  tags.forEach(tag => {
    const toolName = `run-arbigent-test-${tag.replace(/[^a-zA-Z0-9_]/g, '_')}`;
    server.tool(
      toolName,
      `Runs Arbigent tests with the tag '${tag}'.`,
      {}, // No input parameters
      async (): Promise<CallToolResult> => {
        console.error(`Executing tool: ${toolName} (tag: ${tag})`);
        const result = await runArbigentCli(cliPath, projectPath, configPath, tag);

        // Format the response
        const status = result.exitCode === 0 ? "Success" : "Failure";
        const outputText = `Test Execution Status: ${status}\n\nExit Code: ${result.exitCode}\n\nOutput:\n${result.stdout}\n\nErrors:\n${result.stderr}`;

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
