package com.sourceintel.analyzer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.sourceintel.analyzer.k2.K2Analyzer
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
    
    private val semantic by option("-s", "--semantic", help = "Use K2 semantic analysis (slower but more accurate)")
        .flag(default = false)
    
    override fun run() {
        logger.info { "Analyzing project: $projectPath (semantic=$semantic)" }
        
        if (!projectPath.exists()) {
            logger.error { "Project path does not exist: $projectPath" }
            return
        }
        
        val graph = if (semantic) {
            logger.info { "Using K2 FIR semantic analysis" }
            K2SemanticAnalyzer(projectPath).analyze(moduleName)
        } else {
            logger.info { "Using regex-based analysis" }
            K2Analyzer(projectPath).analyze(moduleName)
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

