package com.dinh.aicamera.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.dinh.aicamera.R
import com.dinh.aicamera.composition.AiStage
import com.dinh.aicamera.composition.CompositionState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isAiEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var isGridEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var currentState: CompositionState = CompositionState()

    // Smooth animated values
    private var animatedScore: Float = 0f
    private var scoreAnimator: ValueAnimator? = null

    // Target point lerp
    private var targetX: Float = 0f
    private var targetY: Float = 0f

    // Scanning radar pulse animation
    private var scanAngle: Float = 0f

    // Paints
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.grid_line)
        strokeWidth = 1.2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_gold)
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val targetGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_gold_glow)
        strokeWidth = 6f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val subjectBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white_70)
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_gold)
        strokeWidth = 2.2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_gold)
        style = Paint.Style.FILL
    }

    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val scoreBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_surface_dark)
        style = Paint.Style.FILL
    }

    private val scoreTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white_20)
        strokeWidth = 3.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val scoreProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scoreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val guidanceBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_surface_dark)
        style = Paint.Style.FILL
    }

    private val guidanceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_stroke)
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val guidanceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val autoCaptureRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.score_green)
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scanPulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_gold_glow)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    fun updateState(state: CompositionState) {
        val oldScore = currentState.score
        currentState = state

        if (!isAiEnabled) {
            postInvalidateOnAnimation()
            return
        }

        if (state.stage == AiStage.ALIGNED && oldScore != state.score) {
            scoreAnimator?.cancel()
            scoreAnimator = ValueAnimator.ofFloat(animatedScore, state.score.toFloat()).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animatedScore = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Vẽ lưới 1/3 (tự hiện khi AI BẬT hoặc khi bật toggle Lưới riêng)
        val shouldDrawGrid = isAiEnabled || isGridEnabled
        if (shouldDrawGrid) {
            drawRuleOfThirdsGrid(canvas, w, h)
        }

        // Nếu AI TẮT: dừng vẽ toàn bộ AR, trả về camera thường sạch sẽ
        if (!isAiEnabled) return

        // Thước cân bằng chân trời
        drawHorizonIndicator(canvas, w, h)

        // Phân nhánh vẽ theo 3 Bước rõ ràng:
        when (currentState.stage) {
            AiStage.SCANNING -> {
                // Bước 1: Quét khung hình nhẹ nhàng (radar pulse), chưa hiện gợi ý dồn dập
                drawScanningEffect(canvas, w, h)
                drawGuidancePill(canvas, w, "Đang quét khung hình...")
            }

            AiStage.GUIDING -> {
                // Bước 2: Hiện vòng tròn đích vàng + mũi tên chỉ hướng lia máy
                drawTargetGoldenRing(canvas)
                drawSubjectReticle(canvas, isLocked = false)
                drawDirectionalArrow(canvas)
                drawGuidancePill(canvas, w, currentState.guidanceText)
            }

            AiStage.ALIGNED -> {
                // Bước 3: Đã vào vùng đích -> hiện vòng đích khóa vàng, vòng điểm số cố định góc phải
                drawTargetGoldenRing(canvas)
                drawSubjectReticle(canvas, isLocked = true)
                drawScoreRing(canvas, w)
                drawGuidancePill(canvas, w, currentState.guidanceText)
            }
        }
    }

    private fun drawRuleOfThirdsGrid(canvas: Canvas, w: Float, h: Float) {
        val x1 = w / 3f
        val x2 = w * 2f / 3f
        val y1 = h / 3f
        val y2 = h * 2f / 3f

        canvas.drawLine(x1, 0f, x1, h, gridPaint)
        canvas.drawLine(x2, 0f, x2, h, gridPaint)
        canvas.drawLine(0f, y1, w, y1, gridPaint)
        canvas.drawLine(0f, y2, w, y2, gridPaint)
    }

    private fun drawHorizonIndicator(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val lineLen = 32f * resources.displayMetrics.density
        val roll = currentState.rollAngle

        val isLevel = kotlin.math.abs(roll) <= 1.5f
        val color = if (isLevel) {
            ContextCompat.getColor(context, R.color.score_green)
        } else {
            ContextCompat.getColor(context, R.color.white_50)
        }
        horizonPaint.color = color

        canvas.save()
        canvas.rotate(-roll, cx, cy)

        val gap = 20f * resources.displayMetrics.density
        canvas.drawLine(cx - gap - lineLen, cy, cx - gap, cy, horizonPaint)
        canvas.drawLine(cx + gap, cy, cx + gap + lineLen, cy, horizonPaint)
        canvas.drawCircle(cx, cy, 2.5f * resources.displayMetrics.density, horizonPaint)

        canvas.restore()
    }

    private fun drawScanningEffect(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        scanAngle = (scanAngle + 3f) % 360f

        val radius = 45f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, radius, scanPulsePaint)
        canvas.drawCircle(cx, cy, radius * 0.5f, scanPulsePaint)

        // Dấu cộng tâm
        val crossLen = 10f * resources.displayMetrics.density
        canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, scanPulsePaint)
        canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, scanPulsePaint)

        postInvalidateOnAnimation()
    }

    private fun drawTargetGoldenRing(canvas: Canvas) {
        val pt = currentState.targetPoint
        if (pt.x <= 0f && pt.y <= 0f) return

        targetX += 0.25f * (pt.x - targetX)
        targetY += 0.25f * (pt.y - targetY)

        val radius = 18f * resources.displayMetrics.density
        canvas.drawCircle(targetX, targetY, radius + 4f, targetGlowPaint)
        canvas.drawCircle(targetX, targetY, radius, targetRingPaint)
        canvas.drawCircle(targetX, targetY, 2f * resources.displayMetrics.density, targetRingPaint)
    }

    private fun drawSubjectReticle(canvas: Canvas, isLocked: Boolean) {
        val box = currentState.subjectBounds
        if (box.isEmpty) return

        val cornerLen = 14f * resources.displayMetrics.density
        val radius = 10f * resources.displayMetrics.density

        val path = Path()
        // Top-Left
        path.moveTo(box.left, box.top + cornerLen)
        path.lineTo(box.left, box.top + radius)
        path.quadTo(box.left, box.top, box.left + radius, box.top)
        path.lineTo(box.left + cornerLen, box.top)

        // Top-Right
        path.moveTo(box.right - cornerLen, box.top)
        path.lineTo(box.right - radius, box.top)
        path.quadTo(box.right, box.top, box.right + radius, box.top)
        path.lineTo(box.right, box.top + cornerLen)

        // Bottom-Right
        path.moveTo(box.right, box.bottom - cornerLen)
        path.lineTo(box.right, box.bottom - radius)
        path.quadTo(box.right, box.bottom, box.right - radius, box.bottom)
        path.lineTo(box.right - cornerLen, box.bottom)

        // Bottom-Left
        path.moveTo(box.left + cornerLen, box.bottom)
        path.lineTo(box.left + radius, box.bottom)
        path.quadTo(box.left, box.bottom, box.left + radius, box.bottom)
        path.lineTo(box.left, box.bottom - cornerLen)

        if (isLocked) {
            subjectBoxPaint.color = ContextCompat.getColor(context, R.color.accent_gold)
            subjectBoxPaint.strokeWidth = 2.2f * resources.displayMetrics.density
        } else {
            subjectBoxPaint.color = ContextCompat.getColor(context, R.color.white_70)
            subjectBoxPaint.strokeWidth = 1.5f * resources.displayMetrics.density
        }
        canvas.drawPath(path, subjectBoxPaint)
    }

    private fun drawDirectionalArrow(canvas: Canvas) {
        val from = currentState.subjectCenter
        val to = currentState.targetPoint
        val dist = hypot(to.x - from.x, to.y - from.y)

        val threshold = 30f * resources.displayMetrics.density
        if (dist <= threshold) return

        val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()).toFloat()

        val startGap = 20f * resources.displayMetrics.density
        val endGap = 22f * resources.displayMetrics.density

        val startX = from.x + cos(angle.toDouble()).toFloat() * startGap
        val startY = from.y + sin(angle.toDouble()).toFloat() * startGap
        val endX = to.x - cos(angle.toDouble()).toFloat() * endGap
        val endY = to.y - sin(angle.toDouble()).toFloat() * endGap

        canvas.drawLine(startX, startY, endX, endY, arrowPaint)

        // Đầu mũi tên tam giác
        val headSize = 9f * resources.displayMetrics.density
        val headPath = Path()
        headPath.moveTo(endX, endY)
        headPath.lineTo(
            endX - headSize * cos((angle - Math.PI / 6).toDouble()).toFloat(),
            endY - headSize * sin((angle - Math.PI / 6).toDouble()).toFloat()
        )
        headPath.lineTo(
            endX - headSize * cos((angle + Math.PI / 6).toDouble()).toFloat(),
            endY - headSize * sin((angle + Math.PI / 6).toDouble()).toFloat()
        )
        headPath.close()

        canvas.drawPath(headPath, arrowHeadPaint)
    }

    // VÒNG ĐIỂM SỐ CỐ ĐỊNH Ở GÓC PHẢI TRÊN (KHÔNG ĐÈ LÊN BẤT KỲ GỢI Ý NÀO)
    private fun drawScoreRing(canvas: Canvas, w: Float) {
        val dp = resources.displayMetrics.density
        val ringRadius = 24f * dp
        val cx = w - ringRadius - 20f * dp
        val cy = 115f * dp

        // Nền kính mờ
        canvas.drawCircle(cx, cy, ringRadius + 4f * dp, scoreBgPaint)
        canvas.drawCircle(cx, cy, ringRadius + 4f * dp, guidanceStrokePaint)
        canvas.drawCircle(cx, cy, ringRadius, scoreTrackPaint)

        val score = animatedScore.toInt()
        val color = when {
            score >= 85 -> ContextCompat.getColor(context, R.color.score_green)
            score >= 65 -> ContextCompat.getColor(context, R.color.accent_gold)
            score >= 40 -> ContextCompat.getColor(context, R.color.score_yellow)
            else -> ContextCompat.getColor(context, R.color.score_red)
        }
        scoreProgressPaint.color = color

        val sweepAngle = (animatedScore / 100f) * 360f
        val oval = RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        canvas.drawArc(oval, -90f, sweepAngle, false, scoreProgressPaint)

        // Vòng tiến trình 1s giữ yên để chụp
        if (currentState.autoCaptureProgress > 0f) {
            val autoOval = RectF(
                cx - ringRadius - 5f * dp,
                cy - ringRadius - 5f * dp,
                cx + ringRadius + 5f * dp,
                cy + ringRadius + 5f * dp
            )
            val autoSweep = currentState.autoCaptureProgress * 360f
            canvas.drawArc(autoOval, -90f, autoSweep, false, autoCaptureRingPaint)
        }

        val textY = cy + (scoreTextPaint.textSize / 3f) - 1.5f * dp
        canvas.drawText("$score", cx, textY, scoreTextPaint)
    }

    // THANH CHỮ GỢI Ý TIẾNG VIỆT ĐẶT CỐ ĐỊNH CĂN GIỮA PHÍA DƯỚI TOP TOOLBAR
    private fun drawGuidancePill(canvas: Canvas, w: Float, text: String) {
        if (text.isEmpty()) return

        val dp = resources.displayMetrics.density
        val paddingH = 16f * dp
        val heightPill = 34f * dp
        val textWidth = guidanceTextPaint.measureText(text)
        val pillWidth = textWidth + paddingH * 2f

        val left = (w - pillWidth) / 2f
        val top = 115f * dp
        val right = left + pillWidth
        val bottom = top + heightPill
        val radius = heightPill / 2f

        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, guidanceBgPaint)
        canvas.drawRoundRect(rect, radius, radius, guidanceStrokePaint)

        val textY = top + (heightPill / 2f) + (guidanceTextPaint.textSize / 3f)
        canvas.drawText(text, w / 2f, textY, guidanceTextPaint)
    }
}
