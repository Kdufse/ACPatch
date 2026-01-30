package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import me.bmax.apatch.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val UPDATE_API_URL = "https://kdufse.github.io/api/update/version.acp"
    private const val UPDATE_URL = "https://github.com/Kdufse/ACPatch/releases"

    suspend fun checkUpdate(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(UPDATE_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
                    val rawResponse = reader.readText()
                    reader.close()
                    
                    val remoteVersionCodeStr = rawResponse.replace("\uFEFF", "").trim()
                    Log.d(TAG, "Raw response: '$rawResponse', Parsed string: '$remoteVersionCodeStr'")

                    val remoteVersionCode = remoteVersionCodeStr.toIntOrNull()
                    if (remoteVersionCode != null) {
                        Log.d(TAG, "Remote: $remoteVersionCode, Local: ${BuildConfig.VERSION_CODE}")
                        return@withContext remoteVersionCode > BuildConfig.VERSION_CODE
                    } else {
                        Log.e(TAG, "Failed to parse version code")
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Check update failed", e)
                false
            }
        }
    }

    fun openUpdateUrl(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
