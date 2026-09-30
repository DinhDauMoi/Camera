package com.dinh.aicamera.overlay

import android.graphics.PointF

data class PoseJoints(
    val headCenter: PointF,
    val headRadius: Float,
    val torsoNeck: PointF,
    val torsoHip: PointF,
    val torsoWidth: Float,
    val torsoRadius: Float = 14f,
    val leftArm: List<PointF>,
    val rightArm: List<PointF>,
    val leftLeg: List<PointF>,
    val rightLeg: List<PointF>
)

enum class PoseType(val displayName: String, val joints: PoseJoints) {
    STANDING(
        "Đứng thẳng",
        PoseJoints(
            headCenter = PointF(0f, 0.065f),
            headRadius = 0.065f,
            torsoNeck = PointF(0f, 0.14f),
            torsoHip = PointF(0f, 0.48f),
            torsoWidth = 0.14f,
            leftArm = listOf(PointF(-0.07f, 0.16f), PointF(-0.14f, 0.50f)),
            rightArm = listOf(PointF(0.07f, 0.16f), PointF(0.14f, 0.50f)),
            leftLeg = listOf(PointF(-0.035f, 0.48f), PointF(-0.045f, 1.0f)),
            rightLeg = listOf(PointF(0.035f, 0.48f), PointF(0.045f, 1.0f))
        )
    ),
    SITTING(
        "Ngồi",
        PoseJoints(
            headCenter = PointF(0f, 0.16f),
            headRadius = 0.065f,
            torsoNeck = PointF(0f, 0.235f),
            torsoHip = PointF(0f, 0.55f),
            torsoWidth = 0.14f,
            leftArm = listOf(PointF(-0.07f, 0.26f), PointF(-0.11f, 0.42f), PointF(-0.06f, 0.56f)),
            rightArm = listOf(PointF(0.07f, 0.26f), PointF(0.11f, 0.42f), PointF(0.06f, 0.56f)),
            leftLeg = listOf(PointF(-0.035f, 0.55f), PointF(-0.16f, 0.58f), PointF(-0.16f, 0.94f)),
            rightLeg = listOf(PointF(0.035f, 0.55f), PointF(0.16f, 0.58f), PointF(0.16f, 0.94f))
        )
    ),
    LEANING(
        "Dựa nghiêng",
        PoseJoints(
            headCenter = PointF(-0.05f, 0.08f),
            headRadius = 0.065f,
            torsoNeck = PointF(-0.04f, 0.155f),
            torsoHip = PointF(0.04f, 0.48f),
            torsoWidth = 0.14f,
            leftArm = listOf(PointF(-0.10f, 0.17f), PointF(-0.16f, 0.35f), PointF(-0.13f, 0.50f)),
            rightArm = listOf(PointF(0.02f, 0.17f), PointF(0.11f, 0.36f), PointF(0.13f, 0.52f)),
            leftLeg = listOf(PointF(0.01f, 0.48f), PointF(-0.02f, 0.74f), PointF(-0.04f, 1.0f)),
            rightLeg = listOf(PointF(0.07f, 0.48f), PointF(0.13f, 0.74f), PointF(0.17f, 0.98f))
        )
    ),
    ARMS_UP(
        "Tay giơ cao",
        PoseJoints(
            headCenter = PointF(0f, 0.10f),
            headRadius = 0.065f,
            torsoNeck = PointF(0f, 0.175f),
            torsoHip = PointF(0f, 0.50f),
            torsoWidth = 0.14f,
            leftArm = listOf(PointF(-0.07f, 0.20f), PointF(-0.16f, 0.08f), PointF(-0.22f, -0.02f)),
            rightArm = listOf(PointF(0.07f, 0.20f), PointF(0.16f, 0.08f), PointF(0.22f, -0.02f)),
            leftLeg = listOf(PointF(-0.035f, 0.50f), PointF(-0.08f, 0.75f), PointF(-0.10f, 1.0f)),
            rightLeg = listOf(PointF(0.035f, 0.50f), PointF(0.08f, 0.75f), PointF(0.10f, 1.0f))
        )
    ),
    WALKING(
        "Bước đi",
        PoseJoints(
            headCenter = PointF(0f, 0.065f),
            headRadius = 0.065f,
            torsoNeck = PointF(0f, 0.14f),
            torsoHip = PointF(0f, 0.48f),
            torsoWidth = 0.14f,
            leftArm = listOf(PointF(-0.07f, 0.16f), PointF(-0.14f, 0.32f), PointF(-0.16f, 0.46f)),
            rightArm = listOf(PointF(0.07f, 0.16f), PointF(0.13f, 0.33f), PointF(0.16f, 0.49f)),
            leftLeg = listOf(PointF(-0.035f, 0.48f), PointF(-0.11f, 0.74f), PointF(-0.17f, 1.0f)),
            rightLeg = listOf(PointF(0.035f, 0.48f), PointF(0.08f, 0.72f), PointF(0.15f, 0.98f))
        )
    ),
    HALF_BODY(
        "Nửa người",
        PoseJoints(
            headCenter = PointF(0f, 0.14f),
            headRadius = 0.11f,
            torsoNeck = PointF(0f, 0.26f),
            torsoHip = PointF(0f, 0.88f),
            torsoWidth = 0.28f,
            torsoRadius = 20f,
            leftArm = listOf(PointF(-0.14f, 0.32f), PointF(-0.25f, 0.60f), PointF(-0.22f, 0.90f)),
            rightArm = listOf(PointF(0.14f, 0.32f), PointF(0.25f, 0.60f), PointF(0.22f, 0.90f)),
            leftLeg = emptyList(),
            rightLeg = emptyList()
        )
    )
}
