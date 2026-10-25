package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.connector.CloudToolAgent
import com.opendroid.ai.core.connector.ConnectorRisk
import com.opendroid.ai.core.connector.ConnectorTool
import com.opendroid.ai.core.connector.UniversalToolRegistry
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val catalogCategories = listOf(
    "Android & Phone",
    "Cloud & APIs",
    "Developer Tools",
    "Files & Storage",
    "Git & Version Control",
    "Network & Web",
    "Termux & Linux",
    "Utilities"
)

data class DeveloperToolEntry(
    val id: String,
    val connectorName: String,
    val name: String,
    val description: String,
    val category: String,
    val risk: ConnectorRisk,
    val compatible: Boolean
)

data class DeveloperToolCategory(val name: String, val tools: List<DeveloperToolEntry>)

/** Pure grouping contract so the UI and its empty states are deterministic and testable. */
object DeveloperToolCatalog {
    val categories: List<String> = catalogCategories

    fun group(tools: List<ConnectorTool>): List<DeveloperToolCategory> {
        val entries = tools.map { tool ->
            DeveloperToolEntry(
                id = tool.qualifiedName,
                connectorName = tool.connectorId,
                name = tool.name,
                description = tool.description,
                category = tool.category.ifBlank { "Developer Tools" },
                risk = tool.risk,
                compatible = tool.compatible
            )
        }
        val allCategories = (categories + entries.map { it.category }).distinct().sortedBy { it.lowercase() }
        return allCategories.map { category ->
            DeveloperToolCategory(
                category,
                entries.filter { it.category.equals(category, ignoreCase = true) && it.compatible }
                    .sortedWith(compareBy<DeveloperToolEntry> { it.name.lowercase() }.thenBy { it.id })
            )
        }
    }
}

@HiltViewModel
class DeveloperToolsViewModel @Inject constructor(
    private val registry: UniversalToolRegistry,
    private val ensureLocalConnectors: Lazy<CloudToolAgent>
) : ViewModel() {
    private val _categories = MutableStateFlow(DeveloperToolCatalog.group(emptyList()))
    val categories: StateFlow<List<DeveloperToolCategory>> = _categories.asStateFlow()

    init {
        // Constructing the singleton agent registers its real local connectors in this same registry.
        ensureLocalConnectors.get()
        viewModelScope.launch {
            registry.catalogTools.collect { tools -> _categories.value = DeveloperToolCatalog.group(tools) }
        }
    }
}
