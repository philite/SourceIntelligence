package memgraph

import "encoding/json"

// CodeGraph represents the graph data structure from the Kotlin analyzer
type CodeGraph struct {
	Meta          GraphMeta         `json:"meta"`
	Nodes         []json.RawMessage `json:"nodes"`
	Relationships []GraphRelationship `json:"relationships"`
}

// GraphMeta contains metadata about the analyzed project
type GraphMeta struct {
	Language      string `json:"language"`
	ProjectType   string `json:"projectType"`
	SchemaVersion string `json:"schemaVersion"`
	AnalyzedAt    string `json:"analyzedAt"`
	ProjectPath   string `json:"projectPath"`
}

// GenericNode represents any node with dynamic fields
type GenericNode struct {
	Type       string            `json:"type"`
	ID         string            `json:"id"`
	Labels     []string          `json:"labels"`
	Properties map[string]string `json:"properties"`
	// Common additional fields from Kotlin nodes
	Name      string `json:"name,omitempty"`
	Fqn       string `json:"fqn,omitempty"`
	Path      string `json:"path,omitempty"`
	File      string `json:"file,omitempty"`
	Line      int    `json:"line,omitempty"`
	Kind      string `json:"kind,omitempty"`
	Signature string `json:"signature,omitempty"`
	TypeFqn   string `json:"typeFqn,omitempty"`
	IsMutable bool   `json:"isMutable,omitempty"`
	IsAsync   bool   `json:"isAsync,omitempty"`
	Language  string `json:"language,omitempty"`
	ModuleType string `json:"moduleType,omitempty"`
}

// ToProperties merges all node fields into a single properties map for Memgraph
func (n *GenericNode) ToProperties() map[string]any {
	props := make(map[string]any)
	props["id"] = n.ID
	
	if n.Name != "" {
		props["name"] = n.Name
	}
	if n.Fqn != "" {
		props["fqn"] = n.Fqn
	}
	if n.Path != "" {
		props["path"] = n.Path
	}
	if n.File != "" {
		props["file"] = n.File
	}
	if n.Line > 0 {
		props["line"] = n.Line
	}
	if n.Kind != "" {
		props["kind"] = n.Kind
	}
	if n.Signature != "" {
		props["signature"] = n.Signature
	}
	if n.TypeFqn != "" {
		props["typeFqn"] = n.TypeFqn
	}
	if n.IsMutable {
		props["isMutable"] = true
	}
	if n.IsAsync {
		props["isAsync"] = true
	}
	if n.Language != "" {
		props["language"] = n.Language
	}
	if n.ModuleType != "" {
		props["moduleType"] = n.ModuleType
	}
	
	// Add all from properties map
	for k, v := range n.Properties {
		props[k] = v
	}
	
	return props
}

// GraphRelationship represents a relationship between nodes
type GraphRelationship struct {
	ID          string            `json:"id"`
	Type        string            `json:"type"`
	StartNodeID string            `json:"startNodeId"`
	EndNodeID   string            `json:"endNodeId"`
	Properties  map[string]string `json:"properties"`
}
