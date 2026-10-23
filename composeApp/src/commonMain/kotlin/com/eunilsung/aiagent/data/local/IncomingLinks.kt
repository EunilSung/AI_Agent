package com.eunilsung.aiagent.data.local

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 앱 밖에서 열린 링크(`aiagent://…`)를 받아 두는 곳.
 *
 * 플랫폼 진입점(Android `MainActivity`, iOS `onOpenURL`)이 Koin 없이 [offer] 로 넣고,
 * [com.eunilsung.aiagent.data.repository.SummaryRequestRepositoryImpl] 이 꺼낸다.
 * 앱이 링크로 처음 켜져 화면이 아직 없어도, 꺼낼 때까지 가장 최근 링크 하나를 들고 있다.
 */
object IncomingLinks {
    private val channel = Channel<String>(Channel.CONFLATED)

    /** 들어온 링크. 한 곳에서만 받는다 — 받은 링크는 다시 나오지 않는다. */
    val links: Flow<String> = channel.receiveAsFlow()

    fun offer(link: String) {
        channel.trySend(link)
    }
}
