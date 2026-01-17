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
    
    private val excludeGradleCache by option("--exclude-gradle-cache", help = "Skip Gradle cache JARs (~/.gradle/caches)")
        .flag(default = false)
    
    private val excludeMavenLocal by option("--exclude-maven-local", help = "Skip Maven local JARs (~/.m2/repository)")
        .flag(default = false)
    
    private val excludeBuildDir by option("--exclude-build-dir", help = "Skip project build directory JARs")
        .flag(default = false)
    
    private val excludeLib by option("--exclude-lib", help = "Exclude libraries matching glob pattern (repeatable)")
        .multiple()
    
    override fun run() {
        logger.info { "Analyzing project: $projectPath (fullK2=$fullK2, semantic=$semantic)" }
        
        if (!projectPath.exists()) {
            logger.error { "Project path does not exist: $projectPath" }
            return
        }
        
        val graph = when {
            fullK2 -> {
                logger.info { "Using full K2 FIR analysis with resolved types" }
                val config = ClasspathConfig.fromFlags(
                    excludeGradleCache = excludeGradleCache,
                    excludeMavenLocal = excludeMavenLocal,
                    excludeBuildDir = excludeBuildDir,
                    excludeLibPatterns = excludeLib
                )
                logger.info { "Classpath config: gradle=${!excludeGradleCache}, maven=${!excludeMavenLocal}, build=${!excludeBuildDir}, excludes=${excludeLib}" }
                K2FullAnalyzer(projectPath, config).analyze(moduleName)
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

