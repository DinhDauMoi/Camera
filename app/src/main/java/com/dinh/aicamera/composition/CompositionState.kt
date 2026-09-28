package com.dinh.aicamera.composition

import android.graphics.PointF
import android.graphics.RectF

data class CompositionState(
    val hasSubject: Boolean = false,
    val isFace: Boolean = false,
    val subjectBounds: RectF = RectF(),
    val subjectCenter: PointF = PointF(),
    val targetPoint: PointF = PointF(),
    val distanceToTarget: Float = 0f,
    val rollAngle: Float = 0f,
    val pitchAngle: Float = 0f,
    val score: Int = 0,
    val guidanceText: String = "",
    val isAutoCaptureReady: Boolean = false,
    val autoCaptureProgress: Float = 0f,
    val gridThirdsHorizontal: FloatArray = floatArrayOf(0f, 0f),
    val gridThirdsVertical: FloatArray = floatArrayOf(0f, 0f)
)
