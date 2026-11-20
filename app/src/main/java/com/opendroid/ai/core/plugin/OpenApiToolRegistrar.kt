package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.connector.UniversalToolRegistry

/**
 * Requirement 5, OpenAPI half: take the operations [OpenApiImporter] read out of a document and make
 * them executable. The importer already produced the tool schemas and the method/path for each
 * operation; registering them here is what turns an imported document into callable tools.
 *
 * Execution then goes through the same [UniversalToolRegistry.execute] path as every other connector,
 * so the kill switch, the argument-size limit, the approval gate and the execution log all apply
 * unchanged. Nothing about OpenAPI gets a shortcut around those.
 */
object OpenApiToolRegistrar {

    /** Registers [api] under [api].manifest.id and returns the registered tool names. */
    fun register(registry: UniversalToolRegistry, api: OpenApiImporter.ImportedApi): List<String> {
        val methodByTool = api.routes.mapValues { (_, route) -> route.method }
        val pathByTool = api.routes.mapValues { (_, route) -> route.pathTemplate }
        registry.registerHttpManifest(
            id = api.manifest.id,
            displayName = api.manifest.displayName,
            kind = com.opendroid.ai.core.connector.ConnectorKind.REST,
            baseUrl = api.baseUrl,
            tools = api.tools,
            methodByTool = methodByTool,
            pathByTool = pathByTool,
        )
        return api.tools.map { it.qualifiedName }
    }

    /** Imports and registers in one step; returns the importer failure reason unchanged. */
    fun importAndRegister(
        registry: UniversalToolRegistry,
        documentJson: String,
        pluginId: String,
        displayName: String? = null,
        version: String = "1.0.0",
        appVersion: String = "0.0.0",
    ): Result<List<String>> = when (
        val imported = OpenApiImporter.import(documentJson, pluginId, displayName, version, appVersion)
    ) {
        is OpenApiImporter.Result.Success -> Result.success(register(registry, imported.api))
        is OpenApiImporter.Result.Failure -> Result.failure(IllegalArgumentException(imported.reason))
    }
}
