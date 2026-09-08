package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoInjectionCollector
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class ExtraInfoCollectorRegistrationTest {
    @Test fun proactiveCollectorResolvesItsRegisteredFactory() {
        // JVM tests cannot create Android Context. Probe the real app module up to that boundary.
        // An unregistered collector fails before this dependency is requested.
        val contextProbe = IllegalStateException("Android context dependency reached")
        val application = koinApplication {
            modules(appModule, module {
                single<Context> { throw contextProbe }
            })
        }
        try {
            try {
                application.koin.get<ExtraInfoInjectionCollector>()
                fail("Expected the Android context probe")
            } catch (error: Exception) {
                assertTrue(
                    "Collector must resolve its factory instead of failing with a missing definition",
                    generateSequence<Throwable>(error) { it.cause }.any { it === contextProbe },
                )
            }
        } finally {
            application.close()
        }
    }
}
