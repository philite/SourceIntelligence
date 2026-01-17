package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
	"github.com/sourceintel/cli/internal/memgraph"
	"github.com/sourceintel/cli/internal/query"
)

type Server struct {
	mcpServer *server.MCPServer
	client    *memgraph.Client
	qb        *query.Builder
}

func NewServer(client *memgraph.Client) *Server {
	s := server.NewMCPServer(
		"SourceIntel",
		"0.1.0",
	)

	return &Server{
		mcpServer: s,
		client:    client,
		qb:        query.NewBuilder(client),
	}
}

func (s *Server) Start() error {
	// Register tools
	s.registerFindSymbol()
	s.registerGetSymbolDetails()
	s.registerGetReferences()
	s.registerGetInheritance()
	s.registerRunCypher()

	// Create stdio server
	stdioServer := server.NewStdioServer(s.mcpServer)
	return stdioServer.Listen(context.Background(), os.Stdin, os.Stdout)
}

func (s *Server) registerFindSymbol() {
	tool := mcp.NewTool("find_symbol",
		mcp.WithDescription("Search for classes, functions, or properties by name (fuzzy match)."),
		mcp.WithString("query", mcp.Required(), mcp.Description("The name or partial name to search for")),
		mcp.WithString("type", mcp.Description("Optional filter by type (class, function, property, interface, etc.)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, ok := request.Params.Arguments.(map[string]interface{})
		if !ok {
			// Try to handle if arguments are somehow missing or different type (though mcp-go handles validation)
			return mcp.NewToolResultError("Invalid arguments format"), nil
		}
		
		queryArg, _ := args["query"].(string)
		typeArg, _ := args["type"].(string)

		results, err := s.qb.FindSymbol(ctx, queryArg, typeArg)
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Search failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerGetSymbolDetails() {
	tool := mcp.NewTool("get_symbol_details",
		mcp.WithDescription("Get semantic details for a specific symbol by fully qualified name (FQN). Includes signature, modifiers, and location."),
		mcp.WithString("fqn", mcp.Required(), mcp.Description("The fully qualified name of the symbol (e.g. com.example.MyClass)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, ok := request.Params.Arguments.(map[string]interface{})
		if !ok {
			return mcp.NewToolResultError("Invalid arguments format"), nil
		}
		
		fqn, _ := args["fqn"].(string)

		// Create a specific query for details since generic FindSymbol might be too broad
		// We want exact FQN match here
		cypher := `
			MATCH (n) 
			WHERE n.fqn = $fqn
			RETURN n
			LIMIT 1
		`
		
		results, err := s.client.QueryWithParams(ctx, cypher, map[string]interface{}{"fqn": fqn})
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		if len(results) == 0 {
			return mcp.NewToolResultText(fmt.Sprintf("No symbol found with FQN: %s", fqn)), nil
		}

		// Get the node properties
		node := results[0]["n"]
		jsonBytes, _ := json.MarshalIndent(node, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerGetReferences() {
	tool := mcp.NewTool("get_references",
		mcp.WithDescription("Find code that references (calls, reads, writes) a symbol."),
		mcp.WithString("fqn", mcp.Required(), mcp.Description("The FQN of the symbol to find references for")),
		mcp.WithNumber("limit", mcp.Description("Max number of references to return (default 50)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, ok := request.Params.Arguments.(map[string]interface{})
		if !ok {
			return mcp.NewToolResultError("Invalid arguments format"), nil
		}
		
		fqn, _ := args["fqn"].(string)
		limitFunc, ok := args["limit"].(float64)
		limit := 50
		if ok {
			limit = int(limitFunc)
		}

		// We need to look for relationships pointing TO the symbol
		// Common relationships: CALLS, READS, WRITES, HAS_TYPE
		cypher := `
			MATCH (source)-[r]->(target)
			WHERE target.fqn = $fqn
			RETURN source.fqn as source, type(r) as relationship, source.file as file, source.line as line
			LIMIT $limit
		`

		results, err := s.client.QueryWithParams(ctx, cypher, map[string]interface{}{
			"fqn": fqn,
			"limit": limit,
		})
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerGetInheritance() {
	tool := mcp.NewTool("get_inheritance",
		mcp.WithDescription("Get parent classes/interfaces (supertypes) or child implementations (subtypes)."),
		mcp.WithString("fqn", mcp.Required(), mcp.Description("The FQN of the class/interface")),
		mcp.WithString("direction", mcp.Description("Direction to traverse: 'up' for parents, 'down' for children (default 'up')")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, ok := request.Params.Arguments.(map[string]interface{})
		if !ok {
			return mcp.NewToolResultError("Invalid arguments format"), nil
		}
		
		fqn, _ := args["fqn"].(string)
		direction, _ := args["direction"].(string)
		
		var cypher string
		if strings.ToLower(direction) == "down" {
			// Find subtypes (who extends/implements THIS)
			cypher = `
				MATCH (child)-[:EXTENDS|IMPLEMENTS]->(parent)
				WHERE parent.fqn = $fqn
				RETURN child.fqn as fqn, child.name as name, child.file as file
			`
		} else {
			// Find supertypes (who THIS extends/implements)
			cypher = `
				MATCH (child)-[:EXTENDS|IMPLEMENTS]->(parent)
				WHERE child.fqn = $fqn
				RETURN parent.fqn as fqn, parent.name as name
			`
		}

		results, err := s.client.QueryWithParams(ctx, cypher, map[string]interface{}{"fqn": fqn})
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerRunCypher() {
	tool := mcp.NewTool("run_cypher",
		mcp.WithDescription("Execute a raw Cypher query against the graph database. Use this for complex queries not covered by other tools."),
		mcp.WithString("query", mcp.Required(), mcp.Description("The Cypher query to execute")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, ok := request.Params.Arguments.(map[string]interface{})
		if !ok {
			return mcp.NewToolResultError("Invalid arguments format"), nil
		}
		
		query, _ := args["query"].(string)

		results, err := s.client.Query(ctx, query)
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}
