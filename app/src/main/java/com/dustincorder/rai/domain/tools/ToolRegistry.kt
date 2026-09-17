package com.dustincorder.rai.domain.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central registry holding all discovered and registered Raya tools.
 */
interface ToolRegistry {
    val tools: StateFlow<List<RayaTool>>

    suspend fun register(tool: RayaTool)
    suspend fun unregister(id: ToolId)
    suspend fun get(name: String): RayaTool?
    suspend fun getById(id: ToolId): RayaTool?
    suspend fun activeTools(): List<RayaTool>
}

class InMemoryToolRegistry(
    initialTools: List<RayaTool> = emptyList(),
) : ToolRegistry {
    private val mutex = Mutex()
    private val _tools = MutableStateFlow(initialTools)
    override val tools: StateFlow<List<RayaTool>> = _tools.asStateFlow()

    override suspend fun register(tool: RayaTool) = mutex.withLock {
        _tools.value = _tools.value.filterNot { it.definition.id == tool.definition.id } + tool
    }

    override suspend fun unregister(id: ToolId) = mutex.withLock {
        _tools.value = _tools.value.filterNot { it.definition.id == id }
    }

    override suspend fun get(name: String): RayaTool? = mutex.withLock {
        _tools.value.firstOrNull { it.definition.name == name }
    }

    override suspend fun getById(id: ToolId): RayaTool? = mutex.withLock {
        _tools.value.firstOrNull { it.definition.id == id }
    }

    override suspend fun activeTools(): List<RayaTool> = mutex.withLock {
        _tools.value.filter { it.definition.enabled }
    }
}
