package com.example.vision

data class PointTargetRender(
    val id: Long,
    val number: Int,
    val xNorm: Float,
    val yNorm: Float,
    val isHit: Boolean,
    val isCurrentTarget: Boolean
)

data class PointsVideoOverlayData(
    val playerName: String = "JUGADOR",
    val score: Int = 0,
    val timerRemainingSec: Int = 60,
    val comboText: String? = null,
    val comboMultiplier: Int = 1,
    val activePoints: List<PointTargetRender> = emptyList(),
    val isFlashActive: Boolean = false,
    val isRecording: Boolean = true
)
