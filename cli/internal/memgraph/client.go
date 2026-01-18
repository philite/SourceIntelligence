package memgraph

import (
	"context"
	"encoding/json"
	"fmt"

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

// LoadGraph loads a CodeGraph JSON into Memgraph
// If useMerge is true, uses MERGE to update existing nodes instead of CREATE
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

	// Batch create nodes (grouped by labels)
	if err := c.batchCreateNodes(ctx, session, nodes, useMerge); err != nil {
		return err
	}

	// Batch create relationships (grouped by type)
	if err := c.batchCreateRelationships(ctx, session, graph.Relationships, useMerge); err != nil {
		return err
	}

	return nil
}

const batchSize = 500

// batchCreateNodes creates nodes in batches using UNWIND, grouped by label combination
func (c *Client) batchCreateNodes(ctx context.Context, session neo4j.SessionWithContext, nodes []GenericNode, useMerge bool) error {
	// Group nodes by their label combination
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
		// Process in batches
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
			if totalCreated%5000 == 0 || totalCreated == len(nodes) {
				fmt.Printf("Created %d/%d nodes...\n", totalCreated, len(nodes))
			}
		}
	}
	
	return nil
}

// batchCreateRelationships creates relationships in batches using UNWIND, grouped by type
func (c *Client) batchCreateRelationships(ctx context.Context, session neo4j.SessionWithContext, rels []GraphRelationship, useMerge bool) error {
	// Group relationships by type
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

	totalCreated := 0
	for relType, relProps := range groups {
		// Process in batches
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
			
			totalCreated += len(batch)
			if totalCreated%10000 == 0 || totalCreated == len(rels) {
				fmt.Printf("Created %d/%d relationships...\n", totalCreated, len(rels))
			}
		}
	}
	
	return nil
}

func (c *Client) createNode(ctx context.Context, session neo4j.SessionWithContext, node GenericNode, useMerge bool) error {
	// Build labels string
	labels := ""
	for i, label := range node.Labels {
		if i > 0 {
			labels += ":"
		}
		labels += label
	}

	// Get merged properties (includes name, fqn, etc.)
	props := node.ToProperties()

	var query string
	if useMerge {
		// MERGE by FQN if available, otherwise by ID
		if fqn, ok := props["fqn"].(string); ok && fqn != "" {
			query = fmt.Sprintf("MERGE (n:%s {fqn: $fqn}) SET n += $props", labels)
			props["fqn"] = fqn // ensure fqn is in props
		} else {
			query = fmt.Sprintf("MERGE (n:%s {id: $id}) SET n += $props", labels)
		}
	} else {
		query = fmt.Sprintf("CREATE (n:%s $props)", labels)
	}
	
	_, err := session.Run(ctx, query, map[string]any{"props": props, "fqn": props["fqn"], "id": props["id"]})
	return err
}

func (c *Client) createRelationship(ctx context.Context, session neo4j.SessionWithContext, rel GraphRelationship, useMerge bool) error {
	props := make(map[string]any)
	props["id"] = rel.ID
	for k, v := range rel.Properties {
		props[k] = v
	}

	var query string
	if useMerge {
		// MERGE relationship by matching start/end nodes
		query = `
			MATCH (a {id: $startId}), (b {id: $endId})
			MERGE (a)-[r:` + rel.Type + `]->(b)
			SET r += $props
		`
	} else {
		query = `
			MATCH (a {id: $startId}), (b {id: $endId})
			CREATE (a)-[r:` + rel.Type + ` $props]->(b)
		`
	}

	_, err := session.Run(ctx, query, map[string]any{
		"startId": rel.StartNodeID,
		"endId":   rel.EndNodeID,
		"props":   props,
	})
	return err
}

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

// EnsureIndexes creates necessary indexes to speed up ingestion
func (c *Client) EnsureIndexes(ctx context.Context, labels []string) error {
    session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
    defer session.Close(ctx)

    for _, label := range labels {
        // This creates a uniqueness constraint which creates an underlying index
        // changing linear scan O(N) to O(log N)
        query := fmt.Sprintf("CREATE CONSTRAINT ON (n:%s) ASSERT n.id IS UNIQUE;", label)
        
        // Note: In Memgraph, if it exists it might error or warn, 
        // strictly you might want to check existence first or ignore specific errors.
        _, err := session.Run(ctx, query, nil)
        if err != nil {
            // Log warning but don't fail if it simply already exists
            fmt.Printf("Warning creating index for %s (might already exist): %v\n", label, err)
        } else {
            fmt.Printf("Index created for label: %s\n", label)
        }
    }
    return nil
}
