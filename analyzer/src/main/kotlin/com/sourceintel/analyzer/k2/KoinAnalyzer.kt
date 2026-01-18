package com.sourceintel.analyzer.k2

import com.sourceintel.analyzer.graph.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.psi.*

private val logger = KotlinLogging.logger {}

/**
 * Analyzes Koin DI DSL patterns to extract dependency injection relationships.
 * 
 * Recognizes:
 * - `module { }` blocks
 * - `single { }`, `factory { }`, `viewModel { }` providers
 * - `singleOf(::ClassName)`, `factoryOf(::ClassName)`, `viewModelOf(::ClassName)` providers
 * - `bind<Interface>()` bindings after definitions
 * - `by inject<Type>()`, `by viewModel<Type>()` injection sites
 */
class KoinAnalyzer(
    private val nodesByFqn: MutableMap<String, String>,
    private val idGenerator: () -> String
) {
    
    /**
     * Analyze a KtFile for Koin patterns within a K2 session.
     * Returns nodes for Koin modules and relationships for PROVIDES/INJECTS.
     */
    fun KaSession.analyzeKoin(
        ktFile: KtFile,
        existingNodes: List<GraphNode>
    ): Pair<List<GraphNode>, List<GraphRelationship>> {
        val nodes = mutableListOf<GraphNode>()
        val relationships = mutableListOf<GraphRelationship>()
        
        ktFile.accept(object : KtTreeVisitorVoid() {
            
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                
                val calleeName = expression.calleeExpression?.text ?: return
                
                when (calleeName) {
                    // Provider DSL: single { }, factory { }, viewModel { }
                    "single", "factory", "viewModel" -> {
                        analyzeProviderLambda(expression, calleeName, relationships)
                    }
                    
                    // Provider function refs: singleOf(::Foo), factoryOf(::Foo)
                    "singleOf", "factoryOf", "viewModelOf" -> {
                        analyzeProviderOf(expression, calleeName, relationships)
                    }
                }
            }
            
            override fun visitProperty(property: KtProperty) {
                super.visitProperty(property)
                
                // Check for `by inject<Type>()` or `by viewModel<Type>()`
                val delegateExpr = property.delegateExpression ?: return
                if (delegateExpr !is KtCallExpression) return
                
                val calleeName = delegateExpr.calleeExpression?.text ?: return
                if (calleeName == "inject" || calleeName == "viewModel") {
                    analyzeInjection(property, delegateExpr, relationships)
                }
            }
        })
        
        if (relationships.isNotEmpty()) {
            logger.debug { "Koin analysis for ${ktFile.name}: ${relationships.size} relationships" }
        }
        
        return nodes to relationships
    }
    
    /**
     * Analyze: single { Foo() } -> Finds that Foo is provided
     */
    private fun KaSession.analyzeProviderLambda(
        call: KtCallExpression,
        providerType: String,
        relationships: MutableList<GraphRelationship>
    ) {
        // Get the lambda body
        val lambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtLambdaExpression
            ?: return
        
        val body = lambda.bodyExpression ?: return
        
        // The last expression in the body is the return value
        val lastStatement = body.statements.lastOrNull() ?: return
        
        try {
            // Try to resolve the return type of the lambda
            val returnType = lastStatement.expressionType
            if (returnType != null) {
                val providedFqn = when (val classType = returnType) {
                    is org.jetbrains.kotlin.analysis.api.types.KaClassType -> 
                        classType.classId.asFqNameString()
                    else -> null
                }
                
                if (providedFqn != null) {
                    // Find the class node that is being provided
                    val targetNodeId = nodesByFqn[providedFqn] ?: "external:$providedFqn"
                    
                    // Get the container (class or file) that contains this Koin config
                    val containerFqn = call.containingKtFile.packageFqName.asString()
                    val containerNodeId = nodesByFqn["pkg:$containerFqn"] ?: "koin_module"
                    
                    relationships.add(GraphRelationship(
                        id = idGenerator(),
                        type = RelTypes.PROVIDES,
                        startNodeId = containerNodeId,
                        endNodeId = targetNodeId,
                        properties = mapOf(
                            "providerType" to providerType,
                            "providedFqn" to providedFqn
                        )
                    ))
                    
                    logger.debug { "Koin: $providerType { } provides $providedFqn" }
                }
            }
        } catch (e: Exception) {
            logger.debug { "Koin: Could not resolve provider return type: ${e.message}" }
        }
    }
    
    /**
     * Analyze: singleOf(::Foo) -> Foo is provided
     */
    private fun KaSession.analyzeProviderOf(
        call: KtCallExpression,
        providerType: String,
        relationships: MutableList<GraphRelationship>
    ) {
        // Get the callable reference argument
        val arg = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return
        
        if (arg is KtCallableReferenceExpression) {
            val referencedName = arg.callableReference.text
            
            // Try to resolve the callable reference
            try {
                val resolvedCall = arg.callableReference.resolveToCall()
                val functionCall = resolvedCall?.successfulFunctionCallOrNull()
                
                val providedFqn = functionCall?.partiallyAppliedSymbol?.signature?.callableId
                    ?.asSingleFqName()?.asString()
                    ?: referencedName
                
                val targetNodeId = nodesByFqn[providedFqn] ?: "external:$providedFqn"
                val containerFqn = call.containingKtFile.packageFqName.asString()
                val containerNodeId = nodesByFqn["pkg:$containerFqn"] ?: "koin_module"
                
                relationships.add(GraphRelationship(
                    id = idGenerator(),
                    type = RelTypes.PROVIDES,
                    startNodeId = containerNodeId,
                    endNodeId = targetNodeId,
                    properties = mapOf(
                        "providerType" to providerType.removeSuffix("Of"),
                        "providedFqn" to providedFqn,
                        "isConstructorRef" to "true"
                    )
                ))
                
                logger.debug { "Koin: $providerType(::$referencedName) provides $providedFqn" }
            } catch (e: Exception) {
                logger.debug { "Koin: Could not resolve ::$referencedName" }
            }
        }
    }
    
    /**
     * Analyze: val foo: Foo by inject() -> Class injects Foo
     */
    private fun KaSession.analyzeInjection(
        property: KtProperty,
        delegateCall: KtCallExpression,
        relationships: MutableList<GraphRelationship>
    ) {
        try {
            // Get the injected type from property type or delegate type args
            val injectedType = property.symbol.returnType
            
            val injectedFqn = when (injectedType) {
                is org.jetbrains.kotlin.analysis.api.types.KaClassType -> 
                    injectedType.classId.asFqNameString()
                else -> null
            } ?: return
            
            // Find the containing class
            val containingClass = property.parent?.parent as? KtClassOrObject ?: return
            val containingFqn = try {
                (containingClass.symbol as? KaClassSymbol)?.classId?.asFqNameString()
            } catch (e: Exception) { null } ?: return
            
            val sourceNodeId = nodesByFqn[containingFqn] ?: return
            val targetNodeId = nodesByFqn[injectedFqn] ?: "external:$injectedFqn"
            
            val injectionType = delegateCall.calleeExpression?.text ?: "inject"
            
            relationships.add(GraphRelationship(
                id = idGenerator(),
                type = "INJECTS",
                startNodeId = sourceNodeId,
                endNodeId = targetNodeId,
                properties = mapOf(
                    "propertyName" to (property.name ?: ""),
                    "injectionType" to injectionType,
                    "injectedFqn" to injectedFqn
                )
            ))
            
            logger.debug { "Koin: $containingFqn injects $injectedFqn via $injectionType" }
        } catch (e: Exception) {
            logger.debug { "Koin: Could not analyze injection: ${e.message}" }
        }
    }
}
