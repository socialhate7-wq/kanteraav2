package com.example.vision

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 1-Click Native Social Sharing Helper for Instagram Stories and WhatsApp
 * compliant with Android native intent specifications.
 */
object SocialShareHelper {
    private const val TAG = "SocialShareHelper"

    fun shareToInstagramStory(context: Context, videoFile: File, score: Int) {
        if (!videoFile.exists()) {
            Toast.makeText(context, "El vídeo no está disponible", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )

            val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                setDataAndType(contentUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("content_url", "https://kantera.ai")
                putExtra("top_background_color", "#0B0B0E")
                putExtra("bottom_background_color", "#FF6D00")
                setPackage("com.instagram.android")
            }

            // Verify if Instagram is installed
            val canResolve = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            if (canResolve) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Instagram no está instalado, abriendo compartir...", Toast.LENGTH_SHORT).show()
                fallbackShare(context, contentUri, "Compartir en Instagram Stories / Redes", score)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Instagram story: ${e.message}", e)
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )
            fallbackShare(context, contentUri, "Compartir vídeo", score)
        }
    }

    fun shareToInstagramStories(context: Context, videoFile: File, score: Int) {
        shareToInstagramStory(context, videoFile, score)
    }

    fun shareToWhatsApp(context: Context, videoFile: File, score: Int) {
        if (!videoFile.exists()) {
            Toast.makeText(context, "El vídeo no está disponible", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )

            val shareText = "🏀 ¡Nuevo récord de $score puntos en Reaction Points con Kantera AI! 🔥 Entrenando bote y agilidad mental."

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.whatsapp")
            }

            val canResolve = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            if (canResolve) {
                context.startActivity(intent)
            } else {
                // Try WhatsApp Business as well
                intent.setPackage("com.whatsapp.w4b")
                val canResolveBiz = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
                if (canResolveBiz) {
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "WhatsApp no está instalado, abriendo compartir...", Toast.LENGTH_SHORT).show()
                    fallbackShare(context, contentUri, "Compartir por WhatsApp / Mensajes", score)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp share: ${e.message}", e)
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )
            fallbackShare(context, contentUri, "Compartir por WhatsApp", score)
        }
    }

    fun shareGeneric(context: Context, videoFile: File, score: Int) {
        if (!videoFile.exists()) {
            Toast.makeText(context, "El vídeo no está disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )
        fallbackShare(context, contentUri, "Compartir Highlights Kantera AI", score)
    }

    private fun fallbackShare(context: Context, uri: Uri, title: String, score: Int) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "🏀 ¡Mira mis highlights en Reaction Points con Kantera AI! Puntuación: $score puntos 🔥"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun playVideo(context: Context, videoFile: File) {
        if (!videoFile.exists() || videoFile.length() <= 0L) {
            Toast.makeText(context, "El vídeo no está disponible o aún se está procesando", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Reproducir jugada").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening video: ${e.message}", e)
            Toast.makeText(context, "No se encontró reproductor de vídeo", Toast.LENGTH_SHORT).show()
        }
    }
}
