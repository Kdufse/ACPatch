package me.bmax.apatch.ui.theme

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

object SoundEffectConfig {
    private const val PREFS_NAME = "sound_effect_settings"
    private const val KEY_ENABLED = "sound_effect_enabled"
    private const val KEY_FILENAME = "sound_effect_filename"
    private const val KEY_SCOPE = "sound_effect_scope" // "global" or "bottom_bar"
    private const val TAG = "SoundEffectConfig"

    const val SCOPE_GLOBAL = "global"
    const val SCOPE_BOTTOM_BAR = "bottom_bar"

    var isSoundEffectEnabled: Boolean by mutableStateOf(false)
        private set

    var soundEffectFilename: String? by mutableStateOf(null)
        private set

    var scope: String by mutableStateOf(SCOPE_GLOBAL)
        private set

    fun setEnabledState(enabled: Boolean) {
        isSoundEffectEnabled = enabled
    }

    fun setFilenameValue(filename: String?) {
        soundEffectFilename = filename
    }

    fun setScopeValue(value: String) {
        scope = value
    }

    fun getSoundEffectDir(context: Context): File {
        val dir = File(context.filesDir, "sound_effects")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getSoundEffectFile(context: Context): File? {
        if (soundEffectFilename == null) return null
        return File(getSoundEffectDir(context), soundEffectFilename!!)
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isSoundEffectEnabled = prefs.getBoolean(KEY_ENABLED, false)
        soundEffectFilename = prefs.getString(KEY_FILENAME, null)
        scope = prefs.getString(KEY_SCOPE, SCOPE_GLOBAL) ?: SCOPE_GLOBAL

        // Validate if file exists
        if (isSoundEffectEnabled && soundEffectFilename != null) {
            val file = File(getSoundEffectDir(context), soundEffectFilename!!)
            if (!file.exists()) {
                isSoundEffectEnabled = false
                soundEffectFilename = null
                save(context)
            }
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, isSoundEffectEnabled)
            .putString(KEY_FILENAME, soundEffectFilename)
            .putString(KEY_SCOPE, scope)
            .apply()
    }

    fun saveSoundEffectFile(context: Context, uri: android.net.Uri): Boolean {
        return try {
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri)) ?: "mp3"
            val newFilename = "sound_effect_${System.currentTimeMillis()}.$extension"
            val oldFilename = soundEffectFilename
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                val file = File(getSoundEffectDir(context), newFilename)
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Delete old file if it exists and is different
            if (oldFilename != null && oldFilename != newFilename) {
                val oldFile = File(getSoundEffectDir(context), oldFilename)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }
            
            isSoundEffectEnabled = true
            soundEffectFilename = newFilename
            save(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sound effect file", e)
            false
        }
    }

    fun clearSoundEffect(context: Context) {
        val filename = soundEffectFilename
        if (filename != null) {
            val file = File(getSoundEffectDir(context), filename)
            if (file.exists()) {
                file.delete()
            }
        }
        isSoundEffectEnabled = false
        soundEffectFilename = null
        save(context)
    }
}
