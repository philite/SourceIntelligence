package com.sourceintel.analyzer.output

import com.sourceintel.analyzer.graph.CodeGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes graph data to JSON format for consumption by Go CLI.
 */
class GraphJsonWriter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    
    fun toJson(graph: CodeGraph): String {
        return json.encodeToString(graph)
    }
}
