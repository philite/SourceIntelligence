package com.sourceintel.analyzer.android

import com.sourceintel.analyzer.graph.CodeGraph
import com.sourceintel.analyzer.graph.GraphNode
import com.sourceintel.analyzer.graph.GraphRelationship
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates Android-specific parsing for layouts, navigation, and manifests.
 * Runs all parsers and merges results into the existing code graph.
 */
class AndroidEnricher(private val projectPath: Path) {
    
    /**
     * Run all Android parsers and return combined nodes and relationships.
     * @param existingCodeNodes Nodes from K2 analysis (classes, functions) for cross-referencing
     */
    fun enrich(existingCodeNodes: List<GraphNode> = emptyList()): Pair<List<GraphNode>, List<GraphRelationship>> {
        val allNodes = mutableListOf<GraphNode>()
        val allRelationships = mutableListOf<GraphRelationship>()
        var layoutNodes = listOf<GraphNode>()
        
        logger.info { "Starting Android enrichment for $projectPath" }
        
        // Parse layouts
        try {
            val layoutParser = LayoutParser(projectPath)
            val (nodes, rels) = layoutParser.parseLayouts()
            layoutNodes = nodes
            allNodes.addAll(nodes)
            allRelationships.addAll(rels)
            logger.info { "Layout parsing: ${nodes.size} nodes, ${rels.size} relationships" }
        } catch (e: Exception) {
            logger.warn { "Layout parsing failed: ${e.message}" }
        }
        
        // Parse navigation graphs
        try {
            val navParser = NavigationParser(projectPath)
            val (navNodes, navRels) = navParser.parseNavigationGraphs()
            allNodes.addAll(navNodes)
            allRelationships.addAll(navRels)
            logger.info { "Navigation parsing: ${navNodes.size} nodes, ${navRels.size} relationships" }
        } catch (e: Exception) {
            logger.warn { "Navigation parsing failed: ${e.message}" }
        }
        
        // Parse manifests
        try {
            val manifestParser = ManifestParser(projectPath)
            val (manifestNodes, manifestRels) = manifestParser.parseManifests()
            allNodes.addAll(manifestNodes)
            allRelationships.addAll(manifestRels)
            logger.info { "Manifest parsing: ${manifestNodes.size} nodes, ${manifestRels.size} relationships" }
        } catch (e: Exception) {
            logger.warn { "Manifest parsing failed: ${e.message}" }
        }
        
        // Resolve screen -> layout relationships (if we have existing code nodes)
        if (existingCodeNodes.isNotEmpty() && layoutNodes.isNotEmpty()) {
            try {
                val resolver = ScreenLayoutResolver(projectPath)
                val screenLayoutRels = resolver.resolveScreenLayouts(existingCodeNodes, layoutNodes)
                allRelationships.addAll(screenLayoutRels)
                logger.info { "Screen-layout resolution: ${screenLayoutRels.size} relationships" }
            } catch (e: Exception) {
                logger.warn { "Screen-layout resolution failed: ${e.message}" }
            }
        }
        
        // Analyze Compose UI (if we have existing code nodes)
        if (existingCodeNodes.isNotEmpty()) {
            try {
                val composeAnalyzer = ComposeAnalyzer(projectPath)
                val (composeNodes, composeRels) = composeAnalyzer.analyzeComposables(existingCodeNodes)
                allNodes.addAll(composeNodes)
                allRelationships.addAll(composeRels)
                logger.info { "Compose analysis: ${composeNodes.size} nodes, ${composeRels.size} relationships" }
            } catch (e: Exception) {
                logger.warn { "Compose analysis failed: ${e.message}" }
            }
        }
        
        logger.info { "Android enrichment complete: ${allNodes.size} total nodes, ${allRelationships.size} total relationships" }
        
        return Pair(allNodes, allRelationships)
    }
    
    /**
     * Check if this project looks like an Android project.
     */
    fun isAndroidProject(): Boolean {
        val hasManifest = projectPath.resolve("src/main/AndroidManifest.xml").toFile().exists() ||
                          projectPath.resolve("app/src/main/AndroidManifest.xml").toFile().exists()
        
        val hasResDir = projectPath.resolve("src/main/res").toFile().exists() ||
                        projectPath.resolve("app/src/main/res").toFile().exists()
        
        return hasManifest || hasResDir
    }
}
