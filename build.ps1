# SourceIntelligence Build Script
# Usage: .\build.ps1 [-SkipDocker] [-IndexProject <path>]

param(
    [switch]$SkipDocker,
    [string]$IndexProject
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  SourceIntelligence Build Pipeline" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""


# Step 2: Build Go CLI
Write-Host "[2/4] Building Go CLI..." -ForegroundColor Yellow
Push-Location "$Root\cli"
try {
    go build -o sourceintel.exe ./cmd/sourceintel
    if ($LASTEXITCODE -ne 0) { throw "Go build failed" }
    Write-Host "      ✅ CLI built: cli\sourceintel.exe" -ForegroundColor Green
} finally {
    Pop-Location
}

# Step 3: Check Docker & Memgraph
Write-Host "[3/4] Checking Memgraph..." -ForegroundColor Yellow

if (-not $SkipDocker) {
    $dockerRunning = docker ps 2>&1 | Select-String "memgraph"
    
    if (-not $dockerRunning) {
        Write-Host "      Memgraph not running. Starting..." -ForegroundColor Yellow
        
        # Check if container exists but stopped
        $containerExists = docker ps -a --format "{{.Names}}" | Select-String "memgraph"
        
        if ($containerExists) {
            docker start memgraph
        } else {
            docker run -d --name memgraph `
                -p 7687:7687 `
                -p 7444:7444 `
                -p 3000:3000 `
                -v memgraph-data:/var/lib/memgraph `
                memgraph/memgraph-mage:latest
        }
        
        Start-Sleep -Seconds 3
    }
    
    # Verify connection
    try {
        & "$Root\cli\sourceintel.exe" status 2>&1 | Out-Null
        Write-Host "      ✅ Memgraph connected" -ForegroundColor Green
    } catch {
        Write-Host "      ⚠️ Memgraph may not be ready yet" -ForegroundColor Yellow
    }
} else {
    Write-Host "      ⏭️ Skipped (use -SkipDocker to skip)" -ForegroundColor Gray
}

# Step 4: Optional - Index a project
if ($IndexProject) {
    Write-Host "[4/4] Indexing project: $IndexProject" -ForegroundColor Yellow
    & "$Root\cli\sourceintel.exe" index $IndexProject --android --clear
    if ($LASTEXITCODE -eq 0) {
        Write-Host "      ✅ Project indexed" -ForegroundColor Green
    }
} else {
    Write-Host "[4/4] No project to index (use -IndexProject <path>)" -ForegroundColor Gray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Build Complete! 🎉" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next steps:" -ForegroundColor White
Write-Host "  1. Check status:  .\cli\sourceintel.exe status" -ForegroundColor Gray
Write-Host "  2. Index project: .\cli\sourceintel.exe index <path> --android" -ForegroundColor Gray
Write-Host "  3. Start MCP:     .\cli\sourceintel.exe mcp" -ForegroundColor Gray
Write-Host ""
