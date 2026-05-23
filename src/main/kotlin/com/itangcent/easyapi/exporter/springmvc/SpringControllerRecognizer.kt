package com.itangcent.easyapi.exporter.springmvc

import com.intellij.psi.PsiClass
import com.itangcent.easyapi.exporter.core.ApiClassRecognizer
import com.itangcent.easyapi.exporter.core.MetaAnnotationResolver
import com.itangcent.easyapi.logging.IdeaLog
import com.itangcent.easyapi.rule.RuleKeys
import com.itangcent.easyapi.rule.engine.RuleEngine
import com.itangcent.easyapi.settings.state.ApplicationSettingsSupport

/**
 * Recognizes Spring MVC controller classes.
 *
 * Supports both standard annotations (@Controller, @RestController) and
 * custom meta-annotations (e.g., @CustomRestController annotated with @RestController).
 *
 * Can filter controllers based on Swagger annotations when enabled in settings.
 */
class SpringControllerRecognizer(
    private val ruleEngine: RuleEngine? = null,
    private val settings: ApplicationSettingsSupport? = null
) : ApiClassRecognizer {

    override val frameworkName: String = "SpringMVC"

    override val targetAnnotations: Set<String> = CONTROLLER_ANNOTATIONS

    override suspend fun isApiClass(psiClass: PsiClass): Boolean {
        if (ruleEngine?.evaluate(RuleKeys.CLASS_IS_CTRL, psiClass) == true) return true

        // Check if the class has Controller annotations
        if (!MetaAnnotationResolver.hasMetaAnnotation(psiClass, CONTROLLER_ANNOTATIONS)) {
            return false
        }

        // If "only scan swagger annotated controllers" is enabled, check for Swagger annotations
        if (settings?.onlyScanSwaggerAnnotatedControllers == true) {
            if (!hasSwaggerAnnotation(psiClass)) {
                val className = psiClass.qualifiedName ?: psiClass.name ?: "Unknown"
                LOG.debug("Controller '$className' filtered out: no Swagger annotation found")
                return false
            }
            val className = psiClass.qualifiedName ?: psiClass.name ?: "Unknown"
            LOG.debug("Controller '$className' passed Swagger annotation check")
        }

        return true
    }

    suspend fun isController(psiClass: PsiClass): Boolean = isApiClass(psiClass)

    private suspend fun hasSwaggerAnnotation(psiClass: PsiClass): Boolean {
        val hasAnnotation = MetaAnnotationResolver.hasMetaAnnotation(psiClass, ALL_SWAGGER_ANNOTATIONS)

        // Log for debugging
        if (settings?.onlyScanSwaggerAnnotatedControllers == true) {
            val className = psiClass.qualifiedName ?: psiClass.name ?: "Unknown"
            val foundAnnotations = psiClass.annotations
                .mapNotNull { it.qualifiedName }
                .filter { it in ALL_SWAGGER_ANNOTATIONS || it.startsWith("io.swagger") || it.startsWith("io.swagger.v3") }

            LOG.debug("Checking '$className' for Swagger annotations:")
            LOG.debug("  - Found Swagger-related annotations: $foundAnnotations")
            LOG.debug("  - Result: ${if (hasAnnotation) "PASS" else "FAIL"}")
        }

        return hasAnnotation
    }

    companion object : IdeaLog {
        val CONTROLLER_ANNOTATIONS = setOf(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController"
        )

        // OpenAPI 3.x annotations
        private val OPENAPI_ANNOTATIONS = setOf(
            "io.swagger.v3.oas.annotations.tags.Tag",
            "io.swagger.v3.oas.annotations.tags.Tags"
        )

        // Swagger 2.x annotations
        private val SWAGGER2_ANNOTATIONS = setOf(
            "io.swagger.annotations.Api"
        )

        // All Swagger annotations (both OpenAPI 3.x and Swagger 2.x)
        val ALL_SWAGGER_ANNOTATIONS = OPENAPI_ANNOTATIONS + SWAGGER2_ANNOTATIONS
    }
}
