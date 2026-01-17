package analyzer

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"github.com/sourceintel/cli/internal/memgraph"
)

// Analyzer wraps the Kotlin analyzer subprocess
type Analyzer struct {
	analyzerDir string
}

// NewAnalyzer creates a new analyzer wrapper
// analyzerDir should be the path to the analyzer directory (containing gradlew)
func NewAnalyzer(analyzerDir string) *Analyzer {
	return &Analyzer{
		analyzerDir: analyzerDir,
	}
}

// AnalyzeProject runs the Kotlin analyzer on a project and returns the graph
func (a *Analyzer) AnalyzeProject(projectPath string, moduleName string, semantic bool) (*memgraph.CodeGraph, error) {
	// Convert to absolute path
	absProjectPath, err := filepath.Abs(projectPath)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve project path: %w", err)
	}

	// Build Gradle run command
	gradlew := "gradlew"
	if os.PathSeparator == '\\' {
		gradlew = "gradlew.bat"
	}

	// Build the analyzer arguments: analyze <path> [-m module] [--semantic]
	analyzerArgs := "analyze " + absProjectPath
	if moduleName != "" {
		analyzerArgs += " -m " + moduleName
	}
	if semantic {
		analyzerArgs += " --semantic"
	}

	args := []string{"run", "--quiet", "--args=" + analyzerArgs}

	cmd := exec.Command(filepath.Join(a.analyzerDir, gradlew), args...)
	cmd.Dir = a.analyzerDir
	
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("analyzer failed: %w\nstderr: %s", err, stderr.String())
	}

	// Parse the JSON output
	var graph memgraph.CodeGraph
	if err := json.Unmarshal(stdout.Bytes(), &graph); err != nil {
		return nil, fmt.Errorf("failed to parse analyzer output: %w\noutput: %s", err, stdout.String())
	}

	return &graph, nil
}

// FindAnalyzerDir looks for the analyzer directory in common locations
func FindAnalyzerDir() (string, error) {
	locations := []string{
		"analyzer",
		"../analyzer",
		filepath.Join("..", "analyzer"),
	}

	for _, loc := range locations {
		gradlew := filepath.Join(loc, "gradlew.bat")
		if _, err := os.Stat(gradlew); err == nil {
			return filepath.Abs(loc)
		}
		gradlew = filepath.Join(loc, "gradlew")
		if _, err := os.Stat(gradlew); err == nil {
			return filepath.Abs(loc)
		}
	}

	return "", fmt.Errorf("analyzer directory not found (should contain gradlew)")
}

