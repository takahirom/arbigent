# Arbigent MCP Server

A Model Context Protocol (MCP) server that acts as a bridge between MCP clients (e.g., Claude for Desktop) and the arbigent-cli, enabling users to trigger Arbigent automated tests via interactions with an LLM.

## Overview

The Arbigent MCP Server allows LLMs to execute Arbigent tests through the MCP protocol. It exposes tools that can run all tests or specific test suites based on tags defined in the project YAML file.

## Installation

```bash
# Install dependencies
npm install

# Build the project
npm run build
```

## Usage

```bash
# Run the server with required arguments
node dist/index.js --project <path-to-project-yaml> --config <path-to-config-yaml>

# Or with an explicit path to arbigent-cli
node dist/index.js --project <path-to-project-yaml> --config <path-to-config-yaml> --arbigent-bin-path <path-to-arbigent-cli>

# Using npx (after publishing)
npx arbigent-mcp-server --project <path-to-project-yaml> --config <path-to-config-yaml>
```

### Command-line Arguments

- `--project`, `-p`: (Required) Path to the Arbigent project YAML file
- `--config`, `-c`: (Required) Path to the Arbigent config YAML file
- `--arbigent-bin-path`, `-a`: (Optional) Explicit path to the arbigent-cli executable
- `--help`, `-h`: Show help

## Development

```bash
# Run in development mode
npm run dev -- --project <path-to-project-yaml> --config <path-to-config-yaml>
```

## Features

- Dynamically discovers test tags from the specified project YAML
- Exposes MCP tools to run all tests or tests associated with specific tags
- Executes arbigent-cli as a subprocess
- Returns test results or errors back to the MCP client

## Requirements

- Node.js (version 18.x or higher recommended)
- arbigent-cli executable (either in PATH or specified via --arbigent-bin-path)