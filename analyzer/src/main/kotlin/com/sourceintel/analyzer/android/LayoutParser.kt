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
 * Parses Android layout XML files to extract UI component hierarchy.
 * Extracts Views (Button, TextView, etc.) and their parent-child relationships.
 */
class LayoutParser(private val projectPath: Path) {
    private var nodeIdCounter = 0
    
    private fun nextId(): String = "layout_${nodeIdCounter++}"
    
    /**
     * Parse all layout XML files in the project.
     */
    fun parseLayouts(): Pair<List<GraphNode>, List<GraphRelationship>> {
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        // Find all res/layout directories
        val layoutDirs = findLayoutDirectories()
        logger.info { "Found ${layoutDirs.size} layout directories" }
        
        for (layoutDir in layoutDirs) {
            val xmlFiles = Files.list(layoutDir)
                .filter { it.name.endsWith(".xml") }
                .toList()
            
            logger.info { "Parsing ${xmlFiles.size} layout files in ${layoutDir.name}" }
            
            for (xmlFile in xmlFiles) {
                try {
                    parseLayoutFile(xmlFile, nodes, relationships)
                } catch (e: Exception) {
                    logger.warn { "Failed to parse layout ${xmlFile.name}: ${e.message}" }
                }
            }
        }
        
        logger.info { "Extracted ${nodes.size} UI nodes and ${relationships.size} relationships" }
        return Pair(nodes, relationships)
    }
    
    private fun findLayoutDirectories(): List<Path> {
        val layoutDirs = mutableListOf<Path>()
        
        // Find res/layout, res/layout-land, etc.
        Files.walk(projectPath)
            .filter { Files.isDirectory(it) }
            .filter { it.name.startsWith("layout") }
            .filter { it.parent?.name == "res" }
            .forEach { layoutDirs.add(it) }
        
        return layoutDirs
    }
    
    private fun parseLayoutFile(
        xmlFile: Path,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        val layoutName = xmlFile.name.removeSuffix(".xml")
        val relativePath = projectPath.relativize(xmlFile).pathString
        
        // Create Layout node
        val layoutNode = LayoutNode(
            id = nextId(),
            name = layoutName,
            file = relativePath
        )
        nodes.add(layoutNode)
        
        // Parse XML
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(xmlFile.toFile())
        
        // Parse root element and its children
        val rootElement = document.documentElement
        parseViewElement(rootElement, layoutNode.id, relativePath, 1, nodes, relationships)
    }
    
    private fun parseViewElement(
        element: Element,
        parentId: String,
        file: String,
        lineNumber: Int,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        // Get view type (tag name, handles qualified names like androidx.constraintlayout.widget.ConstraintLayout)
        val viewType = element.tagName.substringAfterLast('.')
        
        // Get android:id if present
        val androidId = element.getAttribute("android:id")
        val viewId = if (androidId.isNotEmpty()) {
            // Parse @+id/name or @id/name
            androidId.substringAfter("/", androidId)
        } else {
            // Generate a placeholder ID for views without explicit ID
            "${viewType.lowercase()}_${nodeIdCounter}"
        }
        
        // Get android:text if present
        val text = element.getAttribute("android:text").takeIf { it.isNotEmpty() }
        
        // Create View node
        val viewNode = ViewNode(
            id = nextId(),
            viewId = viewId,
            viewType = viewType,
            text = text,
            file = file,
            line = lineNumber,
            labels = listOf("View", viewType)  // Add specific type as label
        )
        nodes.add(viewNode)
        
        // Create relationship to parent (Layout or parent View)
        relationships.add(GraphRelationship(
            id = nextId(),
            type = RelTypes.CONTAINS,
            startNodeId = parentId,
            endNodeId = viewNode.id
        ))
        
        // Process child elements
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                parseViewElement(
                    child as Element,
                    viewNode.id,
                    file,
                    lineNumber + i,  // Approximate line number
                    nodes,
                    relationships
                )
            }
        }
    }
}
