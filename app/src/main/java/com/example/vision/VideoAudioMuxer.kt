package com.example.vision

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Native hardware-level remuxer that combines the watermarked MP4 video stream
 * with the urban basketball hip-hop audio track in ~1 second with zero video re-encoding.
 */
object VideoAudioMuxer {
    private const val TAG = "VideoAudioMuxer"

    fun muxAudioIntoVideo(
        context: Context,
        inputVideoFile: File,
        audioAssetPath: String = "audio/basketball_urban_beat.m4a",
        outputFile: File
    ): Boolean {
        if (!inputVideoFile.exists() || inputVideoFile.length() == 0L) {
            Log.w(TAG, "Input video does not exist or is empty")
            return false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var afd: AssetFileDescriptor? = null

        try {
            // 1. Video track extractor
            videoExtractor = MediaExtractor().apply {
                setDataSource(inputVideoFile.absolutePath)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                Log.e(TAG, "No video track found in ${inputVideoFile.name}")
                return false
            }
            videoExtractor.selectTrack(videoTrackIndex)

            val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                30_000_000L // Default 30s
            }

            // 2. Audio track extractor from assets
            try {
                afd = context.assets.openFd(audioAssetPath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not open audio asset: ${e.message}")
            }

            if (afd == null) {
                // Return video without music if asset not available
                inputVideoFile.copyTo(outputFile, overwrite = true)
                return true
            }

            audioExtractor = MediaExtractor().apply {
                setDataSource(afd!!.fileDescriptor, afd!!.startOffset, afd!!.length)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "No audio track found in asset, copying original video")
                inputVideoFile.copyTo(outputFile, overwrite = true)
                return true
            }
            audioExtractor.selectTrack(audioTrackIndex)

            // 3. MediaMuxer setup
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            // 4. Stream-copy video samples
            val maxVideoBuf = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
            } else 1024 * 1024
            val videoBuf = ByteBuffer.allocate(maxVideoBuf)
            val videoBufInfo = MediaCodec.BufferInfo()

            while (true) {
                videoBufInfo.offset = 0
                videoBufInfo.size = videoExtractor.readSampleData(videoBuf, 0)
                if (videoBufInfo.size < 0) {
                    break
                }
                videoBufInfo.presentationTimeUs = videoExtractor.sampleTime
                videoBufInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxVideoTrack, videoBuf, videoBufInfo)
                videoExtractor.advance()
            }

            // 5. Stream-copy audio samples (looping seamlessly to cover the full video duration)
            val maxAudioBuf = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else 256 * 1024
            val audioBuf = ByteBuffer.allocate(maxAudioBuf)
            val audioBufInfo = MediaCodec.BufferInfo()

            var audioPtsOffsetUs = 0L
            var currentAudioSampleTimeUs = 0L
            var loops = 0
            val maxLoops = 10 // Safety limit

            while (audioPtsOffsetUs + currentAudioSampleTimeUs < videoDurationUs && loops < maxLoops) {
                audioBufInfo.offset = 0
                audioBufInfo.size = audioExtractor.readSampleData(audioBuf, 0)
                if (audioBufInfo.size < 0) {
                    // Loop audio
                    audioPtsOffsetUs += (currentAudioSampleTimeUs + 20_000L)
                    audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    loops++
                    continue
                }
                currentAudioSampleTimeUs = audioExtractor.sampleTime
                val presentationTimeUs = audioPtsOffsetUs + currentAudioSampleTimeUs
                if (presentationTimeUs > videoDurationUs) {
                    break
                }
                audioBufInfo.presentationTimeUs = presentationTimeUs
                audioBufInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxAudioTrack, audioBuf, audioBufInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            Log.i(TAG, "Audio successfully muxed into video: ${outputFile.name}, size: ${outputFile.length()} bytes")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error muxing audio and video: ${e.message}", e)
            try {
                inputVideoFile.copyTo(outputFile, overwrite = true)
            } catch (_: Exception) {}
            return false
        } finally {
            try { afd?.close() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
