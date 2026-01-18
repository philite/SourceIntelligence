package memgraph

import (
	"context"
	"encoding/csv"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

// TestImportCSVIntegration verifies the ImportCSV function against a running Memgraph instance.
// Run with: go test -v ./internal/memgraph/... -run TestImportCSVIntegration
func TestImportCSVIntegration(t *testing.T) {
	// Skip if no Memgraph available? Or assume available since user asked for verification.
	// We'll try to connect.
	
	cfg := Config{
		URI:      "bolt://localhost:7687",
		Username: "memgraph", // Adjust if needed, default user usually has empty password or specific
		Password: "sourceintel",  // From main.go default
		Database: "",
	}

	client, err := NewClient(cfg)
	if err != nil {
		t.Skipf("Skipping integration test (Memgraph not available): %v", err)
	}
	defer client.Close(context.Background())

	// Setup Temp CSVs
	tmpDir, err := os.MkdirTemp("", "memgraph_int_test")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	nodesPath := filepath.Join(tmpDir, "nodes.csv")
	relsPath := filepath.Join(tmpDir, "relationships.csv")

	// 1. Create Nodes CSV
	// Header: id,labels,properties
	f, _ := os.Create(nodesPath)
	w := csv.NewWriter(f)
	w.Write([]string{"id", "labels", "properties"})
	// Node 1: Integer line
	w.Write([]string{"n1", "TestNode", `{"name":"Node1", "line": 100}`}) 
	// Node 2: String line (should simulate string in JSON, but our types.go handles 'any')
	// Actually our CSV exporter writes numbers as numbers in JSON.
	w.Write([]string{"n2", "TestNode:Extra", `{"name":"Node2", "line": 200, "active": true}`})
	w.Flush()
	f.Close()

	// 2. Create Rels CSV
	// Header: start_id,end_id,type,properties
	f, _ = os.Create(relsPath)
	w = csv.NewWriter(f)
	w.Write([]string{"start_id", "end_id", "type", "properties"})
	w.Write([]string{"n1", "n2", "LINKS_TO", `{"weight": 5}`})
	w.Flush()
	f.Close()

	ctx := context.Background()

	// 3. Run ImportCSV
	fmt.Println("Running ImportCSV...")
	if err := client.ImportCSV(ctx, nodesPath, relsPath); err != nil {
		t.Fatalf("ImportCSV failed: %v", err)
	}

	// 4. Verify Data in Memgraph
	// Verify Node n1
	res, err := client.Query(ctx, "MATCH (n:TestNode {id: 'n1'}) RETURN n.name as name, n.line as line")
	if err != nil {
		t.Fatalf("Query failed: %v", err)
	}
	if len(res) == 0 {
		t.Fatal("Node n1 not found")
	}
	
	// Check Line Type (should be int/float, not string)
	lineVal := res[0]["line"]
	// In Go, Neo4j driver returns int64 for integers
	switch v := lineVal.(type) {
	case int64:
		if v != 100 { t.Errorf("Expected line 100, got %d", v) }
	case float64:
		if v != 100 { t.Errorf("Expected line 100, got %f", v) }
	default:
		t.Errorf("Expected numeric line, got type %T: %v", v, v)
	}

	// Verify Rel
	res, err = client.Query(ctx, "MATCH (a {id:'n1'})-[r:LINKS_TO]->(b {id:'n2'}) RETURN r.weight as weight")
	if err != nil { t.Fatalf("Rel query failed: %v", err) }
	if len(res) == 0 { t.Fatal("Relationship not found") }
	
	// Cleanup (Optional)
	client.Query(ctx, "MATCH (n:TestNode) DETACH DELETE n")
}
