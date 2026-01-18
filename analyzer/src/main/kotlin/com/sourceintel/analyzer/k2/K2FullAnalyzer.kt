package com.sourceintel.analyzer.k2

import com.intellij.openapi.util.Disposer
import com.sourceintel.analyzer.graph.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Full K2 Analysis API analyzer with semantic type resolution.
 * 
 * Uses StandaloneAnalysisAPISession for:
 * - Full FQN resolution for external library types
 * - Type inference analysis  
 * - Resolved call graph extraction
 * - Generic type parameter analysis
 * 
 * Requires classpath JARs to resolve external dependencies.
 */
class K2FullAnalyzer(
    private val projectPath: Path,
    private val classpathConfig: ClasspathConfig = ClasspathConfig.DEFAULT
) {
    private var nodeIdCounter = 0
    private val nodesByFqn = mutableMapOf<String, String>()
    private val pendingExternalTypes = mutableSetOf<String>()
    
    fun analyze(moduleName: String? = null): CodeGraph {
        logger.info { "Starting full K2 semantic analysis of: $projectPath" }
        
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        val disposable = Disposer.newDisposable("K2FullAnalyzer")
        
        try {
            // Resolve classpath
            val classpathResolver = ClasspathResolver(classpathConfig)
            val libraryJars = classpathResolver.resolveClasspath(projectPath)
            val stdlibJars = classpathResolver.getKotlinStdlibJars()
            val allJars = (libraryJars + stdlibJars).distinct().filter { it.exists() }
            
            logger.info { "Resolved ${allJars.size} library JARs for analysis" }
            
            // Find source files
            val sourceFiles = findKotlinFiles(projectPath)
            logger.info { "Found ${sourceFiles.size} Kotlin source files" }
            
            if (sourceFiles.isEmpty()) {
                logger.warn { "No Kotlin source files found" }
                return createEmptyGraph()
            }
            
            // Build K2 analysis session with correct v2.1.21 API
            val session = buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    platform = JvmPlatforms.defaultJvmPlatform
                    
                    // Build library modules for each JAR
                    val libraryModules = allJars.mapIndexed { index, jarPath ->
                        buildKtLibraryModule {
                            addBinaryRoot(jarPath)
                            libraryName = jarPath.fileName.toString().removeSuffix(".jar")
                            platform = JvmPlatforms.defaultJvmPlatform
                        }
                    }
                    
                    // Build source module with library dependencies
                    val sourceModule = buildKtSourceModule {
                        this.moduleName = moduleName ?: "main"
                        platform = JvmPlatforms.defaultJvmPlatform
                        addSourceRoot(projectPath)
                        
                        // Add library dependencies using correct API
                        libraryModules.forEach { lib ->
                            addRegularDependency(lib)
                        }
                    }
                    
                    addModule(sourceModule)
                }
            }
            
            // Analyze each source file from session
            val ktFiles = session.modulesWithFiles.values.flatten()
                .filterIsInstance<KtFile>()
            
            logger.info { "Analyzing ${ktFiles.size} KtFiles from session" }
            
            var processedCount = 0
            val totalFiles = ktFiles.size
            val startTime = System.currentTimeMillis()

            for (ktFile in ktFiles) {
                processedCount++
                if (processedCount % 10 == 0 || processedCount == totalFiles) {
                    val percent = (processedCount * 100) / totalFiles
                    logger.info { "Analyzing $processedCount/$totalFiles ($percent%) - ${ktFile.name}" }
                }
                
                // Synchronous analysis - no async timeout to avoid zombie threads holding K2 locks
                try {
                    val (fileNodes, fileRels) = analyzeFileWithK2(ktFile)
                    nodes.addAll(fileNodes)
                    relationships.addAll(fileRels)
                } catch (e: Exception) {
                    logger.warn { "Failed to analyze ${ktFile.name}: ${e.message}" }
                }
            }
            
            // Create external type placeholder nodes and fix references
            createExternalTypeNodes(nodes)
            fixupPendingReferences(relationships)
            
            logger.info { "K2 analysis complete: ${nodes.size} nodes, ${relationships.size} relationships" }
            
        } catch (e: Exception) {
            logger.error(e) { "K2 session initialization failed: ${e.message}" }
            logger.info { "Falling back to PSI-based analysis" }
            Disposer.dispose(disposable)
            return K2SemanticAnalyzer(projectPath).analyze(moduleName)
        } finally {
            Disposer.dispose(disposable)
        }
        
        return CodeGraph(
            meta = GraphMeta(
                language = "kotlin",
                projectType = detectProjectType(projectPath),
                analyzedAt = Instant.now().toString(),
                projectPath = projectPath.toString()
            ),
            nodes = nodes,
            relationships = relationships
        )
    }
    
    private fun analyzeFileWithK2(
        ktFile: KtFile
    ): Pair<List<GraphNode>, List<GraphRelationship>> {
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()

        val packageName = ktFile.packageFqName.asString()
        val relativePath = try {
            projectPath.relativize(Path.of(ktFile.virtualFilePath)).toString()
        } catch (e: Exception) {
            ktFile.name
        }
        
        // Create file node
        val fileNode = FileNode(
            id = nextId(),
            properties = emptyMap(),
            name = ktFile.name,
            path = relativePath,
            language = "kotlin"
        )
        nodes.add(fileNode)
        
        // Create package node
        if (packageName.isNotEmpty()) {
            val packageKey = "pkg:$packageName"
            val packageNodeId = nodesByFqn[packageKey] ?: run {
                val packageNode = PackageNode(
                    id = nextId(),
                    properties = emptyMap(),
                    name = packageName.substringAfterLast('.'),
                    fqn = packageName
                )
                nodes.add(packageNode)
                nodesByFqn[packageKey] = packageNode.id
                packageNode.id
            }
            
            relationships.add(GraphRelationship(
                id = nextId(),
                type = RelTypes.CONTAINS,
                startNodeId = packageNodeId,
                endNodeId = fileNode.id
            ))
        }
        
        // Use K2 analyze block for semantic analysis
        analyze(ktFile) {
            for (declaration in ktFile.declarations) {
                analyzeDeclarationWithK2(declaration, fileNode, packageName, nodes, relationships)
            }
        }
        
        return nodes to relationships
    }
    
    private fun org.jetbrains.kotlin.analysis.api.KaSession.analyzeDeclarationWithK2(
        declaration: KtDeclaration,
        fileNode: FileNode,
        packageName: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        when (declaration) {
            is KtClassOrObject -> analyzeClassWithK2(declaration, fileNode, packageName, nodes, relationships)
            is KtNamedFunction -> analyzeFunctionWithK2(declaration, fileNode, packageName, nodes, relationships, null)
            is KtProperty -> analyzePropertyWithK2(declaration, fileNode, packageName, nodes, relationships, null)
        }
    }
    
    private fun org.jetbrains.kotlin.analysis.api.KaSession.analyzeClassWithK2(
        ktClass: KtClassOrObject,
        fileNode: FileNode,
        packageName: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        val name = ktClass.name ?: return
        
        // Get class symbol for semantic information (with safe error handling)
        val classSymbol = try {
            ktClass.symbol as? KaClassSymbol
        } catch (e: Exception) {
            logger.debug { "Symbol resolution failed for $name: ${e.message}" }
            null
        } ?: return
         
        // Get FQN from symbol (resolves to actual package)
        val fqn = classSymbol.classId?.asFqNameString() ?: "$packageName.$name"
        
        val kind = when {
            ktClass is KtClass && ktClass.isInterface() -> TypeKind.INTERFACE
            ktClass is KtClass && ktClass.isEnum() -> TypeKind.ENUM
            ktClass is KtClass && ktClass.isAnnotation() -> TypeKind.ANNOTATION
            ktClass is KtObjectDeclaration -> TypeKind.OBJECT
            else -> TypeKind.CLASS
        }
        
        val isData = ktClass is KtClass && ktClass.isData()
        val isSealed = ktClass is KtClass && ktClass.isSealed()
        
        val labels = buildList {
            add("Type")
            when (kind) {
                TypeKind.CLASS -> add("Class")
                TypeKind.INTERFACE -> add("Interface")
                TypeKind.OBJECT -> add("Object")
                TypeKind.ENUM -> add("Enum")
                TypeKind.ANNOTATION -> add("Annotation")
                else -> {}
            }
            if (isData) add("DataClass")
            if (isSealed) add("SealedClass")
            
            // Architecture patterns
            when {
                name.endsWith("ViewModel") -> add("ViewModel")
                name.endsWith("UseCase") -> add("UseCase")
                name.endsWith("Repository") -> add("Repository")
                name.endsWith("DataSource") -> add("DataSource")
                name.endsWith("Activity") -> add("Activity")
                name.endsWith("Fragment") -> add("Fragment")
                name.endsWith("Dao") -> add("RoomDao")
            }
        }
        
        val properties = buildMap<String, String> {
            if (isData) put("isData", "true")
            if (isSealed) put("isSealed", "true")
        }
        
        val typeNode = TypeNode(
            id = nextId(),
            labels = labels,
            properties = properties,
            name = name,
            fqn = fqn,
            kind = kind,
            file = fileNode.path,
            line = ktClass.textOffset
        )
        nodes.add(typeNode)
        nodesByFqn[fqn] = typeNode.id
        
        // Link to file
        relationships.add(GraphRelationship(
            id = nextId(),
            type = RelTypes.DECLARES,
            startNodeId = fileNode.id,
            endNodeId = typeNode.id
        ))
        
        // Extract inheritance with full FQN resolution (with safe error handling)
        for (superTypeEntry in ktClass.superTypeListEntries) {
            val superTypeRef = superTypeEntry.typeReference ?: continue
            val superType = try {
                superTypeRef.type
            } catch (e: Exception) {
                logger.debug { "Type resolution failed for supertype: ${e.message}" }
                continue
            }
            
            val parentFqn = resolveTypeFqn(superType)
            if (parentFqn.isNotEmpty() && parentFqn != "kotlin.Any") {
                val parentNodeId = nodesByFqn[parentFqn] ?: run {
                    pendingExternalTypes.add(parentFqn)
                    "pending:$parentFqn"
                }
                
                val isClassExtension = superTypeEntry is KtSuperTypeCallEntry
                relationships.add(GraphRelationship(
                    id = nextId(),
                    type = if (isClassExtension) RelTypes.EXTENDS else RelTypes.IMPLEMENTS,
                    startNodeId = typeNode.id,
                    endNodeId = parentNodeId,
                    properties = mapOf("parentFqn" to parentFqn)
                ))
            }
        }
        
        // Analyze class members
        for (member in ktClass.declarations) {
            when (member) {
                is KtNamedFunction -> analyzeFunctionWithK2(member, fileNode, fqn, nodes, relationships, typeNode)
                is KtProperty -> analyzePropertyWithK2(member, fileNode, fqn, nodes, relationships, typeNode)
                is KtClassOrObject -> analyzeClassWithK2(member, fileNode, fqn, nodes, relationships)
            }
        }
        
        // Constructor parameters as properties
        if (ktClass !is KtObjectDeclaration) {
            ktClass.primaryConstructorParameters.forEach { param ->
            if (param.hasValOrVar()) {
                val propName = param.name ?: return@forEach
                val propFqn = "$fqn.$propName"
                
                // Get type from K2 (with safe error handling)
                val typeFqn = try {
                    val paramSymbol = param.symbol
                    resolveTypeFqn(paramSymbol.returnType)
                } catch (e: Exception) {
                    logger.debug { "Parameter type resolution failed for $propName: ${e.message}" }
                    param.typeReference?.text ?: "Unknown"
                }
                
                val fieldNode = FieldNode(
                    id = nextId(),
                    labels = listOf("Field", "Property", "ConstructorProperty"),
                    properties = mapOf(
                        "isMutable" to param.isMutable.toString(),
                        "resolvedType" to typeFqn
                    ),
                    name = propName,
                    fqn = propFqn,
                    typeFqn = typeFqn,
                    isMutable = param.isMutable,
                    file = fileNode.path,
                    line = param.textOffset
                )
                nodes.add(fieldNode)
                
                relationships.add(GraphRelationship(
                    id = nextId(),
                    type = RelTypes.DECLARES,
                    startNodeId = typeNode.id,
                    endNodeId = fieldNode.id
                ))
                
                // Add HAS_TYPE relationship
                addTypeRelationship(fieldNode.id, typeFqn, relationships)
            }
        }
        } // End if !is KtObjectDeclaration
    }
    
    private fun org.jetbrains.kotlin.analysis.api.KaSession.analyzeFunctionWithK2(
        function: KtNamedFunction,
        fileNode: FileNode,
        containerFqn: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>,
        parentNode: GraphNode?
    ) {
        val name = function.name ?: return
        val fqn = "$containerFqn.$name"
        
        // Get function symbol for resolved types (with safe error handling)
        val functionSymbol = try {
            function.symbol
        } catch (e: Exception) {
            logger.debug { "Function symbol resolution failed for $name: ${e.message}" }
            null
        }
        
        // Build parameter list with resolved types (fall back to PSI text)
        val params = function.valueParameters.map { param ->
            val paramName = param.name ?: "_"
            val paramType = try {
                val paramSymbol = param.symbol
                resolveTypeFqn(paramSymbol.returnType)
            } catch (e: Exception) {
                param.typeReference?.text ?: "Unknown"
            }
            "$paramName: $paramType"
        }
        
        val returnType = try {
            functionSymbol?.let { resolveTypeFqn(it.returnType) } ?: function.typeReference?.text ?: "Unit"
        } catch (e: Exception) {
            function.typeReference?.text ?: "Unit"
        }
        val signature = "(${params.joinToString(", ")}): $returnType"
        
        val isSuspend = function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)
        val isExtension = function.receiverTypeReference != null
        
        val labels = buildList {
            add("Function")
            if (isSuspend) add("SuspendFunction")
            if (isExtension) add("ExtensionFunction")
        }
        
        val functionNode = FunctionNode(
            id = nextId(),
            labels = labels,
            properties = mapOf(
                "isSuspend" to isSuspend.toString(),
                "isExtension" to isExtension.toString(),
                "resolvedReturnType" to returnType
            ),
            name = name,
            fqn = fqn,
            signature = signature,
            file = fileNode.path,
            line = function.textOffset,
            isAsync = isSuspend
        )
        nodes.add(functionNode)
        nodesByFqn[fqn] = functionNode.id
        
        // Link to parent
        val linkTo = parentNode ?: fileNode
        relationships.add(GraphRelationship(
            id = nextId(),
            type = RelTypes.DECLARES,
            startNodeId = linkTo.id,
            endNodeId = functionNode.id
        ))
        
        // Extract resolved calls from body
        function.bodyExpression?.let { body ->
            extractResolvedCalls(body, functionNode, relationships)
        }
    }
    
    private fun org.jetbrains.kotlin.analysis.api.KaSession.analyzePropertyWithK2(
        property: KtProperty,
        fileNode: FileNode,
        containerFqn: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>,
        parentNode: GraphNode?
    ) {
        val name = property.name ?: return
        val fqn = "$containerFqn.$name"
        
        // Get property symbol for resolved/inferred type (with safe error handling)
        val typeFqn = try {
            val propertySymbol = property.symbol
            resolveTypeFqn(propertySymbol.returnType)
        } catch (e: Exception) {
            logger.debug { "Property type resolution failed for $name: ${e.message}" }
            property.typeReference?.text ?: "Unknown"
        }
        
        val fieldNode = FieldNode(
            id = nextId(),
            labels = listOf("Field", "Property"),
            properties = mapOf(
                "isMutable" to property.isVar.toString(),
                "resolvedType" to typeFqn
            ),
            name = name,
            fqn = fqn,
            typeFqn = typeFqn,
            isMutable = property.isVar,
            file = fileNode.path,
            line = property.textOffset
        )
        nodes.add(fieldNode)
        
        val linkTo = parentNode ?: fileNode
        relationships.add(GraphRelationship(
            id = nextId(),
            type = RelTypes.DECLARES,
            startNodeId = linkTo.id,
            endNodeId = fieldNode.id
        ))
        
        // Add HAS_TYPE relationship
        addTypeRelationship(fieldNode.id, typeFqn, relationships)
    }
    
    /**
     * Resolves a KaType to its FQN string.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.resolveTypeFqn(type: KaType): String {
        return when (type) {
            is KaClassType -> {
                val classId = type.classId
                val baseFqn = classId.asFqNameString()
                
                // Handle type arguments
                val typeArgs = type.typeArguments
                if (typeArgs.isNotEmpty()) {
                    val argsFqn = typeArgs.mapNotNull { arg ->
                        when (arg) {
                            is KaTypeProjection -> resolveTypeFqn(arg.type ?: return@mapNotNull null)
                            else -> null
                        }
                    }
                    if (argsFqn.isNotEmpty()) {
                        "$baseFqn<${argsFqn.joinToString(", ")}>"
                    } else {
                        baseFqn
                    }
                } else {
                    baseFqn
                }
            }
            is KaTypeParameterType -> type.name.asString()
            else -> type.toString()
        }
    }
    
    /**
     * Extracts resolved function calls from an expression.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.extractResolvedCalls(
        expression: KtExpression,
        callerNode: FunctionNode,
        relationships: MutableList<GraphRelationship>
    ) {
        expression.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(callExpr: KtCallExpression) {
                super.visitCallExpression(callExpr)
                
                try {
                    // Resolve call to actual function symbol
                    val callInfo = callExpr.resolveToCall()
                    val functionCall = callInfo?.successfulFunctionCallOrNull()
                    
                    if (functionCall != null) {
                        // Get FQN from signature's callableId to avoid .symbol conflicts
                        val signature = functionCall.partiallyAppliedSymbol.signature
                        val callableId = signature.callableId
                        
                        val calleeFqn = callableId?.asSingleFqName()?.asString()
                            ?: callExpr.calleeExpression?.text
                            ?: "unknown"
                        
                        val targetId = nodesByFqn[calleeFqn] ?: run {
                            pendingExternalTypes.add(calleeFqn)
                            "pending:$calleeFqn"
                        }
                        
                        relationships.add(GraphRelationship(
                            id = nextId(),
                            type = RelTypes.CALLS,
                            startNodeId = callerNode.id,
                            endNodeId = targetId,
                            properties = mapOf("resolvedFqn" to calleeFqn)
                        ))
                    }
                } catch (e: Exception) {
                    // Fall back to unresolved name
                    val calleeName = callExpr.calleeExpression?.text ?: return
                    relationships.add(GraphRelationship(
                        id = nextId(),
                        type = RelTypes.CALLS,
                        startNodeId = callerNode.id,
                        endNodeId = "call:$calleeName",
                        properties = mapOf("calleeName" to calleeName, "resolved" to "false")
                    ))
                }
            }
        })
    }
    
    private fun addTypeRelationship(
        fieldId: String,
        typeFqn: String,
        relationships: MutableList<GraphRelationship>
    ) {
        val baseType = typeFqn.substringBefore('<')
        if (baseType.isNotEmpty() && baseType !in listOf("kotlin.Any", "kotlin.Unit")) {
            val typeNodeId = nodesByFqn[baseType] ?: run {
                pendingExternalTypes.add(baseType)
                "pending:$baseType"
            }
            
            relationships.add(GraphRelationship(
                id = nextId(),
                type = RelTypes.HAS_TYPE,
                startNodeId = fieldId,
                endNodeId = typeNodeId,
                properties = mapOf("typeFqn" to typeFqn)
            ))
        }
    }
    
    private fun createExternalTypeNodes(nodes: MutableList<GraphNode>) {
        for (externalFqn in pendingExternalTypes) {
            if (externalFqn !in nodesByFqn && !externalFqn.startsWith("call:")) {
                val name = externalFqn.substringAfterLast('.')
                val typeNode = TypeNode(
                    id = nextId(),
                    labels = listOf("Type", "Class", "ExternalType"),
                    properties = mapOf("isExternal" to "true"),
                    name = name,
                    fqn = externalFqn,
                    kind = TypeKind.CLASS,
                    file = "",
                    line = 0
                )
                nodes.add(typeNode)
                nodesByFqn[externalFqn] = typeNode.id
            }
        }
        logger.info { "Created ${pendingExternalTypes.size} external type nodes" }
    }
    
    private fun fixupPendingReferences(relationships: MutableList<GraphRelationship>) {
        val fixed = relationships.map { rel ->
            var startId = rel.startNodeId
            var endId = rel.endNodeId
            
            if (startId.startsWith("pending:")) {
                val fqn = startId.removePrefix("pending:")
                startId = nodesByFqn[fqn] ?: startId
            }
            if (endId.startsWith("pending:")) {
                val fqn = endId.removePrefix("pending:")
                endId = nodesByFqn[fqn] ?: endId
            }
            
            rel.copy(startNodeId = startId, endNodeId = endId)
        }
        relationships.clear()
        relationships.addAll(fixed)
    }
    
    private fun findKotlinFiles(root: Path): List<Path> {
        return root.walk()
            .filter { it.name.endsWith(".kt") || it.name.endsWith(".kts") }
            .filter { !it.toString().contains("build") }
            .filter { !it.toString().contains(".gradle") }
            .toList()
    }
    
    private fun detectProjectType(path: Path): String {
        val hasAndroidManifest = path.resolve("app/src/main/AndroidManifest.xml").exists() ||
                path.walk().any { it.name == "AndroidManifest.xml" }
        val hasBuildGradle = path.resolve("build.gradle.kts").exists() ||
                path.resolve("build.gradle").exists()
        
        return when {
            hasAndroidManifest -> "android"
            hasBuildGradle -> "kotlin-jvm"
            else -> "kotlin"
        }
    }
    
    private fun createEmptyGraph(): CodeGraph {
        return CodeGraph(
            meta = GraphMeta(
                language = "kotlin",
                projectType = "unknown",
                analyzedAt = Instant.now().toString(),
                projectPath = projectPath.toString()
            ),
            nodes = emptyList(),
            relationships = emptyList()
        )
    }
    
    private fun nextId(): String = "node_${nodeIdCounter++}"
}
