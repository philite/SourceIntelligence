package com.sourceintel.analyzer.grpc

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * CLI command to start the gRPC analyzer server.
 * Usage: analyzer-server --port 50051
 */
class ServerCommand : CliktCommand(name = "analyzer-server") {
    private val port by option("-p", "--port", help = "gRPC server port")
        .int()
        .default(50051)
    
    override fun run() {
        logger.info { "Starting AnalyzerServer on port $port" }
        AnalyzerServer(port).start()
    }
}

fun main(args: Array<String>) {
    ServerCommand().main(args)
}
