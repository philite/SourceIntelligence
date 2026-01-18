package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/sourceintel/cli/internal/analyzer"
	"github.com/sourceintel/cli/internal/exporter"
	"github.com/sourceintel/cli/internal/memgraph"
	mcp_server "github.com/sourceintel/cli/internal/mcp"
	"github.com/sourceintel/cli/internal/query"
	"github.com/spf13/cobra"
)

var (
	memgraphURI      string
	memgraphUser     string
	memgraphPassword string
	outputFormat     string
	analyzerGrpc     string
	importDirHost string // e.g., /Users/me/tmp/import
    importDirDocker string // e.g., /var/lib/memgraph/import
)

var rootCmd = &cobra.Command{
	Use:   "sourceintel",
	Short: "AI-powered code intelligence for Kotlin/Android projects",
	Long: `SourceIntelligence builds a queryable knowledge graph from your source code,
enabling AI agents to understand code with precision through type resolution
and reference binding.`,
}

var indexCmd = &cobra.Command{
	Use:   "index [project-path]",
	Short: "Analyze a project and populate the graph database",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		projectPath := args[0]
		moduleName, _ := cmd.Flags().GetString("module")
		analyzerDir, _ := cmd.Flags().GetString("analyzer-dir") // Fallback for non-gRPC mode
		semantic, _ := cmd.Flags().GetBool("semantic")
		fullK2, _ := cmd.Flags().GetBool("full-k2")
		android, _ := cmd.Flags().GetBool("android")
		clearFirst, _ := cmd.Flags().GetBool("clear")
		grpcAddr, _ := cmd.Flags().GetString("analyzer-grpc")

		fmt.Printf("Indexing project: %s\n", projectPath)

		// 1. Resolve Absolute Path
		// Use path as-is if it starts with / (container path), otherwise convert to absolute
		analyzerPath := projectPath
		if len(projectPath) > 0 && projectPath[0] != '/' {
			absProjectPath, err := filepath.Abs(projectPath)
			if err != nil {
				return fmt.Errorf("failed to resolve absolute path: %w", err)
			}
			analyzerPath = absProjectPath
		}

		// 2. Setup CSV Exporter (Prepare the files on disk)
		// We only do this if we are using gRPC, as the streaming optimization is built for it.
		var csvExp *exporter.CSVExporter
		var err error
		
		if grpcAddr != "" {
			fmt.Printf("Preparing CSV export in: %s\n", importDirHost)
			csvExp, err = exporter.NewCSVExporter(importDirHost)
			if err != nil {
				return fmt.Errorf("failed to create CSV exporter: %w", err)
			}
			defer csvExp.Close()
		}

		// 3. Run Analysis
		// We track stats to print later
		var nodeCount, relCount int
		var graph *memgraph.CodeGraph // Used only for legacy non-gRPC mode

		if grpcAddr != "" {
			fmt.Printf("Using gRPC analyzer at %s\n", grpcAddr)
			if fullK2 {
				fmt.Println("Running full K2 semantic analyzer via gRPC (streaming to CSV)...")
			} else {
				fmt.Println("Running analyzer via gRPC...")
			}

			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
			defer cancel()

			grpcAnalyzer := analyzer.NewGrpcAnalyzer(grpcAddr)
			
			// Call the NEW method that streams to CSV instead of returning a struct
			nodeCount, relCount, err = grpcAnalyzer.AnalyzeAndExport(
				ctx, analyzerPath, moduleName, fullK2, android, csvExp,
			)
			if err != nil {
				return fmt.Errorf("gRPC analysis failed: %w", err)
			}
			
			fmt.Printf("✓ Analysis complete. Streamed %d nodes and %d relationships to disk.\n", nodeCount, relCount)

		} else {
			// Legacy/Subprocess Mode (Keep this if you ever need to run without gRPC)
			// This loads everything into RAM.
			if analyzerDir == "../analyzer" {
				if foundDir, err := analyzer.FindAnalyzerDir(); err == nil {
					analyzerDir = foundDir
				}
			}
			a := analyzer.NewAnalyzer(analyzerDir)
			fmt.Println("Running analyzer in subprocess mode (RAM heavy)...")
			
			graph, err = a.AnalyzeProject(projectPath, moduleName, semantic, fullK2, android)
			if err != nil {
				return fmt.Errorf("analysis failed: %w", err)
			}
			nodeCount = len(graph.Nodes)
			relCount = len(graph.Relationships)
		}

		// 4. Connect to Memgraph
		fmt.Println("Connecting to Memgraph...")
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute) // Increased timeout for load
		defer cancel()

		client, err := memgraph.NewClient(memgraph.Config{
			URI:      memgraphURI,
			Username: memgraphUser,
			Password: memgraphPassword,
		})
		if err != nil {
			return fmt.Errorf("failed to connect: %w", err)
		}
		defer client.Close(ctx)

		// 5. Clear Database
		if clearFirst {
			fmt.Println("Clearing existing graph...")
			if err := client.ClearDatabase(ctx); err != nil {
				return fmt.Errorf("clear failed: %w", err)
			}
		}

		// 6. Initialize Schema (Indexes are crucial for import speed)
		fmt.Println("Initializing schema...")
		if err := client.InitSchema(ctx); err != nil {
			return fmt.Errorf("schema init failed: %w", err)
		}

		// 7. Load Data into Memgraph
		if grpcAddr != "" {
			// --- CSV IMPORT PATH ---
			fmt.Println("Loading CSV data into Memgraph (Server-side load)...")
			
			// Construct internal docker paths
			nodesFileDocker := filepath.Join(importDirDocker, "nodes.csv")
			relsFileDocker := filepath.Join(importDirDocker, "relationships.csv")

			if err := client.ImportCSV(ctx, nodesFileDocker, relsFileDocker); err != nil {
				return fmt.Errorf("\n❌ Import Failed: %w\n\nCheck your Docker volume mount:\n  - Host: %s\n  - Docker: %s", err, importDirHost, importDirDocker)
			}
		} else {
			// --- LEGACY RAM PATH ---
			fmt.Println("Loading graph object into Memgraph (Client-side load)...")
			graphJSON, _ := json.Marshal(graph)
			// Note: This relies on the old LoadGraph method. 
			// If you want to force everything to CSV, you would need to convert 'graph' to CSV here too.
			if err := client.LoadGraph(ctx, graphJSON, false); err != nil {
				return fmt.Errorf("graph load failed: %w", err)
			}
		}

		fmt.Println("✓ Indexing complete!")
		return nil
	},
}

