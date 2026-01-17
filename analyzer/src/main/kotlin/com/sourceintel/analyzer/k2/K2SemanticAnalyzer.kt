package com.sourceintel.analyzer.k2

import com.sourceintel.analyzer.graph.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.walk

private val logger = KotlinLogging.logger {}

/**
 * PSI-enhanced Kotlin analyzer.
 * 
 * Uses Kotlin PSI (Program Structure Interface) for more accurate parsing than regex:
 * - Proper class hierarchy detection
 * - Function signature extraction
 * - Property type extraction
 * - Call relationship extraction from AST
 * 
 * This is a simpler alternative to full K2 FIR analysis that still provides
 * much better accuracy than regex-based parsing.
 */
class K2SemanticAnalyzer(
    private val projectPath: Path
) {
    private var nodeIdCounter = 0
    private val nodesByFqn = mutableMapOf<String, String>()
    private lateinit var environment: KotlinCoreEnvironment

    fun analyze(moduleName: String? = null): CodeGraph {
        logger.info { "Starting PSI-enhanced analysis of: $projectPath" }
        
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        try {
            // Initialize Kotlin compiler environment for PSI parsing
            val configuration = CompilerConfiguration()
            configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            
            environment = KotlinCoreEnvironment.createForProduction(
                { },
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            
            val psiFactory = KtPsiFactory(environment.project)
            
            // Find and parse all Kotlin files
            val sourceFiles = findKotlinFiles(projectPath)
            logger.info { "Found ${sourceFiles.size} Kotlin files" }
            
            for (filePath in sourceFiles) {
                try {
                    val content = filePath.toFile().readText()
                    val ktFile = psiFactory.createFile(filePath.name, content)
                    analyzeFile(ktFile, filePath.toString(), nodes, relationships)
                } catch (e: Exception) {
                    logger.warn { "Failed to analyze file $filePath: ${e.message}" }
                }
            }
            
            logger.info { "PSI analysis complete: ${nodes.size} nodes, ${relationships.size} relationships" }
            
        } catch (e: Exception) {
            logger.error(e) { "PSI environment initialization failed, falling back to regex-based analysis" }
            return K2Analyzer(projectPath).analyze(moduleName)
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
    
    private fun analyzeFile(
        ktFile: KtFile,
        filePath: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        val packageName = ktFile.packageFqName.asString()
        val relativePath = projectPath.relativize(Path.of(filePath)).toString()
        
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
            val packageNode = PackageNode(
                id = nextId(),
                properties = emptyMap(),
                name = packageName.substringAfterLast('.'),
                fqn = packageName
            )
            nodes.add(packageNode)
            relationships.add(GraphRelationship(
                id = nextId(),
                type = RelTypes.CONTAINS,
                startNodeId = packageNode.id,
                endNodeId = fileNode.id
            ))
        }
        
        // Analyze all declarations
        for (declaration in ktFile.declarations) {
            analyzeDeclaration(declaration, fileNode, packageName, nodes, relationships)
        }
    }
    
    private fun analyzeDeclaration(
        declaration: KtDeclaration,
        fileNode: FileNode,
        packageName: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        when (declaration) {
            is KtClassOrObject -> analyzeClass(declaration, fileNode, packageName, nodes, relationships)
            is KtNamedFunction -> analyzeFunction(declaration, fileNode, packageName, nodes, relationships, null)
            is KtProperty -> analyzeProperty(declaration, fileNode, packageName, nodes, relationships, null)
        }
    }
    
    private fun analyzeClass(
        ktClass: KtClassOrObject,
        fileNode: FileNode,
        packageName: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>
    ) {
        val name = ktClass.name ?: return
        val fqn = if (packageName.isNotEmpty()) "$packageName.$name" else name
        
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
            // Architecture pattern detection
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
        
        // Extract inheritance from supertype list
        ktClass.superTypeListEntries.forEach { superType ->
            val parentRef = superType.typeReference?.text?.substringBefore('<')?.trim()
            if (parentRef != null && parentRef.isNotEmpty()) {
                // Determine if this is a class extension (has constructor call) or interface implementation
                val isClassExtension = superType is KtSuperTypeCallEntry
                relationships.add(GraphRelationship(
                    id = nextId(),
                    type = if (isClassExtension) RelTypes.EXTENDS else RelTypes.IMPLEMENTS,
                    startNodeId = typeNode.id,
                    endNodeId = "unresolved:$parentRef",
                    properties = mapOf("parentName" to parentRef)
                ))
            }
        }
        
        // Analyze class members
        ktClass.declarations.forEach { member ->
            when (member) {
                is KtNamedFunction -> analyzeFunction(member, fileNode, fqn, nodes, relationships, typeNode)
                is KtProperty -> analyzeProperty(member, fileNode, fqn, nodes, relationships, typeNode)
                is KtClassOrObject -> analyzeClass(member, fileNode, fqn, nodes, relationships) // Nested class
            }
        }
        
        // Analyze primary constructor properties
        ktClass.primaryConstructorParameters.forEach { param ->
            if (param.hasValOrVar()) {
                val propName = param.name ?: return@forEach
                val propFqn = "$fqn.$propName"
                val typeFqn = param.typeReference?.text ?: "Any"
                
                val fieldNode = FieldNode(
                    id = nextId(),
                    labels = listOf("Field", "Property", "ConstructorProperty"),
                    properties = mapOf("isMutable" to param.isMutable.toString()),
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
            }
        }
    }
    
    private fun analyzeFunction(
        function: KtNamedFunction,
        fileNode: FileNode,
        containerFqn: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>,
        parentNode: GraphNode?
    ) {
        val name = function.name ?: return
        val fqn = "$containerFqn.$name"
        
        // Build parameter list
        val params = function.valueParameters.map { param ->
            val paramName = param.name ?: "_"
            val paramType = param.typeReference?.text ?: "Any"
            "$paramName: $paramType"
        }
        
        val returnType = function.typeReference?.text ?: "Unit"
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
                "isExtension" to isExtension.toString()
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
        
        // Extract function calls from body
        function.bodyExpression?.let { body ->
            extractCalls(body, functionNode, relationships)
        }
    }
    
    private fun analyzeProperty(
        property: KtProperty,
        fileNode: FileNode,
        containerFqn: String,
        nodes: MutableList<GraphNode>,
        relationships: MutableList<GraphRelationship>,
        parentNode: GraphNode?
    ) {
        val name = property.name ?: return
        val fqn = "$containerFqn.$name"
        val typeFqn = property.typeReference?.text ?: "Any"
        
        val fieldNode = FieldNode(
            id = nextId(),
            labels = listOf("Field", "Property"),
            properties = mapOf("isMutable" to property.isVar.toString()),
            name = name,
            fqn = fqn,
            typeFqn = typeFqn,
            isMutable = property.isVar,
            file = fileNode.path,
            line = property.textOffset
        )
        nodes.add(fieldNode)
        
        // Link to parent
        val linkTo = parentNode ?: fileNode
        relationships.add(GraphRelationship(
            id = nextId(),
            type = RelTypes.DECLARES,
            startNodeId = linkTo.id,
            endNodeId = fieldNode.id
        ))
    }
    
    private fun extractCalls(
        expression: KtExpression,
        callerNode: FunctionNode,
        relationships: MutableList<GraphRelationship>
    ) {
        expression.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                
                val calleeRef = expression.calleeExpression
                val calleeName = when (calleeRef) {
                    is KtNameReferenceExpression -> calleeRef.getReferencedName()
                    is KtDotQualifiedExpression -> calleeRef.selectorExpression?.text
                    else -> calleeRef?.text
                }
                
                if (calleeName != null && calleeName.isNotEmpty()) {
                    relationships.add(GraphRelationship(
                        id = nextId(),
                        type = RelTypes.CALLS,
                        startNodeId = callerNode.id,
                        endNodeId = "unresolved:$calleeName",
                        properties = mapOf("calleeName" to calleeName)
                    ))
                }
            }
        })
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
    
    private fun nextId(): String = "node_${nodeIdCounter++}"
}
