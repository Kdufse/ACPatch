package me.yuki.apatch.ui.theme

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 背景配置管理类
 */
object BackgroundConfig {
    var customBackgroundUri: String? by mutableStateOf(null)
        private set
    var isCustomBackgroundEnabled: Boolean by mutableStateOf(false)
        private set
    
    private const val PREFS_NAME = "background_settings"
    private const val KEY_CUSTOM_BACKGROUND_URI = "custom_background_uri"
    private const val KEY_CUSTOM_BACKGROUND_ENABLED = "custom_background_enabled"
    private const val TAG = "BackgroundConfig"
    
    /**
     * 更新自定义背景URI
     */
    fun updateCustomBackgroundUri(uri: String?) {
        customBackgroundUri = uri
        isCustomBackgroundEnabled = uri != null
    }
    
    /**
     * 启用/禁用自定义背景
     */
    fun setCustomBackgroundEnabledState(enabled: Boolean) {
        isCustomBackgroundEnabled = enabled
    }
    
    /**
     * 保存配置到SharedPreferences
     */
    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_CUSTOM_BACKGROUND_URI, customBackgroundUri)
            putBoolean(KEY_CUSTOM_BACKGROUND_ENABLED, isCustomBackgroundEnabled)
            apply()
        }
    }
    
    /**
     * 从SharedPreferences加载配置
     */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uri = prefs.getString(KEY_CUSTOM_BACKGROUND_URI, null)
        val enabled = prefs.getBoolean(KEY_CUSTOM_BACKGROUND_ENABLED, false)
        
        Log.d(TAG, "加载背景配置: URI=$uri, enabled=$enabled")
        
        customBackgroundUri = uri
        isCustomBackgroundEnabled = enabled
    }
    
    /**
     * 重置配置
     */
    fun reset() {
        customBackgroundUri = null
        isCustomBackgroundEnabled = false
    }
}

/**
 * 背景管理器
 */
object BackgroundManager {
    private const val TAG = "BackgroundManager"
    private const val APATCH_EXTERNAL_DIR = "Apatch"
    
    /**
     * 获取外部存储目录
     */
    private fun getExternalStorageDir(context: Context): File? {
        return try {
            // 使用应用专用的外部存储目录
            val externalDir = File(context.getExternalFilesDir(null), APATCH_EXTERNAL_DIR)
            if (!externalDir.exists()) {
                externalDir.mkdirs()
            }
            Log.d(TAG, "外部存储目录: ${externalDir.absolutePath}")
            externalDir
        } catch (e: Exception) {
            Log.e(TAG, "获取外部存储目录失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 保存并应用自定义背景
     */
    suspend fun saveAndApplyCustomBackground(context: Context, uri: Uri): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val externalUri = copyImageToExternalStorage(context, uri)
                if (externalUri != null) {
                    Log.d(TAG, "图片复制成功，外部URI: $externalUri")
                    BackgroundConfig.updateCustomBackgroundUri(externalUri.toString())
                    BackgroundConfig.save(context)
                    Log.d(TAG, "背景配置保存成功，URI: ${BackgroundConfig.customBackgroundUri}, 启用状态: ${BackgroundConfig.isCustomBackgroundEnabled}")
                    true
                } else {
                    Log.e(TAG, "图片复制失败")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存自定义背景失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 清除自定义背景
     */
    fun clearCustomBackground(context: Context) {
        try {
            // 删除外部存储的背景文件
            BackgroundConfig.customBackgroundUri?.let { uriString ->
                val uri = Uri.parse(uriString)
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
            
            // 重置配置
            BackgroundConfig.reset()
            BackgroundConfig.save(context)
        } catch (e: Exception) {
            Log.e(TAG, "清除自定义背景失败: ${e.message}", e)
        }
    }
    
    /**
     * 加载自定义背景
     */
    fun loadCustomBackground(context: Context) {
        BackgroundConfig.load(context)
    }
    
    /**
     * 复制图片到外部存储
     */
    private suspend fun copyImageToExternalStorage(context: Context, uri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val apatchDir = getExternalStorageDir(context) ?: return@withContext null
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val fileName = "custom_background_${System.currentTimeMillis()}.jpg"
                val file = File(apatchDir, fileName)

                FileOutputStream(file).use { outputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
                inputStream.close()

                // 直接返回文件URI
                val fileUri = Uri.fromFile(file)
                Log.d(TAG, "图片复制成功，文件URI: $fileUri")
                fileUri
            } catch (e: Exception) {
                Log.e(TAG, "复制图片到外部存储失败: ${e.message}", e)
                null
            }
        }
    }
}