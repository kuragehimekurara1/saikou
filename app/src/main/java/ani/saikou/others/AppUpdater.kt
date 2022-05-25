package ani.saikou.others

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import ani.saikou.*
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

object AppUpdater {
    suspend fun check(activity: FragmentActivity) {
        val repo = activity.getString(R.string.repo)
        tryWithSuspend {
            val md =
                client.get("https://raw.githubusercontent.com/$repo/main/${if (!BuildConfig.DEBUG) "stable" else "beta"}.md").text

            val version = md.substringAfter("# ").substringBefore("\n")
            logger("Git Version : $version")
            val dontShow = loadData("dont_ask_for_update_$version") ?: false
            if (compareVersion(version) && !dontShow && !activity.isDestroyed) activity.runOnUiThread {
                CustomBottomDialog.newInstance().apply {
                    setTitleText("${if (!BuildConfig.DEBUG) "" else "Beta "}Update Available")
                    addView(
                        TextView(activity).apply {
                            val markWon = Markwon.builder(activity).usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                            markWon.setMarkdown(this, md)
                        }
                    )

                    setCheck("Don't show again for version $version", false) { isChecked ->
                        if (isChecked) {
                            saveData("dont_ask_for_update_$version", isChecked)
                        }
                    }
                    setPositiveButton("Let's Go") {
                        MainScope().launch(Dispatchers.IO) {
                            try {
                                client.get("https://api.github.com/repos/$repo/releases/tags/v$version").text.apply {
                                    substringAfter("\"browser_download_url\":\"").substringBefore('"').apply {
                                        if (endsWith("apk")) activity.downloadUpdate(this)
                                        else openLinkInBrowser("https://github.com/$repo/releases/tags/v$version")
                                    }
                                }
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                    }
                    setNegativeButton("Cope") {
                        dismiss()
                    }
                    show(activity.supportFragmentManager, "dialog")
                }
            }
        }
    }

    private fun compareVersion(version: String): Boolean {
        return try {
            version.replace(".", "").toInt() > BuildConfig.VERSION_NAME.replace(".", "").toInt()
        } catch (e: Exception) {
            false
        }
    }


    //Blatantly kanged from https://github.com/LagradOst/CloudStream-3/blob/master/app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt
    private fun Activity.downloadUpdate(url: String): Boolean {
        val downloadManager = this.getSystemService<DownloadManager>()!!

        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("Saikou Update")
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "Saikou.apk"
            )
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            logError(e)
            -1
        }
        if (id == -1L) return true
        registerReceiver(
            object : BroadcastReceiver() {
                @SuppressLint("Range")
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        val downloadId = intent?.getLongExtra(
                            DownloadManager.EXTRA_DOWNLOAD_ID, id
                        ) ?: id

                        val query = DownloadManager.Query()
                        query.setFilterById(downloadId)
                        val c = downloadManager.query(query)

                        if (c.moveToFirst()) {
                            val columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (DownloadManager.STATUS_SUCCESSFUL == c
                                    .getInt(columnIndex)
                            ) {
                                c.getColumnIndex(DownloadManager.COLUMN_MEDIAPROVIDER_URI)
                                val uri = Uri.parse(
                                    c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                                )
                                openApk(this@downloadUpdate, uri)
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
        return true
    }

    fun openApk(context: Context, uri: Uri) {
        try {
            uri.path?.let {
                val contentUri = FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".provider",
                    File(it)
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    data = contentUri
                }
                context.startActivity(installIntent)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }
}