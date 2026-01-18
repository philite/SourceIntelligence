# SourceIntelligence Quick Start Guide

A complete guide from building to using SourceIntelligence with your Android/Kotlin projects.

---

## 📋 Prerequisites

- **Docker** (for Memgraph)
- **Go 1.21+** (for CLI)
- **JDK 17+** (for Kotlin analyzer)
- **Gradle 8+** (included in analyzer)

---

## 🚀 Step 1: Start Memgraph

Memgraph is the graph database that stores your code knowledge graph.

```bash
# Start Memgraph with Docker
docker run -d --name memgraph \
  -p 7687:7687 \
  -p 7444:7444 \
  -v memgraph-data:/var/lib/memgraph \
  memgraph/memgraph-mage:latest

# Verify it's running
docker ps | grep memgraph
```

> **Note**: The `-v memgraph-data:/var/lib/memgraph` ensures your graph data persists between restarts.

---

## 🔨 Step 2: Build the Tools

### Build the Kotlin Analyzer
```bash
cd analyzer
./gradlew build
```

### Build the Go CLI
```bash
cd cli
go build -o sourceintel ./cmd/sourceintel
```

You now have:
- `analyzer/` - Kotlin project that analyzes your code
- `cli/sourceintel` (or `sourceintel.exe` on Windows) - Go CLI tool

---

## 📊 Step 3: Index Your Project

This is the **one-time** (or periodic) step that builds the knowledge graph.

```bash
# Basic indexing (fast, less semantic info)
./sourceintel index /path/to/your/android-project

# Full K2 semantic analysis (slower, full type resolution)
./sourceintel index /path/to/your/android-project --full-k2

# With Android UI enrichment (layouts, navigation, composables)
./sourceintel index /path/to/your/android-project --full-k2 --android

# Clear and re-index (prevents duplicates)
./sourceintel index /path/to/your/android-project --clear --full-k2
```

### Check Index Status
```bash
./sourceintel status
```

Output:
```
✅ Memgraph: Connected
   URI: bolt://localhost:7687

📊 Graph Statistics:
   Type                 523
   File                 187
   Package              45
   Function             1234

   Total Nodes:         1989
   Total Relationships: 4521
```

---

## 🖥️ Step 4: Use via Command Line

### Find Symbols
```bash
# Find by name
./sourceintel find "UserRepository"

# Find by type
./sourceintel find "ViewModel" --type ViewModel
```

### Run Cypher Queries
```bash
# Find all ViewModels
./sourceintel query "MATCH (n:ViewModel) RETURN n.name, n.fqn"

# Find what calls a specific class
./sourceintel query "MATCH (caller)-[:CALLS]->(target {name: 'LoginUseCase'}) RETURN caller.name"
```

---

## 🤖 Step 5: Use with AI (MCP Server)

### Start the MCP Server
```bash
./sourceintel mcp
```

This starts a Model Context Protocol server on stdio, which AI assistants can connect to.

### Configure Claude Desktop

Add to your `claude_desktop_config.json`:

**Windows**:
```json
{
  "mcpServers": {
    "sourceintel": {
      "command": "C:\\path\\to\\sourceintel.exe",
      "args": ["mcp"],
      "env": {}
    }
  }
}
```

**macOS/Linux**:
```json
{
  "mcpServers": {
    "sourceintel": {
      "command": "/path/to/sourceintel",
      "args": ["mcp"],
      "env": {}
    }
  }
}
```

### Restart Claude Desktop
After adding the config, restart Claude Desktop. You should see "SourceIntel" in the available tools.

---

## 💬 Step 6: What to Prompt the AI

### Getting Started Prompts

```
"Connect to SourceIntel and show me the graph status."

"What ViewModels exist in this codebase?"

"Find all classes related to authentication."
```

### Understanding Code Structure

```
"Show me the package structure of com.example.feature.auth"

"What does the LoginViewModel do? Show me its relationships."

"Find the inheritance hierarchy of BaseRepository."
```

### Tracing Data Flow

```
"Trace the call graph from LoginViewModel - what does it call?"

"What code references UserRepository?"

"Show me the Clean Architecture flow from MainViewModel through use cases to repositories."
```

### Android-Specific Queries

```
"What layouts does MainActivity use?"

"Show me all Composable functions in the project."

"What navigation destinations exist in this app?"

"Find all Activities and their associated layouts."
```

### Advanced Analysis

```
"Find all ViewModels and their dependencies."

"What interfaces does NetworkRepository implement?"

"Find all functions with 'suspend' in their signature."

"Show me the module summary for 'feature-profile'."
```

### Debugging and Understanding

```
"I'm working on UserProfileScreen. What context do I need to understand it?"

"What calls the refreshData function in SyncManager?"

"Find all usages of the TokenRepository."
```

---

## 🔄 Workflow Summary

```
┌─────────────────────────────────────────────────────────────┐
│                    ONE-TIME SETUP                           │
├─────────────────────────────────────────────────────────────┤
│  1. docker run memgraph                                     │
│  2. ./gradlew build (analyzer)                              │
│  3. go build (cli)                                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                 INDEX YOUR PROJECT                          │
├─────────────────────────────────────────────────────────────┤
│  ./sourceintel index /my/project --full-k2 --android        │
│  (Run once, or when code changes significantly)             │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    DAILY USE                                │
├─────────────────────────────────────────────────────────────┤
│  Option A: CLI                                              │
│    ./sourceintel find "MyClass"                             │
│    ./sourceintel query "MATCH..."                           │
│                                                             │
│  Option B: AI Assistant                                     │
│    ./sourceintel mcp  (start server)                        │
│    Open Claude Desktop, ask questions!                      │
└─────────────────────────────────────────────────────────────┘
```

---

## 📝 Tips

1. **First time?** Use `--clear` to ensure clean state
2. **Code changed?** Use `--merge` for incremental updates
3. **Large codebase?** `--full-k2` takes longer but gives better analysis
4. **Check before asking AI**: Run `./sourceintel status` to verify index exists
5. **MCP server is instant**: It just connects to existing graph, no re-scanning

---

## 🛠️ Troubleshooting

### "Connection refused" error
```bash
# Check if Memgraph is running
docker ps | grep memgraph

# Start if not running
docker start memgraph
```

### "Graph is empty"
```bash
# Index your project first
./sourceintel index /your/project --full-k2

# Verify
./sourceintel status
```

### AI can't find the MCP server
1. Check the path in `claude_desktop_config.json` is absolute
2. Ensure `sourceintel` binary exists at that path
3. Restart Claude Desktop after config changes

---

## 📚 Available MCP Tools

| Tool | Purpose | Example |
|------|---------|---------|
| `find_symbol` | Search by name (supports regex, multi-keyword) | "Find User,Auth,Login" |
| `get_symbol_details` | Get full details by FQN | Details of com.example.MyClass |
| `get_references` | Find who uses a symbol | What calls LoginUseCase? |
| `get_inheritance` | Type hierarchy | Parents/children of BaseViewModel |
| `get_call_graph` | Function call chains | What does onCreate call? |
| `get_module_summary` | Module overview | Stats for feature-auth |
| `get_package_contents` | List package symbols | What's in com.example.data? |
| `get_android_screen` | Screen UI info | Layouts/Composables for MainActivity |
| `get_context_window` | AI-friendly summary | Full context for a symbol |
| `search_by_label` | Fast label search | Find all ViewModels |
| `run_cypher` | Custom Cypher query | Any complex query |
