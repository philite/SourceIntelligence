package memgraph

import (
	"context"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/neo4j/neo4j-go-driver/v5/neo4j"
)

// Client wraps the Neo4j driver for Memgraph connectivity
type Client struct {
	driver neo4j.DriverWithContext
	dbName string
}

// Config holds Memgraph connection settings
type Config struct {
	URI      string
	Username string
	Password string
	Database string
}

// NewClient creates a new Memgraph client
func NewClient(cfg Config) (*Client, error) {
	driver, err := neo4j.NewDriverWithContext(
		cfg.URI,
		neo4j.BasicAuth(cfg.Username, cfg.Password, ""),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create driver: %w", err)
	}

	// Verify connectivity
	ctx := context.Background()
	if err := driver.VerifyConnectivity(ctx); err != nil {
		return nil, fmt.Errorf("failed to connect to Memgraph: %w", err)
	}

	return &Client{
		driver: driver,
		dbName: cfg.Database,
	}, nil
}

// Close closes the driver connection
func (c *Client) Close(ctx context.Context) error {
	return c.driver.Close(ctx)
}

// ----------------------------------------------------------------------------
// NEW: Bulk Import Logic (CSV based)
// ----------------------------------------------------------------------------

// ImportCSV reads CSV files from Host path and imports them into Memgraph via Go batching.
// This creates a robust import that works without APOC or specific Memgraph modules.
func (c *Client) ImportCSV(ctx context.Context, nodesPath, relsPath string) error {
	session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	// 1. Switch to Analytical mode for performance
	fmt.Println("   [Memgraph] Switching to ANALYTICAL mode...")
	if _, err := session.Run(ctx, "STORAGE MODE IN_MEMORY_ANALYTICAL", nil); err != nil {
		return fmt.Errorf("failed to set analytical mode: %w", err)
	}

	// Helper to restore mode on exit
	defer func() {
		fmt.Println("   [Memgraph] Switching back to TRANSACTIONAL mode...")
		session.Run(ctx, "STORAGE MODE IN_MEMORY_TRANSACTIONAL", nil)
	}()

	// 2. Import Nodes
	fmt.Println("   [Memgraph] Importing Nodes from CSV stream...")
	if err := c.importNodesFromCSV(ctx, session, nodesPath); err != nil {
		return err
	}

	// 3. Import Relationships
	fmt.Println("   [Memgraph] Importing Relationships from CSV stream...")
	if err := c.importRelsFromCSV(ctx, session, relsPath); err != nil {
		return err
	}

	return nil
}

const csvReadBatch = 5000 // Read 5k lines at a time

func (c *Client) importNodesFromCSV(ctx context.Context, session neo4j.SessionWithContext, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("failed to open nodes csv: %w", err)
	}
	defer f.Close()

	r := csv.NewReader(f)
	// Read header
	if _, err := r.Read(); err != nil {
		return fmt.Errorf("failed to read header: %w", err)
	}

	nodesBatch := make([]GenericNode, 0, csvReadBatch)

	for {
		record, err := r.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("csv read error: %w", err)
		}

		// Row: id, labels, properties(json)
		if len(record) < 3 {
			continue
		}

		var props map[string]any
		if err := json.Unmarshal([]byte(record[2]), &props); err != nil {
			// Warn but continue? Or fail? Let's Log
			fmt.Printf("Warning: failed to parse properties for node %s: %v\n", record[0], err)
			props = make(map[string]any)
		}

		node := GenericNode{
			ID:         record[0],
			Labels:     strings.Split(record[1], ":"),
			Properties: props,
		}
		nodesBatch = append(nodesBatch, node)

		if len(nodesBatch) >= csvReadBatch {
			if err := c.batchCreateNodes(ctx, session, nodesBatch, false); err != nil {
				return err
			}
			nodesBatch = nodesBatch[:0]
			fmt.Print(".")
		}
	}

	// Flush remaining
	if len(nodesBatch) > 0 {
		if err := c.batchCreateNodes(ctx, session, nodesBatch, false); err != nil {
			return err
		}
	}
	fmt.Println()
	return nil
}

func (c *Client) importRelsFromCSV(ctx context.Context, session neo4j.SessionWithContext, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("failed to open rels csv: %w", err)
	}
	defer f.Close()

	r := csv.NewReader(f)
	// Read header
	if _, err := r.Read(); err != nil {
		return fmt.Errorf("failed to read header: %w", err)
	}

	relsBatch := make([]GraphRelationship, 0, csvReadBatch)

	for {
		record, err := r.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("csv read error: %w", err)
		}

		// Row: start_id, end_id, type, properties(json)
		if len(record) < 4 {
			continue
		}

		var props map[string]any
		if err := json.Unmarshal([]byte(record[3]), &props); err != nil {
			props = make(map[string]any)
		}

		rel := GraphRelationship{
			StartNodeID: record[0],
			EndNodeID:   record[1],
			Type:        record[2],
			Properties:  props,
			// ID is generated or not used in CREATE?
			// Memgraph relationships store internal IDs usually.
			// GraphRelationship struct has ID field.
			// If we provided one in CSV, we could use it?
			// The CSV doesn't seem to have a dedicated Rel ID column in header (start,end,type,props)
			// Wait, in grpc_analyzer.go, we did NOT write Rel ID to CSV.
			// Step 416: e.RelW.Write([]string{rel.StartNodeId, rel.EndNodeId, rel.Type, string(propsJson)})
			// So Rel ID is effectively lost or assumed generated.
			// GraphRelationship struct has ID field. We can leave it empty.
		}
		relsBatch = append(relsBatch, rel)

		if len(relsBatch) >= csvReadBatch {
			if err := c.batchCreateRelationships(ctx, session, relsBatch, false); err != nil {
				return err
			}
			relsBatch = relsBatch[:0]
			fmt.Print(".")
		}
	}

	// Flush remaining
	if len(relsBatch) > 0 {
		if err := c.batchCreateRelationships(ctx, session, relsBatch, false); err != nil {
			return err
		}
	}
	fmt.Println()
	return nil
}

// ----------------------------------------------------------------------------
// Legacy: In-Memory Load Logic (Keep for backward compatibility)
// ----------------------------------------------------------------------------

// LoadGraph loads a CodeGraph JSON into Memgraph
func (c *Client) LoadGraph(ctx context.Context, graphJSON []byte, useMerge bool) error {
	var graph CodeGraph
	if err := json.Unmarshal(graphJSON, &graph); err != nil {
		return fmt.Errorf("failed to parse graph JSON: %w", err)
	}

	session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	// Parse all nodes first
	nodes := make([]GenericNode, 0, len(graph.Nodes))
	for _, nodeRaw := range graph.Nodes {
		var node GenericNode
		if err := json.Unmarshal(nodeRaw, &node); err != nil {
			return fmt.Errorf("failed to parse node: %w", err)
		}
		nodes = append(nodes, node)
	}

	// Batch create nodes
	if err := c.batchCreateNodes(ctx, session, nodes, useMerge); err != nil {
		return err
	}

	// Batch create relationships
	if err := c.batchCreateRelationships(ctx, session, graph.Relationships, useMerge); err != nil {
		return err
	}

	return nil
}

const batchSize = 500

