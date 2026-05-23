package com.itangcent.easyapi.psi.helper

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.itangcent.easyapi.exporter.model.ApiHeader
import com.itangcent.easyapi.exporter.model.ApiParameter
import com.itangcent.easyapi.exporter.model.ParameterType
import com.itangcent.easyapi.exporter.model.PathSelector
import com.itangcent.easyapi.psi.type.JsonType
import com.itangcent.easyapi.rule.RuleKeys
import com.itangcent.easyapi.rule.engine.RuleEngine
import com.itangcent.easyapi.settings.SettingBinder
import com.itangcent.easyapi.util.GsonUtils
import com.itangcent.easyapi.util.appendWithDedup
import com.itangcent.easyapi.logging.IdeaLog

/**
 * Resolves documentation metadata from PSI elements using rules and doc comments.
 *
 * Provides a unified interface for extracting:
 * - API names and descriptions
 * - Parameter names, types, and defaults
 * - Field descriptions
 * - Folder/group names
 * - Required status
 * - Return type overrides and main field hints
 * - Additional headers, parameters, and response headers
 * - Default HTTP methods and content types
 * - Path selection strategies
 *
 * ## Resolution Priority
 * For most metadata, the resolution order is:
 * 1. Rule-based value (e.g., `api.name`, `param.type`)
 * 2. Doc comment value
 * 3. Default value from PSI
 *
 * ## Deduplication
 * When combining doc comments with rule-based docs (e.g., `class.doc`, `method.doc`,
 * `param.doc`, `field.doc`), lines from the rule result that already exist in the
 * doc comment are removed to prevent duplication. This is handled by [appendWithDedup].
 *
 * ## Framework Applicability
 *
 * ### All Frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
 * - [resolveApiName] — API endpoint name
 * - [resolveClassDoc] — class-level description
 * - [resolveMethodDoc] — method-level description
 * - [resolveFieldDoc] — field-level description
 * - [resolveFolderName] — folder/group name
 * - [resolveMethodReturn] — override return type via rule
 * - [resolveMethodReturnMain] — specify which field receives @return doc
 * - [isIgnored] — skip element during export
 * - [resolveParamName], [resolveParamDoc], [resolveParamType],
 *   [resolveParamDefaultValue], [resolveParamDemo], [resolveParamMock],
 *   [isParamRequired], [isParamIgnored] — parameter metadata
 *
 * ### HTTP Frameworks (SpringMVC, JAX-RS, Feign, Actuator)
 * - [resolveAdditionalHeaders] — add extra request headers
 * - [resolveAdditionalParams] — add extra request parameters
 * - [resolveAdditionalResponseHeaders] — add extra response headers
 * - [resolveDefaultHttpMethod] — fallback HTTP method when not annotated
 * - [resolvePathMulti] — strategy for selecting paths when multiple exist
 *
 * ### Framework-Specific Notes
 * - **JAX-RS**: [resolveDefaultHttpMethod] is used as fallback when no HTTP method
 *   annotation is found. [resolvePathMulti] is not applicable since `@Path` only
 *   supports a single value.
 * - **Feign**: [resolvePathMulti] applies to Spring-style `@RequestMapping` with
 *   multiple paths. Native Feign uses `@RequestLine` which is always single-path.
 * - **gRPC**: HTTP-specific rules ([resolveAdditionalHeaders], [resolveAdditionalParams],
 *   [resolveAdditionalResponseHeaders], [resolveDefaultHttpMethod], [resolvePathMulti])
 *   are not applicable since gRPC uses its own protocol, not HTTP semantics.
 * - **Actuator**: [resolveDefaultHttpMethod] is not applicable since operation annotations
 *   (@ReadOperation, @WriteOperation, @DeleteOperation) always specify the method.
 *
 * ## Usage
 * ```kotlin
 * val resolver = DocMetadataResolver.getInstance(project)
 * val apiName = resolver.resolveApiName(method)
 * val paramType = resolver.resolveParamType(parameter, "java.lang.String")
 * val fieldDoc = resolver.resolveFieldDoc(field, fieldPath = "data")
 * ```
 *
 * @see RuleEngine for rule evaluation
 * @see DocHelper for doc comment extraction
 */
@Service(Service.Level.PROJECT)
class DocMetadataResolver internal constructor(
    private val project: Project
) : IdeaLog {
    private val engine: RuleEngine get() = RuleEngine.getInstance(project)
    private val docHelper: DocHelper get() = UnifiedDocHelper.getInstance(project)
    private val settings get() = SettingBinder.getInstance(project).read()

    companion object : IdeaLog {
        fun getInstance(project: Project): DocMetadataResolver = project.service()
    }
    /**
     * Resolves the API endpoint name.
     *
     * **Applicable to**: All frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
     *
     * Resolution order: `api.name` rule → doc comment first line → `method.doc` rule → method name
     */
    suspend fun resolveApiName(method: PsiMethod): String {
        val ruleApiName = engine.evaluate(RuleKeys.API_NAME, method)
        if (!ruleApiName.isNullOrBlank()) return ruleApiName

        val docComment = docHelper.getAttrOfDocComment(method)
        val headLine = docComment?.lines()?.firstOrNull { it.isNotBlank() }
        if (!headLine.isNullOrBlank()) return headLine

        val methodDoc = engine.evaluate(RuleKeys.METHOD_DOC, method)
        val docHeadLine = methodDoc?.lines()?.firstOrNull { it.isNotBlank() }
        if (!docHeadLine.isNullOrBlank()) return docHeadLine

        return method.name
    }

    /**
     * Resolves the class-level description.
     *
     * **Applicable to**: All frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
     *
     * Resolution order:
     * 1. Swagger class annotations (@Tag#name, @Api#value, @ApiModel#value)
     * 2. `class.doc` rule
     * 3. Doc comment (with dedup from rule result)
     * 4. Class name (fallback)
     */
    suspend fun resolveClassDoc(psiClass: PsiClass): String {
        val className = psiClass.qualifiedName ?: psiClass.name ?: "Unknown"

        // First, try to extract from Swagger annotations on the class
        val swaggerDesc = extractSwaggerClassDescription(psiClass)
        if (!swaggerDesc.isNullOrBlank()) {
            LOG.debug("[$className] Using Swagger annotation for class description: $swaggerDesc")
            return swaggerDesc
        }

        // Then, try the class.doc rule
        val ruleClassDesc = engine.evaluate(RuleKeys.CLASS_DOC, psiClass)
        if (!ruleClassDesc.isNullOrBlank()) {
            LOG.debug("[$className] Using class.doc rule for class description: $ruleClassDesc")
            return ruleClassDesc
        }

        // Finally, fall back to doc comment
        val docComment = docHelper.getAttrOfDocComment(psiClass)
        val result = if (!docComment.isNullOrBlank()) docComment else (psiClass.name ?: "Unknown")
        LOG.debug("[$className] Using doc comment/class name for class description: $result")
        return result
    }

    /**
     * Extracts class description from Swagger annotations.
     *
     * Priority:
     * 1. @Tag#name (OpenAPI 3.x)
     * 2. @Tags#value[].name (OpenAPI 3.x)
     * 3. @Api#value (Swagger 2.x)
     * 4. @Api#tags (Swagger 2.x) - takes first tag
     * 5. @ApiModel#value (Swagger 2.x)
     * 6. @ApiModel#description (Swagger 2.x)
     */
    private suspend fun extractSwaggerClassDescription(psiClass: PsiClass): String? {
        val className = psiClass.qualifiedName ?: psiClass.name ?: "Unknown"

        // Try OpenAPI 3.x @Tag annotation
        val tagAnnotation = psiClass.getAnnotation("io.swagger.v3.oas.annotations.tags.Tag")
        if (tagAnnotation != null) {
            val nameAttr = tagAnnotation.findAttributeValue("name")?.text
            if (!nameAttr.isNullOrBlank()) {
                LOG.debug("[$className] Found @Tag(name=$nameAttr)")
                return nameAttr
            }
            LOG.debug("[$className] Found @Tag but name attribute is empty")
        }

        // Try OpenAPI 3.x @Tags annotation
        val tagsAnnotation = psiClass.getAnnotation("io.swagger.v3.oas.annotations.tags.Tags")
        if (tagsAnnotation != null) {
            val valueAttr = tagsAnnotation.findAttributeValue("value")
            if (valueAttr is PsiArrayInitializerMemberValue) {
                val firstTag = valueAttr.initializers?.firstOrNull()
                if (firstTag is PsiAnnotation) {
                    val nameAttr = firstTag.findAttributeValue("name")?.text
                    if (!nameAttr.isNullOrBlank()) {
                        LOG.debug("[$className] Found @Tags with first tag name=$nameAttr")
                        return nameAttr
                    }
                }
            }
            LOG.debug("[$className] Found @Tags but could not extract name")
        }

        // Try Swagger 2.x @Api annotation
        val apiAnnotation = psiClass.getAnnotation("io.swagger.annotations.Api")
        if (apiAnnotation != null) {
            // Try @Api#value first
            val valueAttr = apiAnnotation.findAttributeValue("value")?.text
            if (!valueAttr.isNullOrBlank()) {
                LOG.debug("[$className] Found @Api(value=$valueAttr)")
                return valueAttr
            }
            // Then try @Api#tags (take first tag)
            val tagsAttr = apiAnnotation.findAttributeValue("tags")?.text
            if (!tagsAttr.isNullOrBlank()) {
                // tags is an array, take first element
                val firstTag = tagsAttr.split(",").firstOrNull()?.trim()
                if (!firstTag.isNullOrBlank()) {
                    LOG.debug("[$className] Found @Api with first tag=$firstTag")
                    return firstTag
                }
            }
            LOG.debug("[$className] Found @Api but could not extract value/tags")
        }

        // Try Swagger 2.x @ApiModel annotation
        val apiModelAnnotation = psiClass.getAnnotation("io.swagger.annotations.ApiModel")
        if (apiModelAnnotation != null) {
            // Try @ApiModel#value first
            val valueAttr = apiModelAnnotation.findAttributeValue("value")?.text
            if (!valueAttr.isNullOrBlank()) {
                LOG.debug("[$className] Found @ApiModel(value=$valueAttr)")
                return valueAttr
            }
            // Then try @ApiModel#description
            val descAttr = apiModelAnnotation.findAttributeValue("description")?.text
            if (!descAttr.isNullOrBlank()) {
                LOG.debug("[$className] Found @ApiModel(description=$descAttr)")
                return descAttr
            }
            LOG.debug("[$className] Found @ApiModel but could not extract value/description")
        }

        LOG.debug("[$className] No Swagger annotations found for class description")
        return null
    }

    /**
     * Resolves the method-level description.
     *
     * **Applicable to**: All frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
     *
     * Resolution order: doc comment → `method.doc` rule (with dedup)
     */
    suspend fun resolveMethodDoc(method: PsiMethod): String {
        val docComment = docHelper.getAttrOfDocComment(method)
        val ruleDoc = engine.evaluate(RuleKeys.METHOD_DOC, method)
        return docComment.appendWithDedup(ruleDoc)
    }

    /**
     * Resolves the field-level description.
     *
     * **Applicable to**: All frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
     *
     * Resolution order: doc comment (from [DocHelper.getAttrOfField] for PsiField,
     * or [DocHelper.getAttrOfDocComment] for other elements) → `field.doc` rule (with dedup)
     *
     * @param field The PSI element representing the field
     * @param fieldPath Optional field path context for rule evaluation (e.g., "data.items")
     */
    suspend fun resolveFieldDoc(field: PsiElement, fieldPath: String? = null): String? {
        val directComment = when (field) {
            is PsiField -> docHelper.getAttrOfField(field)
            else -> docHelper.getAttrOfDocComment(field)
        }
        val ruleComment = engine.evaluate(RuleKeys.FIELD_DOC, field, fieldContext = fieldPath)
        val combined = directComment.appendWithDedup(ruleComment)
        return combined.ifBlank { null }
    }

    /**
     * Resolves the folder/group name for an API endpoint.
     *
     * **Applicable to**: All frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
     *
     * Resolution order:
     * 1. `folder.name` rule on method/class
     * 2. Swagger class annotations (@Tag#name, @Api#value)
     * 3. Doc comment (first line)
     * 4. Class name (fallback)
     */
    suspend fun resolveFolderName(method: PsiMethod?, psiClass: PsiClass? = null): String? {
        if (method != null) {
            val methodFolder = engine.evaluate(RuleKeys.FOLDER_NAME, method)
            if (!methodFolder.isNullOrBlank()) return methodFolder
        }

        val cls = psiClass ?: method?.containingClass ?: return null
        // Try Swagger annotations for folder name
        val swaggerFolder = extractSwaggerClassDescription(cls)
        if (!swaggerFolder.isNullOrBlank()) {
            return swaggerFolder
        }

        val classFolder = engine.evaluate(RuleKeys.FOLDER_NAME, cls)
        if (!classFolder.isNullOrBlank()) return classFolder

        val classDoc = docHelper.getAttrOfDocComment(cls)
        if (!classDoc.isNullOrBlank()) {
            return classDoc.lines().firstOrNull { it.isNotBlank() }
        }

        return cls.name
    }

    suspend fun resolveParamName(parameter: PsiParameter, defaultName: String): String {
        val ruleParamName = engine.evaluate(RuleKeys.PARAM_NAME, parameter)
        return ruleParamName?.takeIf { it.isNotBlank() } ?: defaultName
    }

    suspend fun resolveParamDoc(parameter: PsiParameter): String {
        val method = parameter.declarationScope as? PsiMethod
        val paramName = parameter.name

        val javaDocComment = if (method != null) {
            docHelper.findDocsByTagAndName(method, "param", paramName)
        } else null

        val ruleDoc = engine.evaluate(RuleKeys.PARAM_DOC, parameter)

        return javaDocComment.appendWithDedup(ruleDoc)
    }

    suspend fun isParamRequired(parameter: PsiParameter): Boolean {
        return engine.evaluate(RuleKeys.PARAM_REQUIRED, parameter)
    }

    suspend fun isParamIgnored(parameter: PsiParameter): Boolean {
        return engine.evaluate(RuleKeys.PARAM_IGNORE, parameter)
    }

    suspend fun resolveParamType(parameter: PsiParameter, defaultType: String): String {
        val ruleType = engine.evaluate(RuleKeys.PARAM_TYPE, parameter)?.takeIf { it.isNotBlank() }
        if (ruleType != null) {
            return if (JsonType.isValid(ruleType)) ruleType else JsonType.fromJavaType(ruleType)
        }
        return JsonType.fromJavaType(defaultType)
    }

    suspend fun resolveParamDefaultValue(parameter: PsiParameter): String? {
        return engine.evaluate(RuleKeys.PARAM_DEFAULT_VALUE, parameter)
    }

    suspend fun resolveParamDemo(parameter: PsiParameter): String? {
        return engine.evaluate(RuleKeys.PARAM_DEMO, parameter)
    }

    suspend fun resolveParamMock(parameter: PsiParameter): String? {
        return engine.evaluate(RuleKeys.PARAM_MOCK, parameter)
    }

    suspend fun resolveApiTag(method: PsiMethod): String? {
        // First try to get tag from method level
        val methodTag = engine.evaluate(RuleKeys.API_TAG, method)
        if (!methodTag.isNullOrBlank()) {
            return methodTag
        }

        // If no tag at method level, try to get tag from class level
        val containingClass = method.containingClass ?: return null
        return engine.evaluate(RuleKeys.API_TAG, containingClass)
    }

    suspend fun resolveApiStatus(method: PsiMethod): String? {
        return engine.evaluate(RuleKeys.API_STATUS, method)
    }

    suspend fun isApiOpen(method: PsiMethod): Boolean {
        return engine.evaluate(RuleKeys.API_OPEN, method)
    }

    suspend fun resolveYapiProject(method: PsiMethod): String? {
        return engine.evaluate(RuleKeys.YAPI_PROJECT, method)
    }

    suspend fun resolveYapiProject(psiClass: PsiClass): String? {
        return engine.evaluate(RuleKeys.YAPI_PROJECT, psiClass)
    }

    suspend fun isIgnored(element: PsiElement): Boolean {
        return engine.evaluate(RuleKeys.IGNORE, element)
    }

    /**
     * Overrides the return type via the `method.return` rule.
     *
     * **Applicable to**: All frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
     */
    suspend fun resolveMethodReturn(method: PsiMethod): String? {
        return engine.evaluate(RuleKeys.METHOD_RETURN, method)
    }

    /**
     * Specifies which field in the response type should receive the `@return` doc comment.
     *
     * **Applicable to**: All frameworks (SpringMVC, JAX-RS, Feign, gRPC, Actuator)
     */
    suspend fun resolveMethodReturnMain(method: PsiMethod): String? {
        return engine.evaluate(RuleKeys.METHOD_RETURN_MAIN, method)
    }

    /**
     * Resolves the default HTTP method when not explicitly set by annotations.
     *
     * **Applicable to**: SpringMVC, JAX-RS (as fallback when no annotation found)
     *
     * **Not applicable to**: Feign (requires explicit method annotations),
     * gRPC (uses its own protocol), Actuator (operation annotations always specify method)
     */
    suspend fun resolveDefaultHttpMethod(method: PsiMethod): String? {
        return engine.evaluate(RuleKeys.METHOD_DEFAULT_HTTP, method)
    }

    /**
     * Resolves additional request headers to add to all endpoints.
     *
     * **Applicable to**: HTTP frameworks (SpringMVC, JAX-RS, Feign, Actuator)
     *
     * **Not applicable to**: gRPC (uses metadata, not HTTP headers)
     */
    suspend fun resolveAdditionalHeaders(method: PsiMethod): List<ApiHeader> {
        val raw = engine.evaluate(RuleKeys.METHOD_ADDITIONAL_HEADER, method) ?: return emptyList()
        return parseHeaderLines(raw)
    }

    /**
     * Resolves additional request parameters to add to all endpoints.
     *
     * **Applicable to**: HTTP frameworks (SpringMVC, JAX-RS, Feign, Actuator)
     *
     * **Not applicable to**: gRPC (fixed request message schema)
     */
    suspend fun resolveAdditionalParams(method: PsiMethod): List<ApiParameter> {
        val raw = engine.evaluate(RuleKeys.METHOD_ADDITIONAL_PARAM, method) ?: return emptyList()
        return parseParamLines(raw)
    }

    /**
     * Resolves additional response headers to add to all endpoints.
     *
     * **Applicable to**: HTTP frameworks (SpringMVC, JAX-RS, Feign, Actuator)
     *
     * **Not applicable to**: gRPC (uses trailing metadata)
     */
    suspend fun resolveAdditionalResponseHeaders(method: PsiMethod): List<ApiHeader> {
        val raw = engine.evaluate(RuleKeys.METHOD_ADDITIONAL_RESPONSE_HEADER, method) ?: return emptyList()
        return parseHeaderLines(raw)
    }

    private fun parseHeaderLines(raw: String): List<ApiHeader> {
        val result = ArrayList<ApiHeader>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val map = GsonUtils.fromJson<Map<String, Any?>>(trimmed)
                val name = map["name"]?.toString() ?: continue
                result.add(
                    ApiHeader(
                        name = name,
                        value = map["value"]?.toString(),
                        description = map["desc"]?.toString(),
                        required = map["required"]?.toString()?.toBooleanStrictOrNull() ?: false
                    )
                )
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun parseParamLines(raw: String): List<ApiParameter> {
        val result = ArrayList<ApiParameter>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val map = GsonUtils.fromJson<Map<String, Any?>>(trimmed)
                val name = map["name"]?.toString() ?: continue
                result.add(
                    ApiParameter(
                        name = name,
                        type = ParameterType.fromTypeName(map["type"]?.toString()),
                        required = map["required"]?.toString()?.toBooleanStrictOrNull() ?: false,
                        description = map["desc"]?.toString(),
                        defaultValue = map["value"]?.toString()
                    )
                )
            } catch (_: Exception) {
            }
        }
        return result
    }

    /**
     * Resolves the path selection strategy when a method has multiple path mappings.
     *
     * **Applicable to**: SpringMVC, Feign (Spring-style @RequestMapping with multiple paths)
     *
     * **Not applicable to**: JAX-RS (@Path only supports single value),
     * gRPC (single path per method), Actuator (single path per operation)
     */
    suspend fun resolvePathMulti(method: PsiMethod): PathSelector {
        val raw = engine.evaluate(RuleKeys.PATH_MULTI, method)
        if (!raw.isNullOrBlank()) return PathSelector.fromRule(raw)
        return PathSelector.fromRule(settings.pathMulti)
    }
}
