package com.bd2toolsbox.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bd2toolsbox.data.repository.AvatarRepository
import com.bd2toolsbox.data.repository.Bd2RedeemRepository
import com.bd2toolsbox.data.repository.CharacterMetaRepository
import com.bd2toolsbox.data.repository.GamekeeRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** 未持有 ViewModel 的 UI 叶节点也从同一应用图取仓储，不另建静态单例。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UiRepositories {
    fun characterMetaRepository(): CharacterMetaRepository
    fun avatarRepository(): AvatarRepository
    fun gamekeeRepository(): GamekeeRepository
    fun bd2RedeemRepository(): Bd2RedeemRepository
}

@Composable
fun rememberUiRepositories(): UiRepositories {
    val application = LocalContext.current.applicationContext
    return remember(application) {
        EntryPointAccessors.fromApplication(application, UiRepositories::class.java)
    }
}
