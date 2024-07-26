package app.revanced.manager.plugin.downloader.play.store

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import app.revanced.manager.plugin.downloader.App
import app.revanced.manager.plugin.downloader.DownloaderContext
import app.revanced.manager.plugin.downloader.UserInteractionException
import app.revanced.manager.plugin.downloader.downloader
import app.revanced.manager.plugin.downloader.play.store.data.Http
import app.revanced.manager.plugin.downloader.play.store.data.PropertiesProvider
import app.revanced.manager.plugin.downloader.play.store.service.CredentialProviderService
import app.revanced.manager.plugin.downloader.play.store.ui.AuthActivity
import com.aurora.gplayapi.data.models.File as GPlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.app.AndroidManifest
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.parcelize.Parcelize
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

private class ServiceConnImpl : ServiceConnection {
    private val deferred = CompletableDeferred<IBinder>()

    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        deferred.complete(service)
    }

    suspend fun awaitBind() = deferred.await()

    override fun onServiceDisconnected(name: ComponentName?) {}
}

private suspend inline fun <R> withService(
    context: Context,
    intent: Intent,
    block: (IBinder) -> R
): R {
    val conn = ServiceConnImpl()
    context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    return try {
        block(withTimeout(10000L) { conn.awaitBind() })
    } finally {
        context.unbindService(conn)
    }
}

private val allowedFileTypes = arrayOf(GPlayFile.FileType.BASE, GPlayFile.FileType.SPLIT)
const val LOG_TAG = "PlayStorePlugin"

private object ArscLogger : APKLogger {
    const val TAG = "ARSCLib"

    override fun logMessage(msg: String) {
        Log.i(TAG, msg)
    }

    override fun logError(msg: String, tr: Throwable?) {
        Log.e(TAG, msg, tr)
    }

    override fun logVerbose(msg: String) {
        Log.v(TAG, msg)
    }
}

@Parcelize
class GPlayApp(
    override val packageName: String,
    override val version: String,
    val files: List<GPlayFile>
) : App(packageName, version)

@Suppress("Unused")
@OptIn(ExperimentalPathApi::class)
fun playStoreDownloader(context: DownloaderContext) = downloader<GPlayApp> {
    val deviceProps = PropertiesProvider.createDeviceProperties(context.androidContext)

    get { packageName, version ->
        val serviceIntent = Intent(context.androidContext, CredentialProviderService::class.java)
        val credentials = withService(context.androidContext, serviceIntent) { binder ->
            val credentialProvider = ICredentialProvider.Stub.asInterface(binder)
            credentialProvider.retrieveCredentials()?.let { return@withService it }

            try {
                requestUserInteraction().launch(
                    Intent(
                        context.androidContext,
                        AuthActivity::class.java
                    )
                )
            } catch (e: UserInteractionException.Activity.NotCompleted) {
                if (e.resultCode == AuthActivity.RESULT_FAILED) throw Exception(
                    "Login failed: ${
                        e.intent?.getStringExtra(
                            AuthActivity.FAILURE_MESSAGE_KEY
                        )
                    }"
                )
                throw e
            }

            credentialProvider.retrieveCredentials() ?: throw Exception("Could not get credentials")
        }
        val authData = credentials.toAuthData(deviceProps)

        val app = try {
            AppDetailsHelper(authData).using(Http).getAppByPackageName(packageName)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Got exception while trying to get app", e)
            null
        }?.takeUnless { version != null && it.versionName != version } ?: return@get null
        if (!app.isFree) return@get null

        GPlayApp(
            app.packageName,
            app.versionName,
            app.fileList.filterNot { it.url.isBlank() }.ifEmpty {
                PurchaseHelper(authData).using(Http).purchase(
                    app.packageName,
                    app.versionCode,
                    app.offerType
                )
            }
        )
    }

    download { app ->
        val apkDir = Files.createTempDirectory("play_dl")
        try {
            val totalSize = app.files.sumOf { it.size }
            var downloadedFilesSize = 0L

            if (app.files.isEmpty()) error("No valid files to download")
            app.files.forEach { file ->
                if (file.type !in allowedFileTypes) error("${file.name} could not be downloaded because it has an unsupported type: ${file.type.name}")
                apkDir.resolve(file.name).outputStream(StandardOpenOption.CREATE_NEW)
                    .use { stream ->
                        Http.download(stream) {
                            url(file.url)
                            onDownload { bytesSentTotal, _ ->
                                reportProgress(downloadedFilesSize + bytesSentTotal, totalSize)
                            }
                        }
                    }
                downloadedFilesSize += file.size
            }

            val apkFiles = apkDir.listDirectoryEntries()
            if (apkFiles.size == 1) {
                Files.move(
                    apkFiles.first(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } else {
                val closeables = mutableSetOf<Closeable>()
                try {
                    // Merge splits
                    val merged = withContext(Dispatchers.Default) {
                        with(ApkBundle()) {
                            setAPKLogger(ArscLogger)
                            loadApkDirectory(apkDir.toFile())
                            closeables.addAll(modules)
                            mergeModules().also(closeables::add)
                        }
                    }
                    merged.androidManifest.apply {
                        arrayOf(
                            AndroidManifest.ID_isSplitRequired,
                            AndroidManifest.ID_extractNativeLibs
                        ).forEach {
                            applicationElement.removeAttributesWithId(it)
                            manifestElement.removeAttributesWithId(it)
                        }

                        arrayOf(
                            AndroidManifest.NAME_requiredSplitTypes,
                            AndroidManifest.NAME_splitTypes
                        ).forEach(manifestElement::removeAttributesWithName)

                        val pattern = "^com\\.android\\.(stamp|vending)\\.".toRegex()
                        applicationElement.removeElements { element ->
                            if (element.name != AndroidManifest.TAG_meta_data) return@removeElements false
                            val nameAttr =
                                element.getAttributes { it.nameId == AndroidManifest.ID_name }
                                    .asSequence().single()

                            pattern.containsMatchIn(nameAttr.valueString)
                        }

                        refresh()
                    }

                    merged.writeApk(targetFile)
                } finally {
                    closeables.forEach(Closeable::close)
                }
            }
        } finally {
            apkDir.deleteRecursively()
        }
    }
}