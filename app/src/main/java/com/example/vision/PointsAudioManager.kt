package com.example.vision

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages playback of energetic urban basketball hip-hop sample beats
 * during the Reaction Points minigame.
 */
object PointsAudioManager {
    private const val TAG = "PointsAudioManager"
    private const val DEFAULT_AUDIO_ASSET = "audio/basketball_urban_beat.m4a"

    private var mediaPlayer: MediaPlayer? = null
    private val _isMusicPlaying = MutableStateFlow(false)
    val isMusicPlaying: StateFlow<Boolean> = _isMusicPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    fun startBeat(context: Context, assetPath: String = DEFAULT_AUDIO_ASSET) {
        if (_isMuted.value) {
            _isMusicPlaying.value = false
            return
        }
        try {
            stopBeat()
            var afd: AssetFileDescriptor? = null
            try {
                afd = context.assets.openFd(assetPath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not open asset $assetPath: ${e.message}")
            }

            if (afd != null) {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .build()
                    )
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    isLooping = true
                    setVolume(0.85f, 0.85f)
                    prepare()
                    start()
                }
                afd.close()
                _isMusicPlaying.value = true
                Log.i(TAG, "Urban basketball beat started playing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing urban basketball beat: ${e.message}", e)
            _isMusicPlaying.value = false
        }
    }

    fun stopBeat() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping media player: ${e.message}")
        } finally {
            mediaPlayer = null
            _isMusicPlaying.value = false
        }
    }

    fun pauseBeat() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    _isMusicPlaying.value = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing beat: ${e.message}")
        }
    }

    fun resumeBeat() {
        if (_isMuted.value) return
        try {
            mediaPlayer?.let { player ->
                if (!player.isPlaying) {
                    player.start()
                    _isMusicPlaying.value = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming beat: ${e.message}")
        }
    }

    fun toggleMute(context: Context) {
        val nextMuted = !_isMuted.value
        _isMuted.value = nextMuted
        if (nextMuted) {
            stopBeat()
        } else {
            startBeat(context)
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            stopBeat()
        }
    }
}
