package analyzer

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strconv"

	"github.com/sourceintel/cli/internal/memgraph"
	pb "github.com/sourceintel/cli/pkg/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// GrpcAnalyzer uses gRPC to communicate with the Kotlin analyzer server.
// This is the preferred mode for large projects as it supports streaming.
type GrpcAnalyzer struct {
	address string
	conn    *grpc.ClientConn
	client  pb.AnalyzerServiceClient
}

// NewGrpcAnalyzer creates a new gRPC-based analyzer
func NewGrpcAnalyzer(address string) *GrpcAnalyzer {
	return &GrpcAnalyzer{
		address: address,
	}
}

// connect establishes the gRPC connection
func (a *GrpcAnalyzer) connect() error {
	if a.conn != nil {
		return nil
	}
	
	conn, err := grpc.NewClient(a.address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return fmt.Errorf("failed to connect to analyzer at %s: %w", a.address, err)
	}
	
	a.conn = conn
	a.client = pb.NewAnalyzerServiceClient(conn)
	return nil
}

// Close closes the gRPC connection
func (a *GrpcAnalyzer) Close() {
	if a.conn != nil {
		a.conn.Close()
	}
}

// AnalyzeProject analyzes a project and returns the complete graph.
// For large projects, consider using AnalyzeProjectStream instead.
func (a *GrpcAnalyzer) AnalyzeProject(
	ctx context.Context,
	projectPath string,
	moduleName string,
	fullK2 bool,
	android bool,
) (*memgraph.CodeGraph, error) {
	if err := a.connect(); err != nil {
		return nil, err
	}
	defer a.Close()

	req := &pb.AnalyzeRequest{
		ProjectPath: projectPath,
		ModuleName:  moduleName,
		FullK2:      fullK2,
		Android:     android,
	}

	stream, err := a.client.Analyze(ctx, req)
	if err != nil {
		return nil, fmt.Errorf("gRPC analyze failed: %w", err)
	}

	// Collect results from stream
	var meta *pb.GraphMeta
	nodes := make([]json.RawMessage, 0)
	relationships := make([]memgraph.GraphRelationship, 0)

	for {
		event, err := stream.Recv()
		if err == io.EOF {
			fmt.Printf("Stream complete. Received %d nodes, %d relationships\n", len(nodes), len(relationships))
			break
		}
		if err != nil {
			return nil, fmt.Errorf("stream receive failed: %w", err)
		}

		switch e := event.Event.(type) {
		case *pb.AnalysisEvent_Meta:
			meta = e.Meta
			fmt.Printf("Received metadata: language=%s, projectType=%s\n", meta.Language, meta.ProjectType)
		case *pb.AnalysisEvent_Node:
			// Convert proto node to JSON for memgraph
			nodeJSON, _ := json.Marshal(protoNodeToMap(e.Node))
			nodes = append(nodes, nodeJSON)
			if len(nodes)%1000 == 0 {
				fmt.Printf("Received %d nodes...\n", len(nodes))
			}
		case *pb.AnalysisEvent_Relationship:
			relationships = append(relationships, memgraph.GraphRelationship{
				ID:          e.Relationship.Id,
				Type:        e.Relationship.Type,
				StartNodeID: e.Relationship.StartNodeId,
				EndNodeID:   e.Relationship.EndNodeId,
				Properties:  e.Relationship.Properties,
			})
			if len(relationships)%5000 == 0 {
				fmt.Printf("Received %d relationships...\n", len(relationships))
			}
		case *pb.AnalysisEvent_Error:
			if e.Error.Fatal {
				return nil, fmt.Errorf("analysis error: %s (file: %s)", e.Error.Message, e.Error.File)
			}
			// Non-fatal errors are logged but don't stop processing
		case *pb.AnalysisEvent_Complete:
			fmt.Printf("Server reported complete: %d nodes, %d relationships in %dms\n", 
				e.Complete.TotalNodes, e.Complete.TotalRelationships, e.Complete.DurationMs)
		}
	}

	// Build the CodeGraph
	graph := &memgraph.CodeGraph{
		Nodes:         nodes,
		Relationships: relationships,
	}

	if meta != nil {
		graph.Meta = memgraph.GraphMeta{
			Language:      meta.Language,
			ProjectType:   meta.ProjectType,
			SchemaVersion: meta.SchemaVersion,
			AnalyzedAt:    meta.AnalyzedAt,
			ProjectPath:   meta.ProjectPath,
		}
	}

	return graph, nil
}

// protoNodeToMap converts a proto GraphNode to a map for JSON serialization
func protoNodeToMap(node *pb.GraphNode) map[string]any {
	m := make(map[string]any)
	m["id"] = node.Id
	m["type"] = node.Type
	m["labels"] = node.Labels
	
	// Copy all properties, converting numeric strings to integers where needed
	for k, v := range node.Properties {
		// Convert known numeric fields to int
		if k == "line" {
			if lineNum, err := strconv.Atoi(v); err == nil {
				m[k] = lineNum
			} else {
				m[k] = 0
			}
		} else {
			m[k] = v
		}
	}
	
	return m
}
