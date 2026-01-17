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
func (c *Client) LoadGraph(ctx context.Context, graphJSON []byte) error {
	var graph CodeGraph
	if err := json.Unmarshal(graphJSON, &graph); err != nil {
		return fmt.Errorf("failed to parse graph JSON: %w", err)
	}

	session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	// Create nodes
	for _, nodeRaw := range graph.Nodes {
		var node GenericNode
		if err := json.Unmarshal(nodeRaw, &node); err != nil {
			return fmt.Errorf("failed to parse node: %w", err)
		}
		if err := c.createNode(ctx, session, node); err != nil {
			return fmt.Errorf("failed to create node %s: %w", node.ID, err)
		}
	}

	// Create relationships
	for _, rel := range graph.Relationships {
		if err := c.createRelationship(ctx, session, rel); err != nil {
			return fmt.Errorf("failed to create relationship %s: %w", rel.ID, err)
		}
	}

	return nil
}

func (c *Client) createNode(ctx context.Context, session neo4j.SessionWithContext, node GenericNode) error {
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

	query := fmt.Sprintf("CREATE (n:%s $props)", labels)
	_, err := session.Run(ctx, query, map[string]any{"props": props})
	return err
}

func (c *Client) createRelationship(ctx context.Context, session neo4j.SessionWithContext, rel GraphRelationship) error {
	query := `
		MATCH (a {id: $startId}), (b {id: $endId})
		CREATE (a)-[r:` + rel.Type + ` $props]->(b)
	`
	props := make(map[string]any)
	props["id"] = rel.ID
	for k, v := range rel.Properties {
		props[k] = v
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
