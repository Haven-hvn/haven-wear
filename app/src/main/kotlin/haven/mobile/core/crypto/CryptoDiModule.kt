package haven.mobile.core.crypto

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoDiModule {
    @Binds
    @Singleton
    abstract fun bindHavenCipher(impl: HavenCipherImpl): HavenCipher
}

@Module
@InstallIn(SingletonComponent::class)
object CryptoCacheModule {
    @dagger.Provides
    @Singleton
    fun provideAesKeyCache(config: CryptoConfig): AesKeyCache = AesKeyCache(config.aesKeyCacheCapacity)

    @dagger.Provides
    @Singleton
    fun provideGateKeyCache(config: CryptoConfig): GateKeyCache = GateKeyCache(config.gateKeyCacheCapacity)

    @dagger.Provides
    @Singleton
    fun provideCryptoConfig(): CryptoConfig = CryptoConfig()
}