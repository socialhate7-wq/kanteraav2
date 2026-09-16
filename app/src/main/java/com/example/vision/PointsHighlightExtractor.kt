package com.example.vision

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

data class HighlightClipInfo(
    val startMs: Long,
    val endMs: Long,
    val description: String,
    val scoreAtMoment: Int
)

/**
 * Extracts highlight clips or the final 15-second climax using Keyframe Remuxing.
 * Zero pixel re-encoding: copies I-frames and audio packets directly via MediaExtractor and MediaMuxer.
 * Completes in 1 to 2 seconds!
 */
object PointsHighlightExtractor {
    private const val TAG = "PointsHighlightExtractor"

    fun extractTrimmedClip(
        sourceFile: File,
        targetFile: File,
        startMs: Long,
        endMs: Long
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return false

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(sourceFile.absolutePath)
            }

            muxer = MediaMuxer(targetFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackCount = extractor.trackCount
            val trackMap = HashMap<Int, Int>()

            var videoTrack = -1
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxIndex = muxer.addTrack(format)
                    trackMap[i] = muxIndex
                    if (mime.startsWith("video/")) {
                        videoTrack = i
                    }
                }
            }

            if (videoTrack < 0) {
                Log.e(TAG, "No video track found in ${sourceFile.name}")
                return false
            }

            muxer.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            // Seek to previous sync frame (I-Frame / Keyframe) for instant clean cut
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            for (i in 0 until trackCount) {
                if (trackMap.containsKey(i)) {
                    extractor.selectTrack(i)
                }
            }

            var firstPtsUs: Long? = null

            while (true) {
                val currentTrack = extractor.sampleTrackIndex
                if (currentTrack < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) {
                    break
                }

                if (firstPtsUs == null) {
                    firstPtsUs = sampleTimeUs
                }

                val muxTrack = trackMap[currentTrack]
                if (muxTrack != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size > 0) {
                        // Re-base PTS to 0 for smooth playback
                        bufferInfo.presentationTimeUs = (sampleTimeUs - firstPtsUs).coerceAtLeast(0L)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxTrack, buffer, bufferInfo)
                    }
                }
                extractor.advance()
            }

            muxer.stop()
            Log.i(TAG, "Highlight clip extracted: ${targetFile.name}, size: ${targetFile.length()} bytes")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting highlight clip: ${e.message}", e)
            return false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
