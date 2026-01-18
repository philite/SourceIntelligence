package com.sourceintel.analyzer.grpc

import com.sourceintel.analyzer.android.AndroidEnricher
import com.sourceintel.analyzer.graph.FieldNode
import com.sourceintel.analyzer.graph.FileNode
import com.sourceintel.analyzer.graph.FunctionNode
import com.sourceintel.analyzer.graph.LayoutNode
import com.sourceintel.analyzer.graph.ModuleNode
import com.sourceintel.analyzer.graph.NavDestinationNode
import com.sourceintel.analyzer.graph.PackageNode
import com.sourceintel.analyzer.graph.TypeNode
import com.sourceintel.analyzer.graph.ViewNode
import com.sourceintel.analyzer.graph.GraphNode as ModelGraphNode
import com.sourceintel.analyzer.graph.GraphRelationship as ModelGraphRelationship
import com.sourceintel.analyzer.k2.*
import com.sourceintel.proto.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

/**
 * gRPC server for streaming code analysis.
 * Runs as a long-lived process to avoid JVM cold-start penalties.
 */
class AnalyzerServer(private val port: Int = 50051) {
    private val server: Server = ServerBuilder
        .forPort(port)
        .addService(AnalyzerServiceImpl())
        .build()

    fun start() {
        server.start()
        logger.info { "Analyzer gRPC server started on port $port" }
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Shutting down gRPC server..." }
            stop()
        })
        server.awaitTermination()
    }

    fun stop() {
        server.shutdown()
    }
}

/**
 * Implementation of the AnalyzerService gRPC interface.
 * Streams nodes and relationships as they are discovered.
 */
class AnalyzerServiceImpl : AnalyzerServiceGrpcKt.AnalyzerServiceCoroutineImplBase() {
    
    override fun analyze(request: AnalyzeRequest): Flow<AnalysisEvent> = flow {
        val projectPath = Path(request.projectPath)
        val startTime = System.currentTimeMillis()
        var nodeCount = 0
        var relCount = 0
        
        logger.info { "Starting analysis: ${request.projectPath} (fullK2=${request.fullK2}, android=${request.android})" }
        
        if (!projectPath.exists()) {
            emit(AnalysisEvent.newBuilder().apply {
                error = AnalysisError.newBuilder().apply {
                    message = "Project path does not exist: $projectPath"
                    fatal = true
                }.build()
            }.build())
            return@flow
        }
        
        // Emit metadata first
        emit(AnalysisEvent.newBuilder().apply {
            meta = GraphMeta.newBuilder().apply {
                language = "kotlin"
                projectType = if (request.android) "android" else "jvm"
                schemaVersion = "1.0"
                analyzedAt = Instant.now().toString()
                this.projectPath = request.projectPath
            }.build()
        }.build())
        
        try {
            // Build classpath config from request
            val classpathConfig = ClasspathConfig.fromFlags(
                excludeGradleCache = request.classpathConfig?.excludeGradleCache ?: false,
                excludeMavenLocal = request.classpathConfig?.excludeMavenLocal ?: false,
                excludeBuildDir = request.classpathConfig?.excludeBuildDir ?: false,
                excludeLibPatterns = request.classpathConfig?.excludeLibPatternsList ?: emptyList()
            )
            
            // Parse sharding
            val (shardIndex, totalShards) = parseShard(request.shard)
            
            // Create analyzer based on options
            val graph = when {
                request.fullK2 -> {
                    val kotlinVersion = KotlinVersionDetector.detectVersion(projectPath)
                    if (kotlinVersion == null || kotlinVersion.isK2Compatible()) {
                        logger.info { "Using full K2 FIR analysis" }
                        K2FullAnalyzer(projectPath, classpathConfig, shardIndex, totalShards)
                            .analyze(request.moduleName.takeIf { it.isNotBlank() })
                    } else {
                        logger.info { "Falling back to PSI semantic analysis (Kotlin $kotlinVersion)" }
                        K2SemanticAnalyzer(projectPath)
                            .analyze(request.moduleName.takeIf { it.isNotBlank() })
                    }
                }
                else -> {
                    logger.info { "Using PSI semantic analysis" }
                    K2SemanticAnalyzer(projectPath)
                        .analyze(request.moduleName.takeIf { it.isNotBlank() })
                }
            }
            
            // Stream nodes
            for (node in graph.nodes) {
                emit(AnalysisEvent.newBuilder().apply {
                    this.node = node.toProtoNode()
                }.build())
                nodeCount++
                
                // Emit progress every 100 nodes
                if (nodeCount % 100 == 0) {
                    emit(AnalysisEvent.newBuilder().apply {
                        progress = ProgressUpdate.newBuilder().apply {
                            filesProcessed = nodeCount
                            totalFiles = graph.nodes.size
                            currentFile = "Streaming nodes..."
                        }.build()
                    }.build())
                }
            }
            
            // Android enrichment if requested
            if (request.android) {
                val enricher = AndroidEnricher(projectPath)
                if (enricher.isAndroidProject()) {
                    logger.info { "Running Android enrichment..." }
                    val (androidNodes, androidRels) = enricher.enrich(graph.nodes)
                    
                    for (node in androidNodes) {
                        emit(AnalysisEvent.newBuilder().apply {
                            this.node = node.toProtoNode()
                        }.build())
                        nodeCount++
                    }
                    
                    for (rel in androidRels) {
                        emit(AnalysisEvent.newBuilder().apply {
                            relationship = rel.toProtoRelationship()
                        }.build())
                        relCount++
                    }
                }
            }
            
            // Stream relationships
            for (rel in graph.relationships) {
                emit(AnalysisEvent.newBuilder().apply {
                    relationship = rel.toProtoRelationship()
                }.build())
                relCount++
            }
            
            // Emit completion
            val durationMs = System.currentTimeMillis() - startTime
            emit(AnalysisEvent.newBuilder().apply {
                complete = AnalysisComplete.newBuilder().apply {
                    totalNodes = nodeCount
                    totalRelationships = relCount
                    this.durationMs = durationMs
                }.build()
            }.build())
            
            logger.info { "Analysis complete: $nodeCount nodes, $relCount relationships in ${durationMs}ms" }
            
        } catch (e: Exception) {
            logger.error(e) { "Analysis failed" }
            emit(AnalysisEvent.newBuilder().apply {
                error = AnalysisError.newBuilder().apply {
                    message = e.message ?: "Unknown error"
                    fatal = true
                }.build()
            }.build())
        }
    }
    
