package com.example.vision

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * High-performance hardware Canvas drawer that burns the Kantera brand watermark,
 * live player stats, target rings, and combo effects into every video frame in real-time.
 * Zero post-processing required: The MP4 file is ready instantly when the session ends!
 */
class PointsVideoWatermarkDrawer {

    // Pre-allocated Paint objects to prevent allocations in the 30fps recording loop
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0B0B0E")
        style = Paint.Style.FILL
    }

    private val brandBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00") // Kantera Sport Orange
        style = Paint.Style.FILL
    }

    private val brandTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 32f
    }

    private val playerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Sport Cyan
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 24f
    }

    private val scoreBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6121217")
        style = Paint.Style.FILL
    }

    private val scoreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val scoreLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0A0B0")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 20f
    }

    private val scoreValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 42f
    }

    private val comboBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100")
        style = Paint.Style.FILL
    }

    private val comboTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 24f
    }

    private val timerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 28f
    }

    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val targetNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 40f
    }

    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.parseColor("#80FFD700") // Golden flash glow
    }

    private val bottomBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B308080C")
        style = Paint.Style.FILL
    }

    private val bottomTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDFFFFFF")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textSize = 22f
    }

    private val zeroPostBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }

    private val zeroPostTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }

    // Reusable rects
    private val headerRect = RectF()
    private val scoreRect = RectF()
    private val comboRect = RectF()
    private val bottomRect = RectF()
    private val zeroBadgeRect = RectF()

    fun draw(canvas: Canvas, width: Int, height: Int, data: PointsVideoOverlayData) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Flash effect on screen edges (when hit or combo occurs)
        if (data.isFlashActive) {
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }

        // 2. Active Reaction Target Circles (AR overlay on video)
        data.activePoints.forEach { point ->
            if (!point.isHit) {
                val cx = point.xNorm * w
                val cy = point.yNorm * h
                val radius = 48f

                val isNext = point.isCurrentTarget
                targetRingPaint.color = if (isNext) Color.parseColor("#00E5FF") else Color.parseColor("#FF6D00")
                targetRingPaint.strokeWidth = if (isNext) 8f else 5f

                targetFillPaint.color = if (isNext) Color.parseColor("#8800E5FF") else Color.parseColor("#66FF6D00")

                // Target glow & fill
                canvas.drawCircle(cx, cy, radius, targetFillPaint)
                canvas.drawCircle(cx, cy, radius, targetRingPaint)

                // Outer pulsing ring
                targetRingPaint.alpha = 100
                canvas.drawCircle(cx, cy, radius + 12f, targetRingPaint)
                targetRingPaint.alpha = 255

                // Target Number inside
                val textY = cy - ((targetNumberPaint.descent() + targetNumberPaint.ascent()) / 2)
                canvas.drawText("${point.number}", cx, textY, targetNumberPaint)
            }
        }

        // 3. Top Banner: Brand Watermark & Player Name
        headerRect.set(24f, 28f, w - 24f, 130f)
        canvas.drawRoundRect(headerRect, 22f, 22f, bgPaint)

        // Orange brand pill
        val brandPill = RectF(36f, 40f, 240f, 118f)
        canvas.drawRoundRect(brandPill, 16f, 16f, brandBadgePaint)
        canvas.drawText("🏀 KANTERA", 50f, 88f, brandTextPaint)

        // Player Name & Mode
        canvas.drawText("JUGADOR: ${data.playerName.uppercase()}", 260f, 74f, playerTextPaint)
        canvas.drawText("REACTION DRILL • TIEMPO REAL", 260f, 108f, scoreLabelPaint)

        // 4. Live Score Box (Top-Right of screen)
        scoreRect.set(w - 240f, 38f, w - 36f, 120f)
        canvas.drawRoundRect(scoreRect, 18f, 18f, scoreBoxPaint)
        canvas.drawRoundRect(scoreRect, 18f, 18f, scoreStrokePaint)
        canvas.drawText("PUNTOS", w - 225f, 66f, scoreLabelPaint)
        canvas.drawText("${data.score}", w - 225f, 110f, scoreValuePaint)

        // 5. Dynamic Combo Alert / Time Bonus
        data.comboText?.let { comboMsg ->
            val textWidth = comboTextPaint.measureText(comboMsg)
            val pillWidth = textWidth + 48f
            val pillLeft = (w - pillWidth) / 2f
            comboRect.set(pillLeft, 145f, pillLeft + pillWidth, 195f)
            canvas.drawRoundRect(comboRect, 16f, 16f, comboBadgePaint)
            canvas.drawText(comboMsg, pillLeft + 24f, 180f, comboTextPaint)
        }

        // 6. Bottom Banner: Timer + Zero Post-Processing Tag
        bottomRect.set(24f, h - 85f, w - 24f, h - 25f)
        canvas.drawRoundRect(bottomRect, 18f, 18f, bottomBarPaint)

        val minutes = data.timerRemainingSec / 60
        val seconds = data.timerRemainingSec % 60
        val timeString = String.format("⏱️ %02d:%02d", minutes, seconds)
        canvas.drawText(timeString, 45f, h - 47f, timerTextPaint)

        canvas.drawText("KANTERA AI • ZERO POST-PROCESSING", 210f, h - 49f, bottomTextPaint)

        // Green "0s" instant badge
        zeroBadgeRect.set(w - 150f, h - 77f, w - 38f, h - 33f)
        canvas.drawRoundRect(zeroBadgeRect, 12f, 12f, zeroPostBadgePaint)
        canvas.drawText("⚡ 0s WAIT", w - 94f, h - 47f, zeroPostTextPaint)
    }
}
