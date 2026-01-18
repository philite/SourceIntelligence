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
	// Always use absolute path to avoid issues with child process working directory
	if absDir, err := filepath.Abs(analyzerDir); err == nil {
		analyzerDir = absDir
	}
	return &Analyzer{
		analyzerDir: analyzerDir,
	}
}

// AnalyzeProject runs the Kotlin analyzer on a project and returns the graph
func (a *Analyzer) AnalyzeProject(projectPath string, moduleName string, semantic bool, fullK2 bool, android bool) (*memgraph.CodeGraph, error) {
	// Convert to absolute path
	absProjectPath, err := filepath.Abs(projectPath)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve project path: %w", err)
	}

	var args []string
	var cmdPath string

	// Check if distribution script exists
	distScript := filepath.Join(a.analyzerDir, "build", "install", "analyzer", "bin", "analyzer")
	if os.PathSeparator == '\\' {
		distScript += ".bat"
	}

	if _, err := os.Stat(distScript); err == nil {
		// Use distribution script (faster)
		cmdPath = distScript
		args = []string{"analyze", absProjectPath}
		if moduleName != "" {
			args = append(args, "--module", moduleName)
		}
		if semantic {
			args = append(args, "--semantic")
		}
		if fullK2 {
			args = append(args, "--full-k2")
		}
		if android {
			args = append(args, "--android")
		}
	} else {
		// Fallback to Gradle (slower)
		gradlew := "gradlew"
		if os.PathSeparator == '\\' {
			gradlew = "gradlew.bat"
		}
		cmdPath = filepath.Join(a.analyzerDir, gradlew)
		
		// Build Gradle run args
		gradleArgs := "analyze " + absProjectPath
		if moduleName != "" {
			gradleArgs += " -m " + moduleName
		}
		if semantic {
			gradleArgs += " --semantic"
		}
		if fullK2 {
			gradleArgs += " --full-k2"
		}
		if android {
			gradleArgs += " --android"
		}
		args = []string{"run", "--quiet", "--args=" + gradleArgs}
	}

	cmd := exec.Command(cmdPath, args...)
	// Only set Dir if using Gradle, otherwise dist script handles it relative to itself or CWD
	if cmdPath != distScript {
		cmd.Dir = a.analyzerDir
	}
	
	var stdout bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = os.Stderr // Stream stderr directly to console

	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("analyzer failed: %w", err)
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

