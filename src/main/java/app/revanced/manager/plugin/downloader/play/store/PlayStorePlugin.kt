package app.revanced.manager.plugin.downloader.play.store

import android.os.Parcelable
import android.util.Log
import app.revanced.manager.plugin.downloader.*
import app.revanced.manager.plugin.downloader.play.store.data.Credentials
import app.revanced.manager.plugin.downloader.play.store.data.Http
import app.revanced.manager.plugin.downloader.play.store.service.CredentialProviderService
import app.revanced.manager.plugin.downloader.play.store.ui.AuthActivity
import com.aurora.gplayapi.data.models.File as GPlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.app.AndroidManifest
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

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
    val files: List<GPlayFile>
) : Parcelable

@Suppress("Unused")
@OptIn(ExperimentalPathApi::class)
val playStoreDownloader = Downloader<GPlayApp> {
    get { packageName, version ->
        val (credentials, deviceProps) = useService<CredentialProviderService, Pair<Credentials, Properties>> { binder ->
            val credentialProvider = ICredentialProvider.Stub.asInterface(binder)
            val props = credentialProvider.properties.value
            credentialProvider.retrieveCredentials()?.let { return@useService it to props }

            try {
                requestStartActivity<AuthActivity>()
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

            credentialProvider.retrieveCredentials()?.let { it to props } ?: throw Exception("Could not get credentials")
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
            app.fileList.filterNot { it.url.isBlank() }.ifEmpty {
                PurchaseHelper(authData).using(Http).purchase(
                    app.packageName,
                    app.versionCode,
                    app.offerType
                )
            }
        ) to app.versionName
    }

    download { app, outputStream ->
        val apkDir = Files.createTempDirectory("play_dl")
        try {
            if (app.files.isEmpty()) error("No valid files to download")
            app.files.forEach { file ->
                if (file.type !in allowedFileTypes) error("${file.name} could not be downloaded because it has an unsupported type: ${file.type.name}")
                apkDir.resolve(file.name).outputStream(StandardOpenOption.CREATE_NEW)
                    .use { stream ->
                        Http.download(stream) {
                            url(file.url)
                        }
                    }
            }

            val apkFiles = apkDir.listDirectoryEntries()
            if (apkFiles.size == 1) {
                Files.copy(apkFiles.first(), outputStream)
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

                    merged.writeApk(outputStream)
                } finally {
                    closeables.forEach(Closeable::close)
                }
            }
        } finally {
            apkDir.deleteRecursively()
        }
    }
}