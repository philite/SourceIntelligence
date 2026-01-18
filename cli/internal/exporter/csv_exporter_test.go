package exporter

import (
	"encoding/csv"
	"os"
	"path/filepath"
	"strings"
	"testing"

	pb "github.com/sourceintel/cli/pkg/proto"
)

func TestCSVExporter(t *testing.T) {
	// Create temporary directory for test output
	tmpDir, err := os.MkdirTemp("", "csv_export_test")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	// Initialize Exporter
	exporter, err := NewCSVExporter(tmpDir)
	if err != nil {
		t.Fatalf("Failed to create CSV exporter: %v", err)
	}

	// Test Case 1: Write Node with "line" property
	testNode := &pb.GraphNode{
		Id:     "node1",
		Type:   "Function",
		Labels: []string{"Function", "Kotlin"},
		Properties: map[string]string{
			"name": "myFunction",
			"line": "123", // Should be converted to int
			"file": "Test.kt",
		},
	}

	if err := exporter.WriteNode(testNode); err != nil {
		t.Errorf("WriteNode failed: %v", err)
	}

	// Test Case 2: Write Relationship
	testRel := &pb.GraphRelationship{
		Id:          "rel1",
		Type:        "CALLS",
		StartNodeId: "node1",
		EndNodeId:   "node2",
		Properties: map[string]string{
			"line": "456", // Should NOT be converted (logic only applies to Nodes currently, or does it?)
			// Wait, protoNodeToMap is only for Nodes. Rel properties are marshaled directly.
			// Let's verify that behavior.
		},
	}

	if err := exporter.WriteRelationship(testRel); err != nil {
		t.Errorf("WriteRelationship failed: %v", err)
	}

	// Close exporter to flush buffers
	exporter.Close()

	// Verify Nodes CSV Content
	nodesContent, err := os.ReadFile(filepath.Join(tmpDir, "nodes.csv"))
	if err != nil {
		t.Fatalf("Failed to read nodes.csv: %v", err)
	}
	
	// Parse CSV
	r := csv.NewReader(strings.NewReader(string(nodesContent)))
	records, err := r.ReadAll()
	if err != nil {
		t.Fatalf("Failed to parse nodes CSV: %v", err)
	}

	// Check Header
	if len(records) < 2 {
		t.Fatal("Nodes CSV is empty or missing data")
	}
	header := records[0]
	if header[0] != "id" || header[1] != "labels" || header[2] != "properties" {
		t.Errorf("Unexpected header: %v", header)
	}

	// Check Data Row
	row := records[1]
	if row[0] != "node1" {
		t.Errorf("Expected id node1, got %s", row[0])
	}
	if row[1] != "Function:Kotlin" {
		t.Errorf("Expected labels Function:Kotlin, got %s", row[1])
	}

	// Check JSON Properties
	jsonProps := row[2]
	if !strings.Contains(jsonProps, `"line":123`) {
		t.Errorf("Expected numeric line property, got: %s", jsonProps)
	}
	if strings.Contains(jsonProps, `"line":"123"`) {
		t.Errorf("Found string line property, expected int: %s", jsonProps)
	}

	t.Logf("Verified generated CSV: %s", string(nodesContent))
}
