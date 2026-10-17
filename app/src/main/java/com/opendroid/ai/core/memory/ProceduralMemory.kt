package com.opendroid.ai.core.memory

import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProceduralMemory @Inject constructor(
    private val memoryRepository: MemoryRepository
) {
    suspend fun saveMacro(macro: Macro) {
        memoryRepository.saveMacro(macro)
    }

    suspend fun getMacro(name: String): Macro? {
        return memoryRepository.getMacroByName(name)
    }
}
