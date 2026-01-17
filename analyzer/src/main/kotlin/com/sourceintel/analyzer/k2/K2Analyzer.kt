package com.sourceintel.analyzer.k2

import com.sourceintel.analyzer.graph.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.walk

private val logger = KotlinLogging.logger {}

/**
 * K2 Analysis API based Kotlin analyzer.
 * 
 * Note: Full K2 Analysis API integration requires complex setup.
 * This is a scaffold that starts with AST-level parsing and will be
 * enhanced with semantic analysis once K2 dependencies are resolved.
 */
class K2Analyzer(
    private val projectPath: Path
) {
    private var nodeIdCounter = 0
    
    fun analyze(moduleName: String? = null): CodeGraph {
        logger.info { "Starting analysis of: $projectPath" }
        
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        // Find all Kotlin source files
        val kotlinFiles = findKotlinFiles(projectPath)
        logger.info { "Found ${kotlinFiles.size} Kotlin files" }
        
        // Analyze each file
        kotlinFiles.forEach { file ->
            try {
                val fileNodes = analyzeFile(file)
                nodes.addAll(fileNodes.nodes)
                relationships.addAll(fileNodes.relationships)
            } catch (e: Exception) {
                logger.warn { "Failed to analyze file $file: ${e.message}" }
            }
        }
        
        val meta = GraphMeta(
            language = "kotlin",
            projectType = detectProjectType(projectPath),
            analyzedAt = Instant.now().toString(),
            projectPath = projectPath.toString()
        )
        
        return CodeGraph(
            meta = meta,
            nodes = nodes,
            relationships = relationships
        )
    }
    
    private fun findKotlinFiles(root: Path): List<Path> {
        return root.walk()
            .filter { it.name.endsWith(".kt") || it.name.endsWith(".kts") }
            .filter { !it.toString().contains("build") }
            .filter { !it.toString().contains(".gradle") }
            .toList()
    }
    
    private fun detectProjectType(path: Path): String {
        val hasAndroidManifest = path.resolve("app/src/main/AndroidManifest.xml").exists() ||
                path.walk().any { it.name == "AndroidManifest.xml" }
        val hasBuildGradle = path.resolve("build.gradle.kts").exists() ||
                path.resolve("build.gradle").exists()
        
        return when {
            hasAndroidManifest -> "android"
            hasBuildGradle -> "kotlin-jvm"
            else -> "kotlin"
        }
    }
    
    private fun analyzeFile(file: Path): FileAnalysisResult {
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        val content = file.toFile().readText()
        val relativePath = projectPath.relativize(file).toString()
        
        // Create file node
        val fileNode = FileNode(
            id = nextId(),
            properties = emptyMap(),
            name = file.name,
            path = relativePath,
            language = "kotlin"
        )
        nodes.add(fileNode)
        
        // Extract package
        val packageName = extractPackage(content)
        if (packageName != null) {
            val packageNode = PackageNode(
                id = nextId(),
                properties = emptyMap(),
                name = packageName.substringAfterLast('.'),
                fqn = packageName
            )
            nodes.add(packageNode)
            relationships.add(GraphRelationship(
                id = nextId(),
                type = RelTypes.CONTAINS,
                startNodeId = packageNode.id,
                endNodeId = fileNode.id
            ))
        }
        
        // Extract classes, interfaces, objects
        val typeDeclarations = extractTypeDeclarations(content, file, packageName ?: "")
        nodes.addAll(typeDeclarations.nodes)
        relationships.addAll(typeDeclarations.relationships)
        
        // Link file to its declarations
        typeDeclarations.nodes.filterIsInstance<TypeNode>().forEach { typeNode ->
            relationships.add(GraphRelationship(
                id = nextId(),
                type = RelTypes.DECLARES,
                startNodeId = fileNode.id,
                endNodeId = typeNode.id
            ))
        }
        
        return FileAnalysisResult(nodes, relationships)
    }
    
    private fun extractPackage(content: String): String? {
        val regex = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)
    }
    
    private fun extractTypeDeclarations(
        content: String,
        file: Path,
        packageName: String
    ): FileAnalysisResult {
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        val relativePath = projectPath.relativize(file).toString()
        
        // Match class/interface/object/enum/annotation declarations
        val typeRegex = Regex(
            """(?:@\w+(?:\([^)]*\))?\s*\n\s*)*""" +  // Annotations
            """(data\s+|sealed\s+|inner\s+|open\s+|abstract\s+|private\s+|internal\s+|public\s+)*""" +  // Modifiers
            """(class|interface|object|enum\s+class|annotation\s+class)\s+(\w+)""" +  // Kind and name
            """(?:<[^>]+>)?""" +  // Type parameters
            """(?:\s*:\s*([^{]+))?"""  // Inheritance
        )
        
        var lineNumber = 1
        content.lines().forEachIndexed { index, line ->
            typeRegex.find(line)?.let { match ->
                val modifiers = match.groupValues[1].trim()
                val kindStr = match.groupValues[2].trim()
                val name = match.groupValues[3]
                val inheritance = match.groupValues.getOrNull(4)?.trim()
                
                val kind = when {
                    kindStr.contains("interface") -> TypeKind.INTERFACE
                    kindStr.contains("object") -> TypeKind.OBJECT
                    kindStr.contains("enum") -> TypeKind.ENUM
                    kindStr.contains("annotation") -> TypeKind.ANNOTATION
                    else -> TypeKind.CLASS
                }
                
                val fqn = if (packageName.isNotEmpty()) "$packageName.$name" else name
                
                val labels = buildList {
                    add("Type")
                    when (kind) {
                        TypeKind.CLASS -> add("Class")
                        TypeKind.INTERFACE -> add("Interface")
                        TypeKind.OBJECT -> add("Object")
                        TypeKind.ENUM -> add("Enum")
                        TypeKind.ANNOTATION -> add("Annotation")
                        else -> {}
                    }
                    // Add Kotlin-specific labels
                    if (modifiers.contains("data")) add("DataClass")
                    if (modifiers.contains("sealed")) add("SealedClass")
                    
                    // Detect architecture patterns
                    when {
                        name.endsWith("ViewModel") -> add("ViewModel")
                        name.endsWith("UseCase") -> add("UseCase")
                        name.endsWith("Repository") -> add("Repository")
                        name.endsWith("DataSource") -> add("DataSource")
                        name.endsWith("Activity") -> add("Activity")
                        name.endsWith("Fragment") -> add("Fragment")
                        name.endsWith("Dao") -> add("RoomDao")
                    }
                }
                
                val properties = buildMap {
                    if (modifiers.contains("data")) put("isData", "true")
                    if (modifiers.contains("sealed")) put("isSealed", "true")
                    inheritance?.let { put("inheritance", it) }
                }
                
                val typeNode = TypeNode(
                    id = nextId(),
                    labels = labels,
                    properties = properties,
                    name = name,
                    fqn = fqn,
                    kind = kind,
                    file = relativePath,
                    line = index + 1
                )
                nodes.add(typeNode)
                
                // Extract inheritance relationships
                if (!inheritance.isNullOrBlank()) {
                    extractInheritance(inheritance, typeNode, relationships)
                }
            }
        }
        
        return FileAnalysisResult(nodes, relationships)
    }
    
    private fun extractInheritance(
        inheritance: String,
        typeNode: TypeNode,
        relationships: MutableList<GraphRelationship>
    ) {
        // Parse inheritance: "BaseClass(), Interface1, Interface2"
        inheritance.split(",").map { it.trim() }.forEach { parent ->
            val parentName = parent.replace(Regex("""\([^)]*\)"""), "").trim()
            if (parentName.isNotEmpty()) {
                // We'll create placeholder nodes for parents
                // Full resolution requires semantic analysis
                relationships.add(GraphRelationship(
                    id = nextId(),
                    type = if (parent.contains("(")) RelTypes.EXTENDS else RelTypes.IMPLEMENTS,
                    startNodeId = typeNode.id,
                    endNodeId = "unresolved:$parentName",
                    properties = mapOf("parentName" to parentName)
                ))
            }
        }
    }
    
    private fun nextId(): String = "node_${nodeIdCounter++}"
    
    private data class FileAnalysisResult(
        val nodes: List<GraphNode>,
        val relationships: List<GraphRelationship>
    )
}