var queryCmd = &cobra.Command{
	Use:   "query [cypher]",
	Short: "Execute a Cypher query against the graph",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		cypher := args[0]
		
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		
		client, err := memgraph.NewClient(memgraph.Config{
			URI:      memgraphURI,
			Username: memgraphUser,
			Password: memgraphPassword,
		})
		if err != nil {
			return fmt.Errorf("failed to connect: %w", err)
		}
		defer client.Close(ctx)
		
		results, err := client.Query(ctx, cypher)
		if err != nil {
			return fmt.Errorf("query failed: %w", err)
		}
		
		// Output as JSON
		output, _ := json.MarshalIndent(results, "", "  ")
		fmt.Println(string(output))
		return nil
	},
}

var findCmd = &cobra.Command{
	Use:   "find [symbol-name]",
	Short: "Find symbols by name",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		symbolName := args[0]
		symbolType, _ := cmd.Flags().GetString("type")
		
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		
		client, err := memgraph.NewClient(memgraph.Config{
			URI:      memgraphURI,
			Username: memgraphUser,
			Password: memgraphPassword,
		})
		if err != nil {
			return fmt.Errorf("failed to connect: %w", err)
		}
		defer client.Close(ctx)
		
		qb := query.NewBuilder(client)
		results, err := qb.FindSymbol(ctx, symbolName, symbolType)
		if err != nil {
			return fmt.Errorf("find failed: %w", err)
		}
		
		output, _ := json.MarshalIndent(results, "", "  ")
		fmt.Println(string(output))
		return nil
	},
}

