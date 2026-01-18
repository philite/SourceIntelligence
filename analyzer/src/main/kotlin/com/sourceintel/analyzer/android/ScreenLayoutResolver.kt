package com.sourceintel.analyzer.android

import com.sourceintel.analyzer.graph.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

/**
 * Resolves relationships between Activities/Fragments and their layouts.
 * Uses pattern matching on Kotlin source files to find:
 * 1. setContentView(R.layout.xxx) calls
 * 2. inflater.inflate(R.layout.xxx, ...) calls
 * 3. View Binding patterns (ActivityMainBinding -> activity_main.xml)
 */
class ScreenLayoutResolver(private val projectPath: Path) {
    private var nodeIdCounter = 0
    
    private fun nextId(): String = "screen_layout_${nodeIdCounter++}"
    
    // Regex patterns for layout references
    private val setContentViewPattern = Regex("""setContentView\s*\(\s*R\.layout\.(\w+)""")
    private val inflatePattern = Regex("""inflate\s*\(\s*R\.layout\.(\w+)""")
    private val viewBindingPattern = Regex("""(\w+)Binding\.inflate""")
    
    /**
     * Analyze Kotlin source files to find Activity/Fragment -> Layout relationships.
     * Returns relationships that link existing class nodes to layout nodes.
     */
    fun resolveScreenLayouts(
        existingNodes: List<GraphNode>,
        layoutNodes: List<GraphNode>
    ): List<GraphRelationship> {
        val relationships = mutableListOf<GraphRelationship>()
        
        // Build lookup maps
        val classesByName = existingNodes
            .filterIsInstance<TypeNode>()
            .associateBy { it.name }
        
        val layoutsByName = layoutNodes
            .filterIsInstance<LayoutNode>()
            .associateBy { it.name }
        
        logger.info { "Resolving screen layouts: ${classesByName.size} classes, ${layoutsByName.size} layouts" }
        
        // Find all Kotlin files
        val ktFiles = findKotlinFiles()
        logger.info { "Scanning ${ktFiles.size} Kotlin files for layout references" }
        
        for (ktFile in ktFiles) {
            try {
                val content = ktFile.readText()
                val className = ktFile.name.removeSuffix(".kt")
                val classNode = classesByName[className]
                
                if (classNode != null) {
                    // Check for setContentView
                    setContentViewPattern.findAll(content).forEach { match ->
                        val layoutName = match.groupValues[1]
                        val layoutNode = layoutsByName[layoutName]
                        
                        if (layoutNode != null) {
                            relationships.add(GraphRelationship(
                                id = nextId(),
                                type = RelTypes.USES_LAYOUT,
                                startNodeId = classNode.id,
                                endNodeId = layoutNode.id,
                                properties = mapOf("method" to "setContentView")
                            ))
                            logger.debug { "Found: ${classNode.name} -> $layoutName (setContentView)" }
                        }
                    }
                    
                    // Check for inflate
                    inflatePattern.findAll(content).forEach { match ->
                        val layoutName = match.groupValues[1]
                        val layoutNode = layoutsByName[layoutName]
                        
                        if (layoutNode != null) {
                            relationships.add(GraphRelationship(
                                id = nextId(),
                                type = RelTypes.USES_LAYOUT,
                                startNodeId = classNode.id,
                                endNodeId = layoutNode.id,
                                properties = mapOf("method" to "inflate")
                            ))
                            logger.debug { "Found: ${classNode.name} -> $layoutName (inflate)" }
                        }
                    }
                    
                    // Check for View Binding
                    viewBindingPattern.findAll(content).forEach { match ->
                        val bindingClassName = match.groupValues[1]
                        // Convert ActivityMainBinding -> activity_main
                        val layoutName = bindingClassToLayoutName(bindingClassName)
                        val layoutNode = layoutsByName[layoutName]
                        
                        if (layoutNode != null) {
                            relationships.add(GraphRelationship(
                                id = nextId(),
                                type = RelTypes.USES_LAYOUT,
                                startNodeId = classNode.id,
                                endNodeId = layoutNode.id,
                                properties = mapOf("method" to "viewBinding", "bindingClass" to bindingClassName)
                            ))
                            logger.debug { "Found: ${classNode.name} -> $layoutName (ViewBinding)" }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to scan ${ktFile.name}: ${e.message}" }
            }
        }
        
        logger.info { "Resolved ${relationships.size} screen-layout relationships" }
        return relationships
    }
    
    private fun findKotlinFiles(): List<Path> {
        return Files.walk(projectPath)
            .filter { it.name.endsWith(".kt") }
            .filter { !it.pathString.contains("build") }
            .toList()
    }
    
    /**
     * Convert ViewBinding class name to layout file name.
     * ActivityMainBinding -> activity_main
     * FragmentHomeBinding -> fragment_home
     */
    private fun bindingClassToLayoutName(bindingClassName: String): String {
        // Remove "Binding" suffix
        val baseName = bindingClassName.removeSuffix("Binding")
        
        // Convert PascalCase to snake_case
        val snakeCase = baseName.replace(Regex("([a-z])([A-Z])")) { 
            "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" 
        }.lowercase()
        
        return snakeCase
    }
}
