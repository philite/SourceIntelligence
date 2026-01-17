package query

import (
	"context"
	"fmt"

	"github.com/sourceintel/cli/internal/memgraph"
)

// Builder provides pre-built Cypher queries for common operations
type Builder struct {
	client *memgraph.Client
}

// NewBuilder creates a new query builder
func NewBuilder(client *memgraph.Client) *Builder {
	return &Builder{client: client}
}

// FindSymbol finds symbols by name pattern
func (b *Builder) FindSymbol(ctx context.Context, name string, symbolType string) ([]map[string]any, error) {
	var query string
	if symbolType != "" {
		query = fmt.Sprintf(
			"MATCH (n:%s) WHERE n.name CONTAINS $name OR n.fqn CONTAINS $name RETURN n",
			symbolType,
		)
	} else {
		query = "MATCH (n) WHERE n.name CONTAINS $name OR n.fqn CONTAINS $name RETURN n"
	}
	
	return b.client.QueryWithParams(ctx, query, map[string]any{"name": name})
}

// GetReferences finds all usages of a symbol
func (b *Builder) GetReferences(ctx context.Context, fqn string) ([]map[string]any, error) {
	query := `
		MATCH (usage)-[r]->(target {fqn: $fqn})
		RETURN usage.fqn AS usedBy, type(r) AS relationship
	`
	return b.client.Query(ctx, query)
}

// GetTypeHierarchy returns the inheritance chain of a type
func (b *Builder) GetTypeHierarchy(ctx context.Context, fqn string, direction string) ([]map[string]any, error) {
	var query string
	switch direction {
	case "up":
		query = `
			MATCH path = (c:Type {fqn: $fqn})-[:EXTENDS|IMPLEMENTS*]->(parent)
			RETURN path
		`
	case "down":
		query = `
			MATCH path = (child)-[:EXTENDS|IMPLEMENTS*]->(c:Type {fqn: $fqn})
			RETURN path
		`
	default:
		query = `
			MATCH path = (c:Type {fqn: $fqn})-[:EXTENDS|IMPLEMENTS*]-(related)
			RETURN path
		`
	}
	return b.client.Query(ctx, query)
}

// GetCallGraph returns function call relationships
func (b *Builder) GetCallGraph(ctx context.Context, fqn string, depth int, direction string) ([]map[string]any, error) {
	depthStr := fmt.Sprintf("*1..%d", depth)
	var query string
	switch direction {
	case "callers":
		query = fmt.Sprintf(`
			MATCH path = (caller)-[:CALLS%s]->(target:Function {fqn: $fqn})
			RETURN path
		`, depthStr)
	case "callees":
		query = fmt.Sprintf(`
			MATCH path = (source:Function {fqn: $fqn})-[:CALLS%s]->(callee)
			RETURN path
		`, depthStr)
	default:
		query = fmt.Sprintf(`
			MATCH path = (source:Function {fqn: $fqn})-[:CALLS%s]-(related)
			RETURN path
		`, depthStr)
	}
	return b.client.Query(ctx, query)
}

// GetSymbolContext returns complete context for a symbol
func (b *Builder) GetSymbolContext(ctx context.Context, fqn string) (map[string]any, error) {
	// Get the symbol itself
	symbolQuery := "MATCH (n {fqn: $fqn}) RETURN n"
	symbolResults, err := b.client.Query(ctx, symbolQuery)
	if err != nil {
		return nil, err
	}
	if len(symbolResults) == 0 {
		return nil, fmt.Errorf("symbol not found: %s", fqn)
	}

	// Get relationships
	relQuery := `
		MATCH (n {fqn: $fqn})-[r]-(related)
		RETURN type(r) AS relationship, 
		       CASE WHEN startNode(r) = n THEN 'outgoing' ELSE 'incoming' END AS direction,
		       related.fqn AS relatedFqn,
		       labels(related) AS relatedLabels
	`
	relResults, err := b.client.Query(ctx, relQuery)
	if err != nil {
		return nil, err
	}

	return map[string]any{
		"symbol":        symbolResults[0],
		"relationships": relResults,
	}, nil
}

// GetCleanArchitectureFlow traces ViewModel → UseCase → Repository → DataSource
func (b *Builder) GetCleanArchitectureFlow(ctx context.Context, viewModelFqn string) ([]map[string]any, error) {
	query := `
		MATCH path = (vm:ViewModel {fqn: $fqn})-[:CALLS_USE_CASE]->(uc:UseCase)
		               -[:USES_REPOSITORY]->(repo:Repository)
		               -[:USES_DATASOURCE]->(ds:DataSource)
		RETURN path
	`
	return b.client.Query(ctx, query)
}
