# SourceIntelligence

AI-powered code intelligence for Kotlin/Android projects. Build a queryable knowledge graph from your source code, enabling AI agents to understand code with precision.

## Features

- **K2 Analysis API**: Full Kotlin type resolution and reference binding
- **Extensible Schema**: Language-agnostic core with Kotlin/Android extensions
- **MCP Server**: Integrate with AI agents (Claude, GPT, etc.)
- **Memgraph**: Fast graph queries with Cypher

## Quick Start

### Prerequisites

- JDK 21+
- Go 1.23+
- Docker & Docker Compose

### 1. Start Memgraph

```bash
cd docker
docker compose up -d
```

### 2. Build the Analyzer

```bash
cd analyzer
./gradlew build
```

### 3. Build the CLI

```bash
cd cli
go build -o sourceintel ./cmd/sourceintel
```

### 4. Index a Project

```bash
./sourceintel index /path/to/your/android-project
```

### 5. Query the Graph

```bash
./sourceintel query "MATCH (vm:ViewModel)-[:CALLS_USE_CASE]->(uc:UseCase) RETURN vm.name, uc.name"
```

## Project Structure

```
SourceIntelligence/
├── analyzer/           # Kotlin/JVM analyzer (K2 Analysis API)
├── cli/                # Go CLI + MCP Server
├── docker/             # Memgraph Docker Compose
├── scripts/            # Build and install scripts
└── testdata/           # Test projects
```

## Architecture

```
┌────────────┐   subprocess   ┌────────────────┐   JSON
│  Go CLI    │ ──────────────▶│ Kotlin Analyzer│ ───────▶
└─────┬──────┘                └────────────────┘
      │ Bolt
      ▼
┌────────────┐
│  Memgraph  │
└────────────┘
```

## License

Apache 2.0
