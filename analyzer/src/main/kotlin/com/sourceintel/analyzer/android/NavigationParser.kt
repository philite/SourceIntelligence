package com.sourceintel.analyzer.android

import com.sourceintel.analyzer.graph.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.name
import kotlin.io.path.pathString

private val logger = KotlinLogging.logger {}

/**
 * Parses Android Navigation Graph XML files to extract screen destinations
 * and navigation actions between them.
 */
class NavigationParser(private val projectPath: Path) {
    private var nodeIdCounter = 0
    
    private fun nextId(): String = "nav_${nodeIdCounter++}"
    
    /**
     * Parse all navigation XML files in the project.
     */
    fun parseNavigationGraphs(): Pair<List<GraphNode>, List<GraphRelationship>> {
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        // Find all res/navigation directories
        val navDirs = findNavigationDirectories()
        logger.info { "Found ${navDirs.size} navigation directories" }
        
        for (navDir in navDirs) {
            val xmlFiles = Files.list(navDir)
                .filter { it.name.endsWith(".xml") }
                .toList()
            
            logger.info { "Parsing ${xmlFiles.size} navigation files in ${navDir.name}" }
            
            for (xmlFile in xmlFiles) {
                try {
                    parseNavigationFile(xmlFile, nodes, relationships)
                } catch (e: Exception) {
                    logger.warn { "Failed to parse navigation ${xmlFile.name}: ${e.message}" }
                }
            }
        }
        
        logger.info { "Extracted ${nodes.size} navigation nodes and ${relationships.size} relationships" }
        return Pair(nodes, relationships)
    }
    
    private fun findNavigationDirectories(): List<Path> {
        val navDirs = mutableListOf<Path>()
        
        Files.walk(projectPath)
            .filter { Files.isDirectory(it) }
            .filter { it.name == "navigation" }
            .filter { it.parent?.name == "res" }
            .forEach { navDirs.add(it) }
        
        return navDirs
    }
    
    private fun parseNavigationFile(
        xmlFile: Path,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        val relativePath = projectPath.relativize(xmlFile).pathString
        
        // Parse XML
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(xmlFile.toFile())
        
        val rootElement = document.documentElement
        
        // Map to store destination IDs for action resolution
        val destinationMap = mutableMapOf<String, String>()  // navId -> nodeId
        
        // First pass: collect all destinations
        collectDestinations(rootElement, relativePath, nodes, destinationMap)
        
        // Second pass: create navigation actions
        createNavigationActions(rootElement, destinationMap, relationships)
    }
    
    private fun collectDestinations(
        element: Element,
        file: String,
        nodes: MutableList<GraphNode>,
        destinationMap: MutableMap<String, String>
    ) {
        val tagName = element.tagName
        
        // Handle fragment, activity, dialog destinations
        if (tagName == "fragment" || tagName == "activity" || tagName == "dialog") {
            val navId = element.getAttribute("android:id")
                .substringAfter("/", "")
            val label = element.getAttribute("android:label").takeIf { it.isNotEmpty() }
            val className = element.getAttribute("android:name")
            
            if (navId.isNotEmpty() && className.isNotEmpty()) {
                val node = NavDestinationNode(
                    id = nextId(),
                    navId = navId,
                    label = label,
                    className = className,
                    navGraphFile = file,
                    labels = listOf("NavDestination", tagName.replaceFirstChar { it.uppercase() })
                )
                nodes.add(node)
                destinationMap[navId] = node.id
            }
        }
        
        // Process children
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                collectDestinations(child as Element, file, nodes, destinationMap)
            }
        }
    }
    
    private fun createNavigationActions(
        element: Element,
        destinationMap: Map<String, String>,
        relationships: MutableList<GraphRelationship>
    ) {
        val tagName = element.tagName
        
        // Find containing destination
        val parentNavId = element.getAttribute("android:id")
            .substringAfter("/", "")
        val sourceNodeId = destinationMap[parentNavId]
        
        // Look for action elements
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val childElement = child as Element
                
                if (childElement.tagName == "action") {
                    val actionId = childElement.getAttribute("android:id")
                        .substringAfter("/", "")
                    val destinationId = childElement.getAttribute("app:destination")
                        .substringAfter("/", "")
                    
                    val targetNodeId = destinationMap[destinationId]
                    
                    if (sourceNodeId != null && targetNodeId != null) {
                        relationships.add(GraphRelationship(
                            id = nextId(),
                            type = RelTypes.NAVIGATES_TO,
                            startNodeId = sourceNodeId,
                            endNodeId = targetNodeId,
                            properties = mapOf("actionId" to actionId)
                        ))
                    }
                } else {
                    // Recurse into nested elements
                    createNavigationActions(childElement, destinationMap, relationships)
                }
            }
        }
    }
}
