package com.hik.otest

import android.app.Application
import com.hik.otest.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppDelegate : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Hikotest.configure(
            HikotestConfig.Builder()
                .githubToken(BuildConfig.HIKOTEST_GITHUB_TOKEN)
                .repoOwner(BuildConfig.HIKOTEST_REPO_OWNER)
                .repoName(BuildConfig.HIKOTEST_REPO_NAME)
                .environment(BuildConfig.HIKOTEST_ENVIRONMENT)
                .isBeta(BuildConfig.HIKOTEST_IS_BETA)
                .build()
        )

        appScope.launch {
            runCatching { Hikotest.initialize(this@AppDelegate) }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Hikotest.shutdown()
        appScope.cancel()
    }
}
