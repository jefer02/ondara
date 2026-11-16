package app.ondara

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.ondara.core.data.ApplicationScope
import app.ondara.core.domain.SettingsRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class OndaraApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var okHttp: OkHttpClient

    @Inject lateinit var settings: SettingsRepository

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Cero telemetría por defecto. ACRA solo se inicializa si el usuario lo activó (opt-in), y aun
     * así los informes se quedan en el dispositivo ([LocalCrashSenderFactory]).
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (base.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).getBoolean(CRASH_ENABLED, false)) {
            initAcra {
                buildConfigClass = BuildConfig::class.java
                reportFormat = StringFormat.JSON
                reportContent = listOf(
                    ReportField.APP_VERSION_NAME, ReportField.ANDROID_VERSION, ReportField.PHONE_MODEL,
                    ReportField.STACK_TRACE, ReportField.USER_CRASH_DATE,
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Espejo del ajuste en SharedPreferences: es lo único legible de forma síncrona en attachBaseContext.
        scope.launch {
            settings.settings.map { it.crashReports }.distinctUntilChanged().collect { enabled ->
                getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit { putBoolean(CRASH_ENABLED, enabled) }
            }
        }
    }

    /** Coil comparte el OkHttp de la app (mismo pool de conexiones que el resto de la red). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
        .crossfade(true)
        .build()

    private companion object {
        const val CRASH_PREFS = "crash"
        const val CRASH_ENABLED = "enabled"
    }
}

/** Guarda cada informe como JSON en `Android/data/<app>/files/crash/`; no hay envío por red. */
class LocalCrashSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender = object : ReportSender {
        override fun send(context: Context, errorContent: CrashReportData) {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash").apply { mkdirs() }
            File(dir, "crash-${System.currentTimeMillis()}.json").writeText(errorContent.toJSON())
            // Solo se conservan los 10 más recientes.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        }
    }
}
