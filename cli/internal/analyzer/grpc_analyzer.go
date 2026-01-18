package analyzer

import (
	"context"
	"fmt"
	"io"
	"time"

	"github.com/sourceintel/cli/internal/exporter"
	"github.com/sourceintel/cli/internal/memgraph" // Needed for legacy AnalyzeProject
	pb "github.com/sourceintel/cli/pkg/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"encoding/json"
)

// GrpcAnalyzer uses gRPC to communicate with the Kotlin analyzer server.
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

// AnalyzeAndExport streams data from gRPC directly to CSV files (THE NEW FAST METHOD)
func (a *GrpcAnalyzer) AnalyzeAndExport(
	ctx context.Context,
	projectPath string,
	moduleName string,
	fullK2 bool,
	android bool,
	csvExporter *exporter.CSVExporter,
) (int, int, error) {
	if err := a.connect(); err != nil {
		return 0, 0, err
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
		return 0, 0, fmt.Errorf("gRPC analyze failed: %w", err)
	}

	nodeCount := 0
	relCount := 0
	lastLog := time.Now()

	for {
		event, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nodeCount, relCount, fmt.Errorf("stream receive failed: %w", err)
		}

		switch e := event.Event.(type) {
		case *pb.AnalysisEvent_Node:
			if err := csvExporter.WriteNode(e.Node); err != nil {
				return nodeCount, relCount, err
			}
			nodeCount++
		case *pb.AnalysisEvent_Relationship:
			if err := csvExporter.WriteRelationship(e.Relationship); err != nil {
				return nodeCount, relCount, err
			}
			relCount++
		case *pb.AnalysisEvent_Error:
			if e.Error.Fatal {
				return nodeCount, relCount, fmt.Errorf("analysis fatal error: %s", e.Error.Message)
			}
			fmt.Printf("Analysis warning: %s\n", e.Error.Message)
		}

		// Progress logging
		if time.Since(lastLog) > 2*time.Second {
			fmt.Printf("Streaming... %d nodes, %d relationships written\n", nodeCount, relCount)
			lastLog = time.Now()
		}
	}
    
	// Ensure all data is flushed to disk
	csvExporter.Close() 
	return nodeCount, relCount, nil
}

// AnalyzeProject returns the legacy CodeGraph struct (Only needed if you use non-CSV mode)
func (a *GrpcAnalyzer) AnalyzeProject(
	ctx context.Context,
	projectPath string,
	moduleName string,
	fullK2 bool,
	android bool,
) (*memgraph.CodeGraph, error) {
	// ... (We can keep this stub or the full implementation if you still need legacy support)
    // For now, let's include the implementation to satisfy any other code calling it.
    
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
        return nil, err
    }

    nodes := make([]json.RawMessage, 0)
    relationships := make([]memgraph.GraphRelationship, 0)

    for {
        event, err := stream.Recv()
        if err == io.EOF { break }
        if err != nil { return nil, err }

        switch e := event.Event.(type) {
        case *pb.AnalysisEvent_Node:
             // We need the helper function 'protoNodeToMap' here or copied
             m := make(map[string]any)
             m["id"] = e.Node.Id
             m["type"] = e.Node.Type
             m["labels"] = e.Node.Labels
             for k, v := range e.Node.Properties { m[k] = v }
             
             nodeJSON, _ := json.Marshal(m)
             nodes = append(nodes, nodeJSON)
             
        case *pb.AnalysisEvent_Relationship:
            relationships = append(relationships, memgraph.GraphRelationship{
                ID:          e.Relationship.Id,
                Type:        e.Relationship.Type,
                StartNodeID: e.Relationship.StartNodeId,
                EndNodeID:   e.Relationship.EndNodeId,
                Properties:  e.Relationship.Properties,
            })
        }
    }

    return &memgraph.CodeGraph{
        Nodes:         nodes,
        Relationships: relationships,
    }, nil
}
