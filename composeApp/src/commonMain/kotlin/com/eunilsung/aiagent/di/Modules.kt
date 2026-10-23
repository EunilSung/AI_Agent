package com.eunilsung.aiagent.di

import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.local.DatabaseDriverFactory
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.repository.AssistantRepositoryImpl
import com.eunilsung.aiagent.data.repository.AuthRepositoryImpl
import com.eunilsung.aiagent.data.repository.ConversationRepositoryImpl
import com.eunilsung.aiagent.data.repository.MessengerRepositoryImpl
import com.eunilsung.aiagent.data.repository.ServerSettingsRepositoryImpl
import com.eunilsung.aiagent.data.repository.SummaryRequestRepositoryImpl
import com.eunilsung.aiagent.db.AppDatabase
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.domain.repository.AuthRepository
import com.eunilsung.aiagent.domain.repository.ConversationRepository
import com.eunilsung.aiagent.domain.repository.MessengerRepository
import com.eunilsung.aiagent.domain.repository.ServerSettingsRepository
import com.eunilsung.aiagent.domain.repository.SummaryRequestRepository
import com.eunilsung.aiagent.domain.usecase.CancelSendUseCase
import com.eunilsung.aiagent.domain.usecase.ChatUseCases
import com.eunilsung.aiagent.domain.usecase.ConfirmSendUseCase
import com.eunilsung.aiagent.domain.usecase.ConversationListUseCases
import com.eunilsung.aiagent.domain.usecase.CreateConversationUseCase
import com.eunilsung.aiagent.domain.usecase.DeleteConversationUseCase
import com.eunilsung.aiagent.domain.usecase.GetLastLoginIdUseCase
import com.eunilsung.aiagent.domain.usecase.GetServiceStatusUseCase
import com.eunilsung.aiagent.domain.usecase.HandleSummaryLinkUseCase
import com.eunilsung.aiagent.domain.usecase.LoginUseCase
import com.eunilsung.aiagent.domain.usecase.LoginUseCases
import com.eunilsung.aiagent.domain.usecase.LogoutUseCase
import com.eunilsung.aiagent.domain.usecase.MainUseCases
import com.eunilsung.aiagent.domain.usecase.ObserveConversationUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveConversationsUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveMessagesUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveServerUrlUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveSessionUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveSummaryLinksUseCase
import com.eunilsung.aiagent.domain.usecase.ResumeSummaryAfterLoginUseCase
import com.eunilsung.aiagent.domain.usecase.SendQuestionUseCase
import com.eunilsung.aiagent.domain.usecase.SetServerUrlUseCase
import com.eunilsung.aiagent.domain.usecase.SettingUseCases
import com.eunilsung.aiagent.domain.usecase.SummarizeFileUseCase
import com.eunilsung.aiagent.domain.usecase.TakeHeldSummariesUseCase
import com.eunilsung.aiagent.ui.chat.ChatViewModel
import com.eunilsung.aiagent.ui.conversationlist.ConversationListViewModel
import com.eunilsung.aiagent.ui.login.LoginViewModel
import com.eunilsung.aiagent.ui.main.MainViewModel
import com.eunilsung.aiagent.ui.setting.SettingViewModel
import com.russhwolf.settings.Settings
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { Settings() }
    single { AppDatabase(get<DatabaseDriverFactory>().createDriver()) }

    singleOf(::SessionStore)
    singleOf(::BridgeUrlStore)
    singleOf(::BridgeClient)
    singleOf(::ChatMapper)

    single<AuthRepository> { AuthRepositoryImpl(get(), get()) }
    single<ConversationRepository> { ConversationRepositoryImpl(get(), get(), get()) }
    single<AssistantRepository> { AssistantRepositoryImpl(get(), get(), get()) }
    single<ServerSettingsRepository> { ServerSettingsRepositoryImpl(get(), get()) }
    single<MessengerRepository> { MessengerRepositoryImpl(get(), get(), get()) }
    single<SummaryRequestRepository> { SummaryRequestRepositoryImpl() }

    singleOf(::LoginUseCase)
    singleOf(::GetLastLoginIdUseCase)
    singleOf(::ObserveSessionUseCase)
    singleOf(::LogoutUseCase)
    singleOf(::ObserveConversationsUseCase)
    singleOf(::CreateConversationUseCase)
    singleOf(::DeleteConversationUseCase)
    singleOf(::ObserveConversationUseCase)
    singleOf(::ObserveMessagesUseCase)
    singleOf(::SendQuestionUseCase)
    singleOf(::ConfirmSendUseCase)
    singleOf(::CancelSendUseCase)
    singleOf(::ObserveServerUrlUseCase)
    singleOf(::SetServerUrlUseCase)
    singleOf(::GetServiceStatusUseCase)
    singleOf(::ObserveSummaryLinksUseCase)
    singleOf(::HandleSummaryLinkUseCase)
    singleOf(::ResumeSummaryAfterLoginUseCase)
    singleOf(::TakeHeldSummariesUseCase)
    singleOf(::SummarizeFileUseCase)

    singleOf(::LoginUseCases)
    singleOf(::ConversationListUseCases)
    singleOf(::ChatUseCases)
    singleOf(::SettingUseCases)
    singleOf(::MainUseCases)

    viewModelOf(::MainViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::ConversationListViewModel)
    viewModel { params -> ChatViewModel(params.get(), get()) }
    viewModelOf(::SettingViewModel)
}
