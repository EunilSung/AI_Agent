package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.mapper.bridgeRefusalOf
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.remote.ServerResult
import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.repository.ServerSettingsRepository
import kotlinx.coroutines.flow.StateFlow

class ServerSettingsRepositoryImpl(
    private val urlStore: BridgeUrlStore,
    private val bridge: BridgeClient,
) : ServerSettingsRepository {

    override val baseUrl: StateFlow<String> = urlStore.url

    override fun setBaseUrl(url: String) = urlStore.set(url)

    override suspend fun status(): ServiceStatus = when (val result = bridge.health()) {
        is ServerResult.Success -> ServiceStatus(
            bridge = true,
            talk = result.value.talk,
            chatModel = result.value.rag?.chatModel,
            embedModel = result.value.rag?.embedModel,
            chunks = result.value.rag?.chunks,
        )
        is ServerResult.Rejected -> ServiceStatus(bridge = false, refusal = bridgeRefusalOf(result.code))
        ServerResult.Unreachable -> ServiceStatus(bridge = false)
    }
}
