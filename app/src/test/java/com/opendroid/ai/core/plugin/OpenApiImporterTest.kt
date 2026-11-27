package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.connector.ConnectorRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenAPI 3.1 import, with the failure modes that must not be silently accepted. */
class OpenApiImporterTest {

    private val petstore = """
    {
      "openapi": "3.1.0",
      "info": { "title": "Pet API", "version": "1.0.0", "contact": { "name": "Pets Inc" } },
      "servers": [ { "url": "https://api.example.com/v1" } ],
      "paths": {
        "/pets": {
          "get": {
            "operationId": "listPets",
            "summary": "List pets",
            "parameters": [
              { "name": "limit", "in": "query", "required": true, "schema": { "type": "integer" } }
            ]
          },
          "post": {
            "operationId": "createPet",
            "requestBody": {
              "required": true,
              "content": { "application/json": { "schema": { "type": "object", "properties": { "name": { "type": "string" } } } } }
            }
          }
        },
        "/pets/{petId}": {
          "parameters": [ { "name": "petId", "in": "path", "required": true, "schema": { "type": "string" } } ],
          "get": { "operationId": "getPet" },
          "delete": { "operationId": "deletePet" }
        }
      }
    }
    """.trimIndent()

    private fun success(json: String = petstore) =
        (OpenApiImporter.import(json, "pets.api") as OpenApiImporter.Result.Success).api

    @Test
    fun `imports a 3_1 document into a manifest and tools`() {
        val api = success()

        assertEquals("pets.api", api.manifest.id)
        assertEquals("Pet API", api.manifest.displayName)
        assertEquals("Pets Inc", api.manifest.publisher)
        assertEquals("https://api.example.com/v1", api.baseUrl)
        assertEquals(4, api.tools.size)
        assertTrue(api.manifest.interop.contains("OPENAPI_31"))
    }

    @Test
    fun `every tool belongs to the plugin and carries a JSON schema`() {
        val api = success()

        assertTrue(api.tools.all { it.connectorId == "pets.api" })
        assertTrue(api.tools.all { it.parametersJsonSchema.contains("json-schema.org/draft/2020-12") })
    }

    @Test
    fun `get is read-only and delete is destructive`() {
        val api = success()
        val byName = api.tools.associateBy { it.name }

        assertEquals(ConnectorRisk.READ_ONLY, byName.getValue("getPet").risk)
        assertEquals(ConnectorRisk.DESTRUCTIVE, byName.getValue("deletePet").risk)
        assertEquals(ConnectorRisk.SENSITIVE, byName.getValue("createPet").risk)
    }

    @Test
    fun `routes record the http method and the real path`() {
        val api = success()

        assertEquals("GET", api.routes.getValue("getPet").method)
        assertEquals("/pets/{petId}", api.routes.getValue("getPet").pathTemplate)
        assertEquals("DELETE", api.routes.getValue("deletePet").method)
    }

    @Test
    fun `a query parameter becomes a route placeholder`() {
        val api = success()

        assertEquals("/pets?limit={limit}", api.routes.getValue("listPets").pathTemplate)
    }

    @Test
    fun `a path-level parameter applies to every operation under it`() {
        val api = success()
        val getPet = api.tools.first { it.name == "getPet" }

        assertTrue(getPet.parametersJsonSchema.contains("petId"))
    }

    @Test
    fun `a request body becomes a body property`() {
        val api = success()
        val create = api.tools.first { it.name == "createPet" }

        assertTrue(create.parametersJsonSchema.contains("\"body\""))
    }

    @Test
    fun `a non-3_1 document is refused with the declared version`() {
        val result = OpenApiImporter.import(
            """{"openapi":"3.0.3","info":{"title":"x"},"servers":[{"url":"https://x"}],"paths":{"/a":{"get":{}}}}""",
            "x.api",
        )

        assertTrue(result is OpenApiImporter.Result.Failure)
        assertTrue((result as OpenApiImporter.Result.Failure).reason.contains("3.0.3"))
    }

    @Test
    fun `a document with no servers is refused`() {
        val result = OpenApiImporter.import(
            """{"openapi":"3.1.0","info":{"title":"x"},"paths":{"/a":{"get":{}}}}""",
            "x.api",
        )

        assertTrue(result is OpenApiImporter.Result.Failure)
        assertTrue((result as OpenApiImporter.Result.Failure).reason.contains("server URL"))
    }

    @Test
    fun `a document with no usable operations is refused`() {
        val result = OpenApiImporter.import(
            """{"openapi":"3.1.0","info":{"title":"x"},"servers":[{"url":"https://x"}],"paths":{"/a":{"trace":{}}}}""",
            "x.api",
        )

        assertTrue(result is OpenApiImporter.Result.Failure)
    }

    @Test
    fun `an invalid plugin id is refused`() {
        val result = OpenApiImporter.import(petstore, "Invalid ID")
        assertTrue(result is OpenApiImporter.Result.Failure)
    }

    @Test
    fun `a plain http server is imported but warned about`() {
        val api = success(
            """{"openapi":"3.1.0","info":{"title":"x"},"servers":[{"url":"http://insecure.example.com"}],
               "paths":{"/a":{"get":{"operationId":"a"}}}}"""
        )

        assertTrue(api.warnings.any { it.contains("plain HTTP") })
    }

    @Test
    fun `a non-http server scheme is refused`() {
        val result = OpenApiImporter.import(
            """{"openapi":"3.1.0","info":{"title":"x"},"servers":[{"url":"ftp://files.example.com"}],"paths":{"/a":{"get":{"operationId":"a"}}}}""",
            "x.api",
        )

        assertTrue(result is OpenApiImporter.Result.Failure)
    }

    @Test
    fun `malformed json is refused`() {
        val result = OpenApiImporter.import("{not json", "x.api")
        assertTrue(result is OpenApiImporter.Result.Failure)
    }

    @Test
    fun `a header parameter is reported as a warning`() {
        val api = success(
            """{"openapi":"3.1.0","info":{"title":"x"},"servers":[{"url":"https://x"}],
               "paths":{"/a":{"get":{"operationId":"a","parameters":[{"name":"X-Trace","in":"header","schema":{"type":"string"}}]}}}}"""
        )

        assertTrue(api.warnings.any { it.contains("header parameter") })
    }

    @Test
    fun `tool names are sanitized to a safe identifier`() {
        assertEquals("pets_list_all", OpenApiImporter.sanitizeToolName("pets/list all"))
        assertEquals("abc", OpenApiImporter.sanitizeToolName("__abc__"))
    }
}
