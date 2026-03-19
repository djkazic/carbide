package com.carbide.wallet.di

import com.carbide.wallet.data.lnd.LndConnection
import com.carbide.wallet.data.lnd.RealLndConnection
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class LndModule {
    @Binds
    abstract fun bindLndConnection(impl: RealLndConnection): LndConnection
}