func (c *Client) batchCreateNodes(ctx context.Context, session neo4j.SessionWithContext, nodes []GenericNode, useMerge bool) error {
	groups := make(map[string][]map[string]any)
	for _, node := range nodes {
		labels := ""
		for i, label := range node.Labels {
			if i > 0 {
				labels += ":"
			}
			labels += label
		}
		props := node.ToProperties()
		groups[labels] = append(groups[labels], props)
	}

	totalCreated := 0
	for labels, nodeProps := range groups {
		for i := 0; i < len(nodeProps); i += batchSize {
			end := i + batchSize
			if end > len(nodeProps) {
				end = len(nodeProps)
			}
			batch := nodeProps[i:end]

			var query string
			if useMerge {
				query = fmt.Sprintf(`
					UNWIND $batch AS item
					MERGE (n:%s {id: item.id})
					SET n = item
				`, labels)
			} else {
				query = fmt.Sprintf(`
					UNWIND $batch AS item
					CREATE (n:%s)
					SET n = item
				`, labels)
			}

			_, err := session.Run(ctx, query, map[string]any{"batch": batch})
			if err != nil {
				return fmt.Errorf("failed to batch create nodes with labels %s: %w", labels, err)
			}
			totalCreated += len(batch)
		}
	}
	return nil
}

func (c *Client) batchCreateRelationships(ctx context.Context, session neo4j.SessionWithContext, rels []GraphRelationship, useMerge bool) error {
	groups := make(map[string][]map[string]any)
	for _, rel := range rels {
		props := make(map[string]any)
		props["id"] = rel.ID
		props["startId"] = rel.StartNodeID
		props["endId"] = rel.EndNodeID
		for k, v := range rel.Properties {
			props[k] = v
		}
		groups[rel.Type] = append(groups[rel.Type], props)
	}

	for relType, relProps := range groups {
		for i := 0; i < len(relProps); i += batchSize {
			end := i + batchSize
			if end > len(relProps) {
				end = len(relProps)
			}
			batch := relProps[i:end]

			var query string
			if useMerge {
				query = fmt.Sprintf(`
					UNWIND $batch AS item
					MATCH (a {id: item.startId}), (b {id: item.endId})
					MERGE (a)-[r:%s]->(b)
					SET r = item
				`, relType)
			} else {
				query = fmt.Sprintf(`
					UNWIND $batch AS item
					MATCH (a {id: item.startId}), (b {id: item.endId})
					CREATE (a)-[r:%s]->(b)
					SET r = item
				`, relType)
			}

			_, err := session.Run(ctx, query, map[string]any{"batch": batch})
			if err != nil {
				return fmt.Errorf("failed to batch create relationships of type %s: %w", relType, err)
			}
		}
	}
	return nil
}

// ----------------------------------------------------------------------------
// Utility / Query Methods
// ----------------------------------------------------------------------------

// Query executes a Cypher query and returns results as JSON
func (c *Client) Query(ctx context.Context, cypher string) ([]map[string]any, error) {
	return c.QueryWithParams(ctx, cypher, nil)
}

// QueryWithParams executes a Cypher query with parameters
func (c *Client) QueryWithParams(ctx context.Context, cypher string, params map[string]any) ([]map[string]any, error) {
	session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	result, err := session.Run(ctx, cypher, params)
	if err != nil {
		return nil, fmt.Errorf("query failed: %w", err)
	}

	var results []map[string]any
	for result.Next(ctx) {
		record := result.Record()
		row := make(map[string]any)
		for _, key := range record.Keys {
			row[key], _ = record.Get(key)
		}
		results = append(results, row)
	}

	if err := result.Err(); err != nil {
		return nil, fmt.Errorf("result error: %w", err)
	}

	return results, nil
}

// ClearDatabase removes all nodes and relationships
func (c *Client) ClearDatabase(ctx context.Context) error {
	session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	_, err := session.Run(ctx, "MATCH (n) DETACH DELETE n", nil)
	return err
}

// InitSchema creates indexes and constraints in Memgraph
// NOTE: We moved Schema definition here or you can keep it in schema.go
func (c *Client) InitSchema(ctx context.Context) error {
	// Re-using the logic from your original schema.go
	// If Schema variable is defined in schema.go (same package), it is accessible here.
	session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	// Ensure Schema is available. If it's in schema.go, it works.
	// Otherwise, define it here or pass it in.
	for _, stmt := range Schema {
		if _, err := session.Run(ctx, stmt, nil); err != nil {
			continue
		}
	}
	return nil
}
