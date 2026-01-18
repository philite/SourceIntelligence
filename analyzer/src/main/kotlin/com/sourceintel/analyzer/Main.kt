package com.sourceintel.analyzer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.sourceintel.analyzer.k2.ClasspathConfig
import com.sourceintel.analyzer.k2.K2Analyzer
import com.sourceintel.analyzer.k2.K2FullAnalyzer
import com.sourceintel.analyzer.k2.K2SemanticAnalyzer
import com.sourceintel.analyzer.k2.KotlinVersionDetector
import com.sourceintel.analyzer.android.AndroidEnricher
import com.sourceintel.analyzer.graph.CodeGraph
import com.sourceintel.analyzer.output.GraphJsonWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

class AnalyzerCli : CliktCommand(name = "analyzer") {
    override fun run() = Unit
}

class AnalyzeCommand : CliktCommand(name = "analyze") {
    private val projectPath by argument()
        .path(mustExist = true, canBeFile = false)
    
    private val output by option("-o", "--output")
        .path()
    
    private val moduleName by option("-m", "--module")
    
    private val semantic by option("-s", "--semantic", help = "Use PSI semantic analysis (faster)")
        .flag(default = false)
    
    private val fullK2 by option("--full-k2", help = "Use full K2 FIR analysis with resolved types (slower but most accurate)")
        .flag(default = false)
    
    private val android by option("--android", help = "Enable Android enrichment (parse layouts, navigation, manifests)")
        .flag(default = false)
    
    private val excludeGradleCache by option("--exclude-gradle-cache", help = "Skip Gradle cache JARs (~/.gradle/caches)")
        .flag(default = false)
    
    private val excludeMavenLocal by option("--exclude-maven-local", help = "Skip Maven local JARs (~/.m2/repository)")
        .flag(default = false)
    
    private val excludeBuildDir by option("--exclude-build-dir", help = "Skip project build directory JARs")
        .flag(default = false)
    
    private val excludeLib by option("--exclude-lib", help = "Exclude libraries matching glob pattern (repeatable)")
        .multiple()
    
    override fun run() {
        logger.info { "Analyzing project: $projectPath (fullK2=$fullK2, semantic=$semantic, android=$android)" }
        
        if (!projectPath.exists()) {
            logger.error { "Project path does not exist: $projectPath" }
            return
        }
        
        var graph = when {
            fullK2 -> {
                val config = ClasspathConfig.fromFlags(
                    excludeGradleCache = excludeGradleCache,
                    excludeMavenLocal = excludeMavenLocal,
                    excludeBuildDir = excludeBuildDir,
                    excludeLibPatterns = excludeLib
                )
                logger.info { "Classpath config: gradle=${!excludeGradleCache}, maven=${!excludeMavenLocal}, build=${!excludeBuildDir}, excludes=${excludeLib}" }
                
                // Detect Kotlin version to choose appropriate analyzer
                val kotlinVersion = KotlinVersionDetector.detectVersion(projectPath)
                
                if (kotlinVersion == null || kotlinVersion.isK2Compatible()) {
                    logger.info { "Using full K2 FIR analysis (Kotlin ${kotlinVersion ?: "unknown, assuming 2.0+"})" }
                    K2FullAnalyzer(projectPath, config).analyze(moduleName)
                } else {
                    logger.info { "Project uses Kotlin $kotlinVersion (< 2.0) - using PSI semantic analysis instead of K2 FIR" }
                    K2SemanticAnalyzer(projectPath).analyze(moduleName)
                }
            }
            semantic -> {
                logger.info { "Using PSI semantic analysis" }
                K2SemanticAnalyzer(projectPath).analyze(moduleName)
            }
            else -> {
                logger.info { "Using regex-based analysis" }
                K2Analyzer(projectPath).analyze(moduleName)
            }
        }
        
        // Android enrichment
        if (android) {
            val enricher = AndroidEnricher(projectPath)
            if (enricher.isAndroidProject()) {
                logger.info { "Running Android enrichment..." }
                val (androidNodes, androidRels) = enricher.enrich(graph.nodes)
                
                // Merge Android data into graph
                graph = CodeGraph(
                    meta = graph.meta.copy(projectType = "android"),
                    nodes = graph.nodes + androidNodes,
                    relationships = graph.relationships + androidRels
                )
                logger.info { "Android enrichment added ${androidNodes.size} nodes and ${androidRels.size} relationships" }
            } else {
                logger.warn { "Android flag set but project doesn't appear to be an Android project" }
            }
        }
        
        val writer = GraphJsonWriter()
        val json = writer.toJson(graph)
        
        if (output != null) {
            output!!.toFile().writeText(json)
            logger.info { "Graph written to: $output" }
        } else {
            println(json)
        }
    }
}

fun main(args: Array<String>) {
    AnalyzerCli()
        .subcommands(AnalyzeCommand())
        .main(args)
}