var mcpCmd = &cobra.Command{
	Use:   "mcp",
	Short: "Start MCP server for AI agent integration",
	RunE: func(cmd *cobra.Command, args []string) error {
		fmt.Fprintln(os.Stderr, "Starting MCP server on stdio...")
		
		// Connect to Memgraph
		// Note: We use a separate context for the connection that lives as long as the server
		client, err := memgraph.NewClient(memgraph.Config{
			URI:      memgraphURI,
			Username: memgraphUser,
			Password: memgraphPassword,
		})
		if err != nil {
			return fmt.Errorf("failed to connect to Memgraph: %w", err)
		}
		defer client.Close(context.Background())

		// Start MCP server
		srv := mcp_server.NewServer(client)
		if err := srv.Start(); err != nil {
			return fmt.Errorf("server error: %w", err)
		}
		return nil
	},
}

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show graph database status and statistics",
	RunE: func(cmd *cobra.Command, args []string) error {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		
		client, err := memgraph.NewClient(memgraph.Config{
			URI:      memgraphURI,
			Username: memgraphUser,
			Password: memgraphPassword,
		})
		if err != nil {
			fmt.Println("❌ Memgraph: Not connected")
			fmt.Printf("   Error: %v\n", err)
			return nil
		}
		defer client.Close(ctx)
		
		fmt.Println("✅ Memgraph: Connected")
		fmt.Printf("   URI: %s\n", memgraphURI)
		
		// Get node counts by label
		countQuery := `
			MATCH (n)
			WITH labels(n) as nodeLabels
			RETURN nodeLabels[0] as label, count(*) as count
			ORDER BY count DESC
			LIMIT 15
		`
		results, err := client.Query(ctx, countQuery)
		if err != nil {
			fmt.Printf("   ⚠️ Could not query stats: %v\n", err)
			return nil
		}
		
		if len(results) == 0 {
			fmt.Println("\n📊 Graph Status: EMPTY")
			fmt.Println("   Run 'sourceintel index <project-path>' to populate the graph.")
			return nil
		}
		
		fmt.Println("\n📊 Graph Statistics:")
		totalNodes := 0
		for _, row := range results {
			label := row["label"]
			count := row["count"]
			if countInt, ok := count.(int64); ok {
				totalNodes += int(countInt)
				fmt.Printf("   %-20s %d\n", label, countInt)
			}
		}
		
		// Get total relationships
		relQuery := `MATCH ()-[r]->() RETURN count(r) as count`
		relResults, _ := client.Query(ctx, relQuery)
		if len(relResults) > 0 {
			if count, ok := relResults[0]["count"].(int64); ok {
				fmt.Printf("\n   Total Nodes:         %d\n", totalNodes)
				fmt.Printf("   Total Relationships: %d\n", count)
			}
		}
		
		// Get last analyzed project info if available
		metaQuery := `MATCH (n) WHERE n.file IS NOT NULL RETURN n.file as file LIMIT 1`
		metaResults, _ := client.Query(ctx, metaQuery)
		if len(metaResults) > 0 {
			if file, ok := metaResults[0]["file"].(string); ok && file != "" {
				fmt.Printf("\n   Sample file: %s\n", file)
			}
		}
		
		return nil
	},
}

func init() {
	// Global flags
	rootCmd.PersistentFlags().StringVar(&memgraphURI, "memgraph-uri", "bolt://localhost:7687", "Memgraph connection URI")
	rootCmd.PersistentFlags().StringVar(&memgraphUser, "memgraph-user", "memgraph", "Memgraph username")
	rootCmd.PersistentFlags().StringVar(&memgraphPassword, "memgraph-password", "sourceintel", "Memgraph password")
	rootCmd.PersistentFlags().StringVar(&outputFormat, "output", "json", "Output format (json, table)")
	
	// Index command flags
	indexCmd.Flags().String("module", "", "Specific module to analyze")
	indexCmd.Flags().String("analyzer-dir", "../analyzer", "Path to analyzer directory (containing gradlew)")
	indexCmd.Flags().Bool("semantic", false, "Use PSI-enhanced semantic analysis (slower but more accurate)")
	indexCmd.Flags().Bool("full-k2", false, "Use full K2 semantic analysis with classpath (slowest but most accurate)")
	indexCmd.Flags().Bool("android", false, "Run Android enrichment (layouts, manifest, navigation, Compose)")
	indexCmd.Flags().Bool("clear", false, "Clear existing graph before indexing (prevents duplicates)")
	indexCmd.Flags().Bool("merge", false, "Use MERGE instead of CREATE for incremental updates")
	indexCmd.Flags().String("analyzer-grpc", "", "Analyzer gRPC server address (e.g., localhost:50051). Uses subprocess mode if empty.")
	
	// Find command flags
	findCmd.Flags().String("type", "", "Filter by symbol type (e.g., ViewModel, UseCase)")
	
	rootCmd.AddCommand(indexCmd)
	rootCmd.AddCommand(queryCmd)
	rootCmd.AddCommand(findCmd)
	rootCmd.AddCommand(mcpCmd)
	rootCmd.AddCommand(statusCmd)

	indexCmd.Flags().StringVar(&importDirHost, "import-host", "./import_data", "Local directory to write CSVs to")
    indexCmd.Flags().StringVar(&importDirDocker, "import-docker", "/var/lib/memgraph/import", "Path to that directory inside the Memgraph container")
}

func main() {
	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
