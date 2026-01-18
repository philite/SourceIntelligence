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
	
	// New enterprise-scale tools
	s.registerGetCallGraph()
	s.registerGetModuleSummary()
	s.registerGetPackageContents()
	s.registerGetAndroidScreen()
	s.registerGetContextWindow()
	s.registerSearchByLabel()

	// Create stdio server
	stdioServer := server.NewStdioServer(s.mcpServer)
	return stdioServer.Listen(context.Background(), os.Stdin, os.Stdout)
}

func (s *Server) registerFindSymbol() {
	tool := mcp.NewTool("find_symbol",
		mcp.WithDescription("Search for classes, functions, or properties by name. Supports multiple comma-separated keywords and regex patterns."),
		mcp.WithString("query", mcp.Required(), mcp.Description("Search query. Comma-separated for OR (e.g. 'User,Auth,Login'). Use use_regex for regex patterns.")),
		mcp.WithString("type", mcp.Description("Optional filter by label (Class, Function, Interface, ViewModel, Repository, etc.)")),
		mcp.WithNumber("limit", mcp.Description("Max results to return (default 50, max 200)")),
		mcp.WithBoolean("use_regex", mcp.Description("If true, treat query as a regex pattern (default false)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, ok := request.Params.Arguments.(map[string]interface{})
		if !ok {
			return mcp.NewToolResultError("Invalid arguments format"), nil
		}
		
		queryArg, _ := args["query"].(string)
		typeArg, _ := args["type"].(string)
		useRegex, _ := args["use_regex"].(bool)
		limitFloat, ok := args["limit"].(float64)
		limit := 50
		if ok && limitFloat > 0 {
			limit = int(limitFloat)
			if limit > 200 {
				limit = 200
			}
		}

		var cypher string
		var params map[string]interface{}
		
		if useRegex {
			// Regex mode - use Memgraph's regex matching
			if typeArg != "" {
				cypher = fmt.Sprintf(`
					MATCH (n:%s) 
					WHERE n.name =~ $pattern OR n.fqn =~ $pattern 
					RETURN n LIMIT %d
				`, typeArg, limit)
			} else {
				cypher = fmt.Sprintf(`
					MATCH (n) 
					WHERE n.name =~ $pattern OR n.fqn =~ $pattern 
					RETURN n LIMIT %d
				`, limit)
			}
			params = map[string]interface{}{"pattern": queryArg}
		} else {
			// Multi-keyword mode - split by comma and OR them together
			keywords := strings.Split(queryArg, ",")
			var conditions []string
			params = make(map[string]interface{})
			
			for i, kw := range keywords {
				kw = strings.TrimSpace(kw)
				if kw == "" {
					continue
				}
				paramName := fmt.Sprintf("kw%d", i)
				conditions = append(conditions, 
					fmt.Sprintf("(n.name CONTAINS $%s OR n.fqn CONTAINS $%s)", paramName, paramName))
				params[paramName] = kw
			}
			
			if len(conditions) == 0 {
				return mcp.NewToolResultError("No valid search terms provided"), nil
			}
			
			whereClause := strings.Join(conditions, " OR ")
			
			if typeArg != "" {
				cypher = fmt.Sprintf(`
					MATCH (n:%s) 
					WHERE %s 
					RETURN n LIMIT %d
				`, typeArg, whereClause, limit)
			} else {
				cypher = fmt.Sprintf(`
					MATCH (n) 
					WHERE %s 
					RETURN n LIMIT %d
				`, whereClause, limit)
			}
		}

		results, err := s.client.QueryWithParams(ctx, cypher, params)
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

// New Enterprise-Scale Tools

func (s *Server) registerGetCallGraph() {
	tool := mcp.NewTool("get_call_graph",
		mcp.WithDescription("Get function call relationships for call chain analysis. Essential for understanding data flow."),
		mcp.WithString("fqn", mcp.Required(), mcp.Description("The FQN of the function to analyze")),
		mcp.WithString("direction", mcp.Description("'callers' (who calls this), 'callees' (what this calls), or 'both' (default)")),
		mcp.WithNumber("depth", mcp.Description("How many levels to traverse (default 2, max 5)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, _ := request.Params.Arguments.(map[string]interface{})
		fqn, _ := args["fqn"].(string)
		direction, _ := args["direction"].(string)
		depthFloat, ok := args["depth"].(float64)
		depth := 2
		if ok && depthFloat > 0 {
			depth = int(depthFloat)
			if depth > 5 {
				depth = 5
			}
		}

		var cypher string
		switch strings.ToLower(direction) {
		case "callers":
			cypher = fmt.Sprintf(`
				MATCH path = (caller)-[:CALLS*1..%d]->(target {fqn: $fqn})
				RETURN caller.fqn as fqn, caller.name as name, caller.file as file, length(path) as depth
				LIMIT 50
			`, depth)
		case "callees":
			cypher = fmt.Sprintf(`
				MATCH path = (source {fqn: $fqn})-[:CALLS*1..%d]->(callee)
				RETURN callee.fqn as fqn, callee.name as name, callee.file as file, length(path) as depth
				LIMIT 50
			`, depth)
		default:
			cypher = fmt.Sprintf(`
				MATCH path = (source {fqn: $fqn})-[:CALLS*1..%d]-(related)
				RETURN related.fqn as fqn, related.name as name, related.file as file, 
				       CASE WHEN startNode(relationships(path)[0]) = source THEN 'callee' ELSE 'caller' END as direction,
				       length(path) as depth
				LIMIT 100
			`, depth)
		}

		results, err := s.client.QueryWithParams(ctx, cypher, map[string]interface{}{"fqn": fqn})
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerGetModuleSummary() {
	tool := mcp.NewTool("get_module_summary",
		mcp.WithDescription("Get a high-level summary of a module/project directory. Returns counts of classes, interfaces, functions, and key types."),
		mcp.WithString("module", mcp.Required(), mcp.Description("The module name or path pattern to summarize (e.g. 'feature-auth', 'core/domain')")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, _ := request.Params.Arguments.(map[string]interface{})
		module, _ := args["module"].(string)

		cypher := `
			MATCH (n)
			WHERE n.file CONTAINS $module
			WITH labels(n) as nodeLabels, n
			RETURN 
				nodeLabels[0] as type,
				count(n) as count,
				collect(DISTINCT n.name)[0..10] as examples
			ORDER BY count DESC
		`

		results, err := s.client.QueryWithParams(ctx, cypher, map[string]interface{}{"module": module})
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerGetPackageContents() {
	tool := mcp.NewTool("get_package_contents",
		mcp.WithDescription("List all symbols in a specific package. Efficient for exploring package structure."),
		mcp.WithString("package", mcp.Required(), mcp.Description("The package FQN (e.g. 'com.example.auth.domain')")),
		mcp.WithNumber("limit", mcp.Description("Max results (default 100)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, _ := request.Params.Arguments.(map[string]interface{})
		pkg, _ := args["package"].(string)
		limitFloat, ok := args["limit"].(float64)
		limit := 100
		if ok && limitFloat > 0 {
			limit = int(limitFloat)
		}

		cypher := fmt.Sprintf(`
			MATCH (n)
			WHERE n.fqn STARTS WITH $package
			RETURN n.name as name, n.fqn as fqn, labels(n) as labels, n.file as file
			ORDER BY n.name
			LIMIT %d
		`, limit)

		results, err := s.client.QueryWithParams(ctx, cypher, map[string]interface{}{"package": pkg})
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerGetAndroidScreen() {
	tool := mcp.NewTool("get_android_screen",
		mcp.WithDescription("Get Android screen details including layouts, composables, and navigation for an Activity/Fragment."),
		mcp.WithString("class_name", mcp.Required(), mcp.Description("The Activity or Fragment class name")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, _ := request.Params.Arguments.(map[string]interface{})
		className, _ := args["class_name"].(string)

		// Get layouts used by this screen
		layoutQuery := `
			MATCH (screen)-[:USES_LAYOUT]->(layout)
			WHERE screen.name = $name OR screen.fqn CONTAINS $name
			RETURN layout.name as layout, layout.file as file
		`
		layouts, _ := s.client.QueryWithParams(ctx, layoutQuery, map[string]interface{}{"name": className})

		// Get composables called by this screen
		composeQuery := `
			MATCH (screen)-[:CALLS*1..3]->(composable)
			WHERE (screen.name = $name OR screen.fqn CONTAINS $name)
			  AND 'Composable' IN labels(composable)
			RETURN DISTINCT composable.name as composable, composable.fqn as fqn
			LIMIT 20
		`
		composables, _ := s.client.QueryWithParams(ctx, composeQuery, map[string]interface{}{"name": className})

		// Get navigation destinations from this screen
		navQuery := `
			MATCH (source)-[:NAVIGATES_TO]->(dest)
			WHERE source.className CONTAINS $name OR source.navId CONTAINS $name
			RETURN dest.navId as destination, dest.className as targetClass, dest.label as label
		`
		navigation, _ := s.client.QueryWithParams(ctx, navQuery, map[string]interface{}{"name": className})

		result := map[string]interface{}{
			"screen":      className,
			"layouts":     layouts,
			"composables": composables,
			"navigation":  navigation,
		}

		jsonBytes, _ := json.MarshalIndent(result, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerGetContextWindow() {
	tool := mcp.NewTool("get_context_window",
		mcp.WithDescription("Get a curated, AI-friendly context summary for a symbol. Designed to fit within context limits while providing comprehensive understanding."),
		mcp.WithString("fqn", mcp.Required(), mcp.Description("The FQN of the symbol to get context for")),
		mcp.WithNumber("max_tokens", mcp.Description("Approximate max output size in tokens (default 2000)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, _ := request.Params.Arguments.(map[string]interface{})
		fqn, _ := args["fqn"].(string)

		// Get the symbol itself
		symbolQuery := `
			MATCH (n {fqn: $fqn})
			RETURN n.name as name, n.fqn as fqn, labels(n) as labels, 
			       n.file as file, n.line as line, n.signature as signature
		`
		symbol, err := s.client.QueryWithParams(ctx, symbolQuery, map[string]interface{}{"fqn": fqn})
		if err != nil || len(symbol) == 0 {
			return mcp.NewToolResultError(fmt.Sprintf("Symbol not found: %s", fqn)), nil
		}

		// Get direct relationships (limited)
		relQuery := `
			MATCH (n {fqn: $fqn})-[r]-(related)
			RETURN type(r) as relationship, 
			       CASE WHEN startNode(r) = n THEN 'outgoing' ELSE 'incoming' END as direction,
			       related.name as name, related.fqn as fqn, labels(related)[0] as type
			LIMIT 30
		`
		relationships, _ := s.client.QueryWithParams(ctx, relQuery, map[string]interface{}{"fqn": fqn})

		// Get inheritance (limited)
		inheritQuery := `
			MATCH (n {fqn: $fqn})-[:EXTENDS|IMPLEMENTS*1..3]-(parent)
			RETURN parent.name as name, parent.fqn as fqn, 
			       CASE WHEN (n)-[:EXTENDS|IMPLEMENTS]->(parent) THEN 'parent' ELSE 'child' END as relation
			LIMIT 10
		`
		inheritance, _ := s.client.QueryWithParams(ctx, inheritQuery, map[string]interface{}{"fqn": fqn})

		// Build summary
		result := map[string]interface{}{
			"symbol":        symbol[0],
			"relationships": relationships,
			"inheritance":   inheritance,
			"_meta": map[string]interface{}{
				"description": "Curated context for AI analysis. Use get_symbol_details for raw properties.",
			},
		}

		jsonBytes, _ := json.MarshalIndent(result, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

func (s *Server) registerSearchByLabel() {
	tool := mcp.NewTool("search_by_label",
		mcp.WithDescription("Fast search by node label (ViewModel, Repository, UseCase, Activity, Fragment, Composable, etc.). Much faster than fuzzy name search."),
		mcp.WithString("label", mcp.Required(), mcp.Description("The label to search for")),
		mcp.WithNumber("limit", mcp.Description("Max results (default 50)")),
	)

	s.mcpServer.AddTool(tool, func(ctx context.Context, request mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		args, _ := request.Params.Arguments.(map[string]interface{})
		label, _ := args["label"].(string)
		limitFloat, ok := args["limit"].(float64)
		limit := 50
		if ok && limitFloat > 0 {
			limit = int(limitFloat)
		}

		cypher := fmt.Sprintf(`
			MATCH (n:%s)
			RETURN n.name as name, n.fqn as fqn, n.file as file
			ORDER BY n.name
			LIMIT %d
		`, label, limit)

		results, err := s.client.Query(ctx, cypher)
		if err != nil {
			return mcp.NewToolResultError(fmt.Sprintf("Query failed: %v", err)), nil
		}

		jsonBytes, _ := json.MarshalIndent(results, "", "  ")
		return mcp.NewToolResultText(string(jsonBytes)), nil
	})
}

