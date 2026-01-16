package me.bmax.apatch.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.bmax.apatch.ui.theme.SoundEffectConfig
import java.io.File

object SoundEffectManager {
    private const val TAG = "SoundEffectManager"
    private var mediaPlayer: MediaPlayer? = null
    
    // Use Main dispatcher as MediaPlayer must be created/accessed on same thread or handle synch
    // But prepare can be async.
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    fun play(context: Context) {
        if (!SoundEffectConfig.isSoundEffectEnabled) return
        
        // Scope check is done by the caller (onClick listener)
        
        val filename = SoundEffectConfig.soundEffectFilename ?: return
        val file = File(SoundEffectConfig.getSoundEffectDir(context), filename)
        
        if (!file.exists()) return

        scope.launch {
            try {
                if (mediaPlayer == null) {
                    mediaPlayer = MediaPlayer()
                } else {
                    mediaPlayer?.reset()
                }

                mediaPlayer?.apply {
                    setDataSource(context, Uri.fromFile(file))
                    setOnPreparedListener { mp ->
                        mp.start()
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: $what, $extra")
                        mp.reset()
                        true
                    }
                    prepareAsync() // Use async to avoid blocking UI
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play sound effect", e)
                mediaPlayer = null // Reset on hard failure
            }
        }
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
