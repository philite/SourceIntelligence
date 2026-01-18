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
 * Analyzes Kotlin source files for Jetpack Compose patterns.
 * Detects @Composable functions and builds a call hierarchy between them.
 */
class ComposeAnalyzer(private val projectPath: Path) {
    private var nodeIdCounter = 0
    
    private fun nextId(): String = "compose_${nodeIdCounter++}"
    
    // Regex patterns for Compose detection
    private val composableAnnotationPattern = Regex("""@Composable\s+(?:fun|private fun|internal fun|public fun)\s+(\w+)""")
    private val composableFunctionCallPattern = Regex("""(?<!\.)\b([A-Z][a-zA-Z0-9]*)\s*\(""")
    
    /**
     * Analyze Kotlin files for @Composable functions.
     * Returns additional labels/properties to add to existing function nodes
     * and relationships between Composable functions.
     */
    fun analyzeComposables(existingNodes: List<GraphNode>): Pair<List<GraphNode>, List<GraphRelationship>> {
        val composableNodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        // Build lookup of existing functions
        val functionsByName = existingNodes
            .filterIsInstance<FunctionNode>()
            .groupBy { it.name }
        
        // Track all composable function names for call resolution
        val composableFunctions = mutableMapOf<String, String>()  // name -> nodeId
        
        // Find all Kotlin files
        val ktFiles = findKotlinFiles()
        logger.info { "Scanning ${ktFiles.size} files for Composable functions" }
        
        // First pass: identify all Composable functions
        for (ktFile in ktFiles) {
            try {
                val content = ktFile.readText()
                
                composableAnnotationPattern.findAll(content).forEach { match ->
                    val funcName = match.groupValues[1]
                    
                    // Find or create function node
                    val existingFunc = functionsByName[funcName]?.firstOrNull()
                    
                    if (existingFunc != null) {
                        // Add Composable label to existing node
                        composableFunctions[funcName] = existingFunc.id
                        logger.debug { "Found Composable: $funcName" }
                    } else {
                        // Create a new node for this Composable
                        val relativePath = projectPath.relativize(ktFile).pathString
                        val node = FunctionNode(
                            id = nextId(),
                            labels = listOf("Function", "Composable"),
                            properties = mapOf("isComposable" to "true"),
                            name = funcName,
                            fqn = "compose.$funcName",  // Approximate FQN
                            signature = "@Composable fun $funcName()",
                            file = relativePath,
                            line = findLineNumber(content, match.range.first),
                            isAsync = false
                        )
                        composableNodes.add(node)
                        composableFunctions[funcName] = node.id
                        logger.debug { "Created Composable node: $funcName" }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to scan ${ktFile.name} for Composables: ${e.message}" }
            }
        }
        
        logger.info { "Found ${composableFunctions.size} Composable functions" }
        
        // Second pass: find Composable -> Composable calls
        for (ktFile in ktFiles) {
            try {
                val content = ktFile.readText()
                
                // Find which Composable this file defines
                composableAnnotationPattern.findAll(content).forEach { defMatch ->
                    val callerName = defMatch.groupValues[1]
                    val callerId = composableFunctions[callerName] ?: return@forEach
                    
                    // Find the function body (simplified: content after the function definition)
                    val funcStart = defMatch.range.last
                    val funcBody = extractFunctionBody(content, funcStart)
                    
                    // Look for calls to other Composables
                    composableFunctionCallPattern.findAll(funcBody).forEach { callMatch ->
                        val calleeName = callMatch.groupValues[1]
                        val calleeId = composableFunctions[calleeName]
                        
                        if (calleeId != null && callerId != calleeId) {
                            relationships.add(GraphRelationship(
                                id = nextId(),
                                type = RelTypes.CALLS,
                                startNodeId = callerId,
                                endNodeId = calleeId,
                                properties = mapOf("callType" to "compose")
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to analyze Composable calls in ${ktFile.name}: ${e.message}" }
            }
        }
        
        logger.info { "Found ${relationships.size} Composable call relationships" }
        
        return Pair(composableNodes, relationships)
    }
    
    private fun findKotlinFiles(): List<Path> {
        return Files.walk(projectPath)
            .filter { it.name.endsWith(".kt") }
            .filter { !it.pathString.contains("build") }
            .toList()
    }
    
    private fun findLineNumber(content: String, charIndex: Int): Int {
        return content.substring(0, charIndex).count { it == '\n' } + 1
    }
    
    /**
     * Extract approximate function body (content between { and matching }).
     * Simplified: just take content until we hit another fun declaration or EOF.
     */
    private fun extractFunctionBody(content: String, startIndex: Int): String {
        val braceStart = content.indexOf('{', startIndex)
        if (braceStart == -1) return ""
        
        var depth = 1
        var index = braceStart + 1
        
        while (index < content.length && depth > 0) {
            when (content[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        
        return if (depth == 0) content.substring(braceStart + 1, index - 1) else ""
    }
}
