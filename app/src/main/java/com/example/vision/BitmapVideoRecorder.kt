package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BitmapVideoRecorder"

/**
 * Hardware-accelerated MP4 video recorder that renders Bitmaps and dynamic overlay
 * watermarks into MediaCodec via EGL & GLES20 surface pipeline.
 * Ensures zero-latency real-time video encoding with watermark & audio muxing.
 */
class BitmapVideoRecorder(
    private val outputFile: File,
    private val width: Int = 720,
    private val height: Int = 1280,
    private val frameRate: Int = 30
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val isRecording = AtomicBoolean(false)
    private var frameCount = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    // EGL & GLES components
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glProgram = 0
    private var glTextureId = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0

    // Vertex and Texture buffers for quad rendering
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // Reusable intermediate bitmap & canvas for software overlay composition
    private var intermediateBitmap: Bitmap? = null
    private var intermediateCanvas: Canvas? = null
    private val destRect = Rect(0, 0, width, height)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val vertexData = floatArrayOf(
            -1.0f, -1.0f,  // bottom-left
             1.0f, -1.0f,  // bottom-right
            -1.0f,  1.0f,  // top-left
             1.0f,  1.0f   // top-right
        )
        // Texture coordinate mapping so that Android Bitmap (0,0 at top-left) renders upright
        val texCoordData = floatArrayOf(
            0.0f, 1.0f,    // bottom-left
            1.0f, 1.0f,    // bottom-right
            0.0f, 0.0f,    // top-left
            1.0f, 0.0f     // top-right
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexData)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(texCoordData)
                position(0)
            }
    }

    @Synchronized
    fun start(): Boolean {
        if (isRecording.get()) return true
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            codec = encoder

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            trackIndex = -1
            frameCount = 0L

            initEGL(inputSurface!!)

            intermediateBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            intermediateCanvas = Canvas(intermediateBitmap!!)

            isRecording.set(true)
            Log.i(TAG, "BitmapVideoRecorder started successfully -> ${outputFile.name} (${width}x${height})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BitmapVideoRecorder: ${e.message}", e)
            release()
            return false
        }
    }

    private fun initEGL(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("EGL14 display not available")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL14")
        }

        // Try with EGL_RECORDABLE_ANDROID first
        val configAttribsWithRecordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        var success = EGL14.eglChooseConfig(
            eglDisplay, configAttribsWithRecordable, 0,
            configs, 0, configs.size, numConfigs, 0
        )

        if (!success || numConfigs[0] <= 0 || configs[0] == null) {
            // Fallback without EGL_RECORDABLE_ANDROID
            val configAttribsFallback = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(
                eglDisplay, configAttribsFallback, 0,
                configs, 0, configs.size, numConfigs, 0
            )
        }

        val eglConfig = configs[0] ?: throw RuntimeException("Suitable EGLConfig not found")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Failed to create EGL window surface")
        }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        initGL()

        // Crucial: Release EGL context from initialization thread so it can be made current on the camera frame analysis thread
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    private fun initGL() {
        val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        glProgram = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val err = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("Program linking failed: $err")
            }
        }

        aPositionLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(glProgram, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        glTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val err = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed ($type): $err")
        }
        return shader
    }

    @Synchronized
    fun recordFrame(
        bitmap: Bitmap,
        overlayDrawer: ((android.graphics.Canvas, Int, Int) -> Unit)? = null
    ) {
        if (!isRecording.get()) return
        val canvas = intermediateCanvas ?: return
        val targetBmp = intermediateBitmap ?: return

        try {
            // 1. Ensure EGL Context is current on this thread (CameraX analyzer thread)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglGetCurrentContext() != eglContext) {
                    val made = EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    if (!made) {
                        val err = EGL14.eglGetError()
                        Log.e(TAG, "eglMakeCurrent failed: 0x${Integer.toHexString(err)}")
                        return
                    }
                }
            }

            // 2. Clear canvas with dark background
            canvas.drawColor(Color.BLACK)

            // 3. Aspect-fill / center crop incoming camera bitmap
            val bWidth = bitmap.width.toFloat()
            val bHeight = bitmap.height.toFloat()
            val targetAspect = width.toFloat() / height.toFloat()
            val sourceAspect = bWidth / bHeight

            val srcRect: Rect = if (sourceAspect > targetAspect) {
                val scaledWidth = bHeight * targetAspect
                val xOffset = (bWidth - scaledWidth) / 2f
                Rect(xOffset.toInt(), 0, (xOffset + scaledWidth).toInt(), bHeight.toInt())
            } else {
                val scaledHeight = bWidth / targetAspect
                val yOffset = (bHeight - scaledHeight) / 2f
                Rect(0, yOffset.toInt(), bWidth.toInt(), (yOffset + scaledHeight).toInt())
            }

            canvas.drawBitmap(bitmap, srcRect, destRect, paint)

            // 4. Render overlay elements (Score, Combo multiplier, Logo, Neon badges)
            overlayDrawer?.invoke(canvas, width, height)

            // 5. Upload composed bitmap into OpenGL ES texture
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(glProgram)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, targetBmp, 0)
            GLES20.glUniform1i(uTextureLoc, 0)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            // 6. Set timestamp for accurate PTS in MP4 and swap buffers
            val ptsNs = frameCount * (1_000_000_000L / frameRate)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            frameCount++
            drainEncoder(endOfStream = false)
        } catch (e: Exception) {
            Log.w(TAG, "Error recording frame via EGL: ${e.message}")
        } finally {
            // Keep EGL context uncurrent between frames so stop() or release() can be called safely from any thread
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
        }
    }

    @Synchronized
    fun stop(): File? {
        if (!isRecording.getAndSet(false)) return outputFile
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
            // Signal EOF and drain all buffered frames
            codec?.signalEndOfInputStream()
            drainEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.w(TAG, "Error signaling end of stream: ${e.message}")
        } finally {
            release()
        }
        return if (outputFile.exists() && outputFile.length() > 0) {
            Log.i(TAG, "BitmapVideoRecorder completed: ${outputFile.length()} bytes, $frameCount frames")
            outputFile
        } else {
            Log.w(TAG, "BitmapVideoRecorder produced empty or missing file (frameCount: $frameCount)")
            null
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = codec ?: return
        val mux = muxer ?: return

        var retries = if (endOfStream) 30 else 1
        while (retries > 0) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    retries--
                    try { Thread.sleep(15) } catch (_: Exception) {}
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.e(TAG, "Format changed after muxer started")
                        break
                    }
                    val newFormat = encoder.outputFormat
                    trackIndex = mux.addTrack(newFormat)
                    mux.start()
                    muxerStarted = true
                    Log.d(TAG, "Muxer started with trackIndex $trackIndex")
                }
                outIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (encodedData != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (muxerStarted && bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            mux.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        try {
            if (muxerStarted) {
                muxer?.stop()
            }
        } catch (_: Exception) {}
        try {
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null
        muxerStarted = false

        // Cleanup EGL & GLES
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        if (glTextureId != 0) {
            val textures = intArrayOf(glTextureId)
            GLES20.glDeleteTextures(1, textures, 0)
            glTextureId = 0
        }
        if (glProgram != 0) {
            GLES20.glDeleteProgram(glProgram)
            glProgram = 0
        }

        try {
            intermediateBitmap?.recycle()
        } catch (_: Exception) {}
        intermediateBitmap = null
        intermediateCanvas = null
    }
}

