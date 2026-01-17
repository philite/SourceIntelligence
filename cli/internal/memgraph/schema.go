package memgraph

import (
	"context"
	
	"github.com/neo4j/neo4j-go-driver/v5/neo4j"
)

// Schema contains the Cypher statements to initialize the graph schema
var Schema = []string{
	// Core indexes for faster lookups
	"CREATE INDEX ON :Type(fqn)",
	"CREATE INDEX ON :Type(name)",
	"CREATE INDEX ON :Function(fqn)",
	"CREATE INDEX ON :Function(name)",
	"CREATE INDEX ON :Field(fqn)",
	"CREATE INDEX ON :Module(name)",
	"CREATE INDEX ON :Package(fqn)",
	"CREATE INDEX ON :File(path)",
	
	// Architecture-specific indexes
	"CREATE INDEX ON :ViewModel(fqn)",
	"CREATE INDEX ON :UseCase(fqn)",
	"CREATE INDEX ON :Repository(fqn)",
	"CREATE INDEX ON :DataSource(fqn)",
	"CREATE INDEX ON :Activity(fqn)",
	"CREATE INDEX ON :Fragment(fqn)",
	
	// Unique constraint on id
	"CREATE CONSTRAINT ON (n:Type) ASSERT n.id IS UNIQUE",
	"CREATE CONSTRAINT ON (n:Function) ASSERT n.id IS UNIQUE",
}

// InitSchema creates indexes and constraints in Memgraph
func (c *Client) InitSchema(ctx context.Context) error {
	session := c.driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	for _, stmt := range Schema {
		if _, err := session.Run(ctx, stmt, nil); err != nil {
			// Ignore errors for already existing indexes/constraints
			continue
		}
	}
	return nil
}
