package me.bmax.apatch.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import java.io.File

object FontConfig {
    private const val PREFS_NAME = "font_settings"
    private const val KEY_CUSTOM_FONT_ENABLED = "custom_font_enabled"
    private const val FONT_FILE_NAME = "custom_font.ttf"
    private const val TAG = "FontConfig"

    var isCustomFontEnabled: Boolean by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isCustomFontEnabled = prefs.getBoolean(KEY_CUSTOM_FONT_ENABLED, false)
        
        // Validate if file exists
        if (isCustomFontEnabled) {
            val file = File(context.filesDir, FONT_FILE_NAME)
            if (!file.exists()) {
                isCustomFontEnabled = false
                save(context)
            }
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CUSTOM_FONT_ENABLED, isCustomFontEnabled).apply()
    }

    fun saveFontFile(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val file = File(context.filesDir, FONT_FILE_NAME)
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            isCustomFontEnabled = true
            save(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save font file", e)
            false
        }
    }

    fun clearFont(context: Context) {
        val file = File(context.filesDir, FONT_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
        isCustomFontEnabled = false
        save(context)
    }

    fun getFontFamily(context: Context): FontFamily {
        if (isCustomFontEnabled) {
            val file = File(context.filesDir, FONT_FILE_NAME)
            if (file.exists()) {
                try {
                    val typeface = Typeface.createFromFile(file)
                    return FontFamily(typeface)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load custom font", e)
                }
            }
        }
        return FontFamily.Default
    }
}
