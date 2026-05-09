package com.itangcent.easyapi.integration

import com.itangcent.easyapi.exporter.curl.CurlFormatter
import com.itangcent.easyapi.exporter.model.*
import com.itangcent.easyapi.exporter.postman.PostmanFormatOptions
import com.itangcent.easyapi.exporter.postman.PostmanFormatter
import com.itangcent.easyapi.psi.model.FieldModel
import com.itangcent.easyapi.psi.model.ObjectModel
import com.itangcent.easyapi.testFramework.EasyApiLightCodeInsightFixtureTestCase
import com.itangcent.easyapi.testFramework.TestConfigReader
import kotlinx.coroutines.runBlocking

class FormatConversionTest : EasyApiLightCodeInsightFixtureTestCase() {

    override fun createConfigReader() = TestConfigReader.empty(project)

    private val testEndpoint = ApiEndpoint(
        name = "Get User",
        description = "Retrieve user by ID",
        metadata = httpMetadata(
            path = "/api/users/{id}",
            method = HttpMethod.GET,
            parameters = listOf(
                ApiParameter(name = "id", binding = ParameterBinding.Path, example = "1")
            )
        )
    )

    private val testPostEndpoint = ApiEndpoint(
        name = "Create User",
        description = "Create a new user",
        metadata = httpMetadata(
            path = "/api/users",
            method = HttpMethod.POST,
            contentType = "application/json",
            parameters = listOf(
                ApiParameter(name = "name", binding = ParameterBinding.Body, example = "John"),
                ApiParameter(name = "email", binding = ParameterBinding.Body, example = "john@example.com")
            )
        )
    )

    private val testEndpointWithResponseBody = ApiEndpoint(
        name = "Get User",
        description = "Retrieve user by ID",
        metadata = httpMetadata(
            path = "/api/users/{id}",
            method = HttpMethod.GET,
            parameters = listOf(
                ApiParameter(name = "id", binding = ParameterBinding.Path, example = "1")
            ),
            responseBody = ObjectModel.Object(
                fields = mapOf(
                    "id" to FieldModel(ObjectModel.Single("long")),
                    "name" to FieldModel(ObjectModel.Single("string")),
                    "email" to FieldModel(ObjectModel.Single("string"))
                )
            )
        )
    )

    fun testPostmanFormatConversion(): Unit = runBlocking {
        val postmanFormatter = PostmanFormatter(
            project = project,
            options = PostmanFormatOptions(buildExample = true, autoMergeScript = true)
        )
        val endpoints = listOf(testEndpoint, testPostEndpoint)
        val collection = postmanFormatter.format(endpoints, "Test API")

        assertNotNull("Collection should not be null", collection)
        assertTrue("Collection name should start with Test API", collection.info?.name?.startsWith("Test API") == true)
        assertTrue("Should have items", collection.item?.isNotEmpty() == true)
    }

    fun testCurlFormatConversion() {
        val curl = CurlFormatter.format(testPostEndpoint, "https://api.example.com")

        assertNotNull("cURL output should not be null", curl)
        assertTrue("Should contain POST method", curl.contains("POST"))
        assertTrue("Should contain URL", curl.contains("https://api.example.com/api/users"))
        assertTrue("Should contain Content-Type", curl.contains("application/json"))
    }

    fun testJson5FormatConversion() {
        // Json5Formatter removed — JSON5 formatting now handled by YapiFormatter.formatAsJson5 on ObjectModel
    }

    fun testMultipleEndpointsFormat(): Unit = runBlocking {
        val postmanFormatter = PostmanFormatter(
            project = project,
            options = PostmanFormatOptions(buildExample = true, autoMergeScript = true)
        )
        val endpoints = listOf(
            testEndpoint,
            testPostEndpoint,
            ApiEndpoint(
                name = "Update User",
                metadata = httpMetadata(
                    path = "/api/users/{id}",
                    method = HttpMethod.PUT,
                    contentType = "application/json",
                    parameters = listOf(
                        ApiParameter(name = "id", binding = ParameterBinding.Path, example = "1"),
                        ApiParameter(name = "name", binding = ParameterBinding.Body, example = "Updated")
                    )
                )
            ),
            ApiEndpoint(
                name = "Delete User",
                metadata = httpMetadata(
                    path = "/api/users/{id}",
                    method = HttpMethod.DELETE,
                    parameters = listOf(
                        ApiParameter(name = "id", binding = ParameterBinding.Path, example = "1")
                    )
                )
            )
        )

        val collection = postmanFormatter.format(endpoints, "CRUD API")
        assertTrue("Should have items", collection.item?.isNotEmpty() == true)
    }

    fun testPostmanResponseExampleGeneration(): Unit = runBlocking {
        val formatterWithExample = PostmanFormatter(
            project = project,
            options = PostmanFormatOptions(buildExample = true, autoMergeScript = false)
        )
        val collectionWithExample = formatterWithExample.format(listOf(testEndpointWithResponseBody), "Test API")

        assertNotNull("Collection should not be null", collectionWithExample)
        val item = collectionWithExample.item?.firstOrNull()
        assertNotNull("Should have at least one item", item)

        val responses = item?.response
        assertNotNull("Should have response examples when buildExample=true and responseBody exists", responses)
        assertTrue("Should have at least one response example", responses?.isNotEmpty() == true)

        val firstResponse = responses?.firstOrNull()
        assertNotNull("First response should not be null", firstResponse)
        assertEquals("Response status code should be 200", 200, firstResponse?.code)
        assertTrue("Response body should contain JSON", firstResponse?.body?.isNotBlank() == true)

        val formatterWithoutExample = PostmanFormatter(
            project = project,
            options = PostmanFormatOptions(buildExample = false, autoMergeScript = false)
        )
        val collectionWithoutExample = formatterWithoutExample.format(listOf(testEndpointWithResponseBody), "Test API")

        val itemWithoutExample = collectionWithoutExample.item?.firstOrNull()
        val responsesWithoutExample = itemWithoutExample?.response
        assertTrue("Should not have response examples when buildExample=false", responsesWithoutExample?.isEmpty() != false)
    }

    fun testPostmanResponseExampleGenerationWithoutResponseBody(): Unit = runBlocking {
        val formatter = PostmanFormatter(
            project = project,
            options = PostmanFormatOptions(buildExample = true, autoMergeScript = false)
        )
        val collection = formatter.format(listOf(testEndpoint), "Test API")

        val item = collection.item?.firstOrNull()
        assertNotNull("Should have at least one item", item)

        val responses = item?.response
        assertTrue("Should not have response examples when responseBody is null", responses?.isEmpty() != false)
    }
}