    private fun parseShard(shardSpec: String?): Pair<Int, Int> {
        if (shardSpec.isNullOrBlank()) return 0 to 1
        
        val parts = shardSpec.split("/")
        if (parts.size != 2) return 0 to 1
        
        val index = parts[0].toIntOrNull() ?: return 0 to 1
        val total = parts[1].toIntOrNull() ?: return 0 to 1
        
        if (index < 0 || total < 1 || index >= total) return 0 to 1
        
        return index to total
    }
}

/**
 * Extension to convert ModelGraphNode to proto GraphNode
 */
private fun ModelGraphNode.toProtoNode(): GraphNode {
    val props = mutableMapOf<String, String>()
    props.putAll(properties)
    
    // Add common fields as properties
    when (this) {
        is TypeNode -> {
            props["name"] = name
            props["fqn"] = fqn
            props["kind"] = kind.name
            props["file"] = file
            props["line"] = line.toString()
        }
        is FunctionNode -> {
            props["name"] = name
            props["fqn"] = fqn
            props["signature"] = signature
            props["file"] = file
            props["line"] = line.toString()
            if (isAsync) props["isAsync"] = "true"
        }
        is FieldNode -> {
            props["name"] = name
            props["fqn"] = fqn
            props["typeFqn"] = typeFqn
            props["file"] = file
            props["line"] = line.toString()
            if (isMutable) props["isMutable"] = "true"
        }
        is ModuleNode -> {
            props["name"] = name
            props["path"] = path
            props["moduleType"] = moduleType
        }
        is PackageNode -> {
            props["name"] = name
            props["fqn"] = fqn
        }
        is FileNode -> {
            props["name"] = name
            props["path"] = path
            props["language"] = language
        }
        is LayoutNode -> {
            props["name"] = name
            props["file"] = file
        }
        is ViewNode -> {
            props["viewId"] = viewId
            props["viewType"] = viewType
            text?.let { props["text"] = it }
            props["file"] = file
            props["line"] = line.toString()
        }
        is NavDestinationNode -> {
            props["navId"] = navId
            label?.let { props["label"] = it }
            props["className"] = className
            props["navGraphFile"] = navGraphFile
        }
    }
    
    return GraphNode.newBuilder().apply {
        this.id = this@toProtoNode.id
        this.type = this@toProtoNode::class.simpleName ?: "Unknown"
        addAllLabels(this@toProtoNode.labels)
        putAllProperties(props)
    }.build()
}

/**
 * Extension to convert ModelGraphRelationship to proto
 */
private fun ModelGraphRelationship.toProtoRelationship(): com.sourceintel.proto.GraphRelationship {
    return com.sourceintel.proto.GraphRelationship.newBuilder().apply {
        this.id = this@toProtoRelationship.id
        this.type = this@toProtoRelationship.type
        this.startNodeId = this@toProtoRelationship.startNodeId
        this.endNodeId = this@toProtoRelationship.endNodeId
        putAllProperties(this@toProtoRelationship.properties)
    }.build()
}
