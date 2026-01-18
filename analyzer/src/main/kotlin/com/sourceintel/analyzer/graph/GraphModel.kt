package com.sourceintel.analyzer.graph

import kotlinx.serialization.Serializable

/**
 * Core graph model - language agnostic base types.
 * All language-specific nodes inherit from these.
 */

@Serializable
data class CodeGraph(
    val meta: GraphMeta,
    val nodes: List<GraphNode>,
    val relationships: List<GraphRelationship>
)

@Serializable
data class GraphMeta(
    val language: String,
    val projectType: String,
    val schemaVersion: String = "1.0",
    val analyzedAt: String,
    val projectPath: String
)

@Serializable
sealed class GraphNode {
    abstract val id: String
    abstract val labels: List<String>
    abstract val properties: Map<String, String>
}

@Serializable
data class TypeNode(
    override val id: String,
    override val labels: List<String>,
    override val properties: Map<String, String>,
    val name: String,
    val fqn: String,
    val kind: TypeKind,
    val file: String,
    val line: Int
) : GraphNode()

@Serializable
data class FunctionNode(
    override val id: String,
    override val labels: List<String>,
    override val properties: Map<String, String>,
    val name: String,
    val fqn: String,
    val signature: String,
    val file: String,
    val line: Int,
    val isAsync: Boolean = false
) : GraphNode()

@Serializable
data class FieldNode(
    override val id: String,
    override val labels: List<String>,
    override val properties: Map<String, String>,
    val name: String,
    val fqn: String,
    val typeFqn: String,
    val isMutable: Boolean,
    val file: String,
    val line: Int
) : GraphNode()

@Serializable
data class ModuleNode(
    override val id: String,
    override val labels: List<String> = listOf("Module"),
    override val properties: Map<String, String>,
    val name: String,
    val path: String,
    val moduleType: String
) : GraphNode()

@Serializable
data class PackageNode(
    override val id: String,
    override val labels: List<String> = listOf("Package"),
    override val properties: Map<String, String>,
    val name: String,
    val fqn: String
) : GraphNode()

@Serializable
data class FileNode(
    override val id: String,
    override val labels: List<String> = listOf("File"),
    override val properties: Map<String, String>,
    val name: String,
    val path: String,
    val language: String
) : GraphNode()

@Serializable
enum class TypeKind {
    CLASS, INTERFACE, OBJECT, ENUM, ANNOTATION, TYPE_ALIAS, STRUCT
}

// Android UI Nodes
@Serializable
data class LayoutNode(
    override val id: String,
    override val labels: List<String> = listOf("Layout"),
    override val properties: Map<String, String> = emptyMap(),
    val name: String,
    val file: String
) : GraphNode()

@Serializable
data class ViewNode(
    override val id: String,
    override val labels: List<String> = listOf("View"),
    override val properties: Map<String, String> = emptyMap(),
    val viewId: String,          // e.g., "btn_login"
    val viewType: String,        // e.g., "Button", "TextView"
    val text: String? = null,    // android:text if present
    val file: String,
    val line: Int
) : GraphNode()

@Serializable
data class NavDestinationNode(
    override val id: String,
    override val labels: List<String> = listOf("NavDestination"),
    override val properties: Map<String, String> = emptyMap(),
    val navId: String,           // e.g., "homeFragment"
    val label: String?,          // android:label
    val className: String,       // class FQN
    val navGraphFile: String
) : GraphNode()

@Serializable
data class GraphRelationship(
    val id: String,
    val type: String,
    val startNodeId: String,
    val endNodeId: String,
    val properties: Map<String, String> = emptyMap()
)

// Common relationship types
object RelTypes {
    const val CONTAINS = "CONTAINS"
    const val DECLARES = "DECLARES"
    const val MEMBER = "MEMBER"
    const val EXTENDS = "EXTENDS"
    const val IMPLEMENTS = "IMPLEMENTS"
    const val CALLS = "CALLS"
    const val REFERENCES = "REFERENCES"
    const val RETURNS = "RETURNS"
    const val PARAMETER = "PARAMETER"
    const val HAS_TYPE = "HAS_TYPE"
    
    // Architecture-specific
    const val CALLS_USE_CASE = "CALLS_USE_CASE"
    const val USES_REPOSITORY = "USES_REPOSITORY"
    const val USES_DATASOURCE = "USES_DATASOURCE"
    const val CALLS_API = "CALLS_API"
    const val PROVIDES = "PROVIDES"
    const val BINDS = "BINDS"
    
    // Android UI
    const val USES_LAYOUT = "USES_LAYOUT"
    const val CHILD_OF = "CHILD_OF"
    const val NAVIGATES_TO = "NAVIGATES_TO"
    const val IMPLEMENTED_BY = "IMPLEMENTED_BY"
}
