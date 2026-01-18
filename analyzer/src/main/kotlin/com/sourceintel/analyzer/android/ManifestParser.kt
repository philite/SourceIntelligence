package com.sourceintel.analyzer.android

import com.sourceintel.analyzer.graph.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.pathString

private val logger = KotlinLogging.logger {}

/**
 * Parses AndroidManifest.xml to extract declared Android components
 * (Activities, Services, BroadcastReceivers, ContentProviders).
 */
class ManifestParser(private val projectPath: Path) {
    private var nodeIdCounter = 0
    
    private fun nextId(): String = "manifest_${nodeIdCounter++}"
    
    /**
     * Parse all AndroidManifest.xml files in the project.
     */
    fun parseManifests(): Pair<List<GraphNode>, List<GraphRelationship>> {
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        // Find all AndroidManifest.xml files
        val manifestFiles = findManifestFiles()
        logger.info { "Found ${manifestFiles.size} manifest files" }
        
        for (manifestFile in manifestFiles) {
            try {
                parseManifestFile(manifestFile, nodes, relationships)
            } catch (e: Exception) {
                logger.warn { "Failed to parse manifest ${manifestFile}: ${e.message}" }
            }
        }
        
        logger.info { "Extracted ${nodes.size} manifest nodes and ${relationships.size} relationships" }
        return Pair(nodes, relationships)
    }
    
    private fun findManifestFiles(): List<Path> {
        val manifests = mutableListOf<Path>()
        
        Files.walk(projectPath)
            .filter { it.fileName.toString() == "AndroidManifest.xml" }
            .filter { !it.pathString.contains("build") }  // Skip build directories
            .forEach { manifests.add(it) }
        
        return manifests
    }
    
    private fun parseManifestFile(
        xmlFile: Path,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        val relativePath = projectPath.relativize(xmlFile).pathString
        
        // Parse XML
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(xmlFile.toFile())
        
        val manifestElement = document.documentElement
        val packageName = manifestElement.getAttribute("package")
        
        // Create Manifest node
        val manifestNode = TypeNode(
            id = nextId(),
            labels = listOf("Manifest"),
            properties = mapOf("package" to packageName),
            name = "AndroidManifest",
            fqn = packageName,
            kind = TypeKind.CLASS,  // Using CLASS as placeholder
            file = relativePath,
            line = 1
        )
        nodes.add(manifestNode)
        
        // Find application element
        val appElements = manifestElement.getElementsByTagName("application")
        if (appElements.length > 0) {
            val appElement = appElements.item(0) as Element
            
            // Parse activities
            parseComponents(appElement, "activity", packageName, manifestNode.id, nodes, relationships)
            
            // Parse services
            parseComponents(appElement, "service", packageName, manifestNode.id, nodes, relationships)
            
            // Parse receivers
            parseComponents(appElement, "receiver", packageName, manifestNode.id, nodes, relationships)
            
            // Parse providers
            parseComponents(appElement, "provider", packageName, manifestNode.id, nodes, relationships)
        }
    }
    
    private fun parseComponents(
        appElement: Element,
        componentType: String,
        packageName: String,
        manifestNodeId: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        val elements = appElement.getElementsByTagName(componentType)
        
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            var className = element.getAttribute("android:name")
            
            // Resolve relative class names
            if (className.startsWith(".")) {
                className = packageName + className
            }
            
            if (className.isNotEmpty()) {
                val componentNode = TypeNode(
                    id = nextId(),
                    labels = listOf("AndroidComponent", componentType.replaceFirstChar { it.uppercase() }),
                    properties = mapOf(
                        "componentType" to componentType,
                        "exported" to element.getAttribute("android:exported")
                    ),
                    name = className.substringAfterLast('.'),
                    fqn = className,
                    kind = TypeKind.CLASS,
                    file = "",  // Will be resolved by linking
                    line = 0
                )
                nodes.add(componentNode)
                
                // Link to manifest
                relationships.add(GraphRelationship(
                    id = nextId(),
                    type = RelTypes.DECLARES,
                    startNodeId = manifestNodeId,
                    endNodeId = componentNode.id
                ))
            }
        }
    }
}
