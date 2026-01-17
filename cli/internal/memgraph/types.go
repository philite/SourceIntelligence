package memgraph

// CodeGraph represents the graph data structure from the Kotlin analyzer
type CodeGraph struct {
	Meta          GraphMeta           `json:"meta"`
	Nodes         []GraphNode         `json:"nodes"`
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

// GraphNode represents a node in the code graph
type GraphNode struct {
	ID         string            `json:"id"`
	Labels     []string          `json:"labels"`
	Properties map[string]string `json:"properties"`
}

// GraphRelationship represents a relationship between nodes
type GraphRelationship struct {
	ID          string            `json:"id"`
	Type        string            `json:"type"`
	StartNodeID string            `json:"startNodeId"`
	EndNodeID   string            `json:"endNodeId"`
	Properties  map[string]string `json:"properties"`
}
