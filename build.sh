#!/bin/bash
# SourceIntelligence Build Script for macOS/Linux
# Usage: ./build.sh [-s|--skip-docker] [-i|--index <path>]

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SKIP_DOCKER=false
INDEX_PROJECT=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -s|--skip-docker) SKIP_DOCKER=true; shift ;;
        -i|--index) INDEX_PROJECT="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

echo "========================================"
echo "  SourceIntelligence Build Pipeline"
echo "========================================"
echo ""


# Step 2: Build Go CLI
echo -e "\033[33m[2/4] Building Go CLI...\033[0m"
cd "$ROOT/cli"
go build -o sourceintel ./cmd/sourceintel
echo -e "      \033[32m✅ CLI built: cli/sourceintel\033[0m"

# Step 3: Check Docker & Memgraph
echo -e "\033[33m[3/4] Checking Memgraph...\033[0m"

if [ "$SKIP_DOCKER" = false ]; then
    if ! docker ps | grep -q memgraph; then
        echo "      Memgraph not running. Starting..."
        
        if docker ps -a --format "{{.Names}}" | grep -q memgraph; then
            docker start memgraph
        else
            docker run -d --name memgraph \
                -p 7687:7687 \
                -p 7444:7444 \
                -p 3000:3000 \
                -v memgraph-data:/var/lib/memgraph \
                memgraph/memgraph-mage:latest
        fi
        
        sleep 3
    fi
    
    # Verify connection
    if "$ROOT/cli/sourceintel" status >/dev/null 2>&1; then
        echo -e "      \033[32m✅ Memgraph connected\033[0m"
    else
        echo -e "      \033[33m⚠️ Memgraph may not be ready yet\033[0m"
    fi
else
    echo -e "      \033[90m⏭️ Skipped (use -s to skip Docker)\033[0m"
fi

# Step 4: Optional - Index a project
if [ -n "$INDEX_PROJECT" ]; then
    echo -e "\033[33m[4/4] Indexing project: $INDEX_PROJECT\033[0m"
    "$ROOT/cli/sourceintel" index "$INDEX_PROJECT" --android --clear
    echo -e "      \033[32m✅ Project indexed\033[0m"
else
    echo -e "\033[90m[4/4] No project to index (use -i <path>)\033[0m"
fi

echo ""
echo "========================================"
echo "  Build Complete! 🎉"
echo "========================================"
echo ""
echo "Next steps:"
echo "  1. Check status:  ./cli/sourceintel status"
echo "  2. Index project: ./cli/sourceintel index <path> --android"
echo "  3. Start MCP:     ./cli/sourceintel mcp"
echo ""
