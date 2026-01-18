package exporter

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	pb "github.com/sourceintel/cli/pkg/proto"
)

type CSVExporter struct {
	NodeFile   *os.File
	RelFile    *os.File
	NodeW      *csv.Writer
	RelW       *csv.Writer
	NodePath   string
	RelPath    string
}

func NewCSVExporter(outputDir string) (*CSVExporter, error) {
	// Ensure dir exists
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create export dir: %w", err)
	}

	nodePath := filepath.Join(outputDir, "nodes.csv")
	relPath := filepath.Join(outputDir, "relationships.csv")

	// Open Node File
	nf, err := os.Create(nodePath)
	if err != nil {
		return nil, err
	}
	nw := csv.NewWriter(nf)
	// Write Node Header: ID, Labels (colon separated), Properties (JSON)
	if err := nw.Write([]string{"id", "labels", "properties"}); err != nil {
		return nil, err
	}

	// Open Relationship File
	rf, err := os.Create(relPath)
	if err != nil {
		nf.Close()
		return nil, err
	}
	rw := csv.NewWriter(rf)
	// Write Rel Header: StartID, EndID, Type, Properties (JSON)
	if err := rw.Write([]string{"start_id", "end_id", "type", "properties"}); err != nil {
		nf.Close()
		rf.Close()
		return nil, err
	}

	return &CSVExporter{
		NodeFile: nf, RelFile: rf,
		NodeW: nw, RelW: rw,
		NodePath: nodePath, RelPath: relPath,
	}, nil
}

func (e *CSVExporter) WriteNode(node *pb.GraphNode) error {
	// 1. Flatten labels to "Label1:Label2"
	labels := strings.Join(node.Labels, ":")

	// 2. Merge all properties (proto props + id + name etc) into one map for JSON serialization
	// We use the helper from your existing code to normalize types (int vs string)
	propsMap := protoNodeToMap(node)
	
	// Remove 'labels' and 'type' from propsMap as they are handled explicitly in CSV columns
	delete(propsMap, "labels")
	delete(propsMap, "type")

	propsJson, err := json.Marshal(propsMap)
	if err != nil {
		return err
	}

	return e.NodeW.Write([]string{
		node.Id,
		labels,
		string(propsJson),
	})
}

func (e *CSVExporter) WriteRelationship(rel *pb.GraphRelationship) error {
	propsJson, err := json.Marshal(rel.Properties)
	if err != nil {
		return err
	}

	return e.RelW.Write([]string{
		rel.StartNodeId,
		rel.EndNodeId,
		rel.Type,
		string(propsJson),
	})
}

func (e *CSVExporter) Close() {
	e.NodeW.Flush()
	e.RelW.Flush()
	e.NodeFile.Close()
	e.RelFile.Close()
}

// Reuse your existing helper logic
func protoNodeToMap(node *pb.GraphNode) map[string]any {
	m := make(map[string]any)
	m["id"] = node.Id
	m["type"] = node.Type
	m["labels"] = node.Labels

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
