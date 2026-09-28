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

    private var currentState: CompositionState = CompositionState()

    // Smooth animated values
    private var animatedScore: Float = 0f
    private var scoreAnimator: ValueAnimator? = null

    // Target point lerp
    private var targetX: Float = 0f
    private var targetY: Float = 0f

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
        strokeWidth = 2f * resources.displayMetrics.density
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
        strokeWidth = 4f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val scoreProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scoreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = 15f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val scoreLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white_70)
        textSize = 9f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
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
        textSize = 13.5f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val autoCaptureRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.score_green)
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun updateState(state: CompositionState) {
        val oldScore = currentState.score
        currentState = state

        if (oldScore != state.score) {
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

        // 1. Vẽ lưới 1/3 mờ tinh tế
        drawRuleOfThirdsGrid(canvas, w, h)

        // 2. Thước cân bằng chân trời (Horizon Level Indicator)
        drawHorizonIndicator(canvas, w, h)

        // 3. Nếu có chủ thể, vẽ khung chủ thể, vòng đích vàng & mũi tên chỉ dẫn AR
        if (currentState.hasSubject) {
            drawSubjectReticle(canvas)
            drawTargetGoldenRing(canvas)
            drawDirectionalArrow(canvas)
        }

        // 4. Vòng điểm bố cục 0-100 ở góc trên bên phải
        drawScoreRing(canvas, w)

        // 5. Thanh chữ gợi ý tiếng Việt dạng Glass Pill ở phía trên
        drawGuidancePill(canvas, w)
    }

    private fun drawRuleOfThirdsGrid(canvas: Canvas, w: Float, h: Float) {
        val x1 = w / 3f
        val x2 = w * 2f / 3f
        val y1 = h / 3f
        val y2 = h * 2f / 3f

        // Đường dọc
        canvas.drawLine(x1, 0f, x1, h, gridPaint)
        canvas.drawLine(x2, 0f, x2, h, gridPaint)

        // Đường ngang
        canvas.drawLine(0f, y1, w, y1, gridPaint)
        canvas.drawLine(0f, y2, w, y2, gridPaint)
    }

    private fun drawHorizonIndicator(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val lineLen = 36f * resources.displayMetrics.density
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

        // Vẽ vạch cân bằng 2 bên tâm màn hình
        val gap = 20f * resources.displayMetrics.density
        canvas.drawLine(cx - gap - lineLen, cy, cx - gap, cy, horizonPaint)
        canvas.drawLine(cx + gap, cy, cx + gap + lineLen, cy, horizonPaint)

        // Chấm tròn tâm
        canvas.drawCircle(cx, cy, 2.5f * resources.displayMetrics.density, horizonPaint)

        canvas.restore()
    }

    private fun drawTargetGoldenRing(canvas: Canvas) {
        val pt = currentState.targetPoint
        if (pt.x <= 0f && pt.y <= 0f) return

        targetX += 0.25f * (pt.x - targetX)
        targetY += 0.25f * (pt.y - targetY)

        val radius = 16f * resources.displayMetrics.density
        // Outer subtle glow
        canvas.drawCircle(targetX, targetY, radius + 4f, targetGlowPaint)
        // Core golden ring
        canvas.drawCircle(targetX, targetY, radius, targetRingPaint)
        // Center crosshair dot
        canvas.drawCircle(targetX, targetY, 2f * resources.displayMetrics.density, targetRingPaint)
    }

    private fun drawSubjectReticle(canvas: Canvas) {
        val box = currentState.subjectBounds
        if (box.isEmpty) return

        val cornerLen = 14f * resources.displayMetrics.density
        val radius = 10f * resources.displayMetrics.density

        // Bo 4 góc tinh tế thay vì khung chữ nhật thô ráp
        val path = Path()

        // Top-Left
        path.moveTo(box.left, box.top + cornerLen)
        path.lineTo(box.left, box.top + radius)
        path.quadTo(box.left, box.top, box.left + radius, box.top)
        path.lineTo(box.left + cornerLen, box.top)

        // Top-Right
        path.moveTo(box.right - cornerLen, box.top)
        path.lineTo(box.right - radius, box.top)
        path.quadTo(box.right, box.top, box.right, box.top + radius)
        path.lineTo(box.right, box.top + cornerLen)

        // Bottom-Right
        path.moveTo(box.right, box.bottom - cornerLen)
        path.lineTo(box.right, box.bottom - radius)
        path.quadTo(box.right, box.bottom, box.right - radius, box.bottom)
        path.lineTo(box.right - cornerLen, box.bottom)

        // Bottom-Left
        path.moveTo(box.left + cornerLen, box.bottom)
        path.lineTo(box.left + radius, box.bottom)
        path.quadTo(box.left, box.bottom, box.left, box.bottom - radius)
        path.lineTo(box.left, box.bottom - cornerLen)

        if (currentState.score >= 85) {
            subjectBoxPaint.color = ContextCompat.getColor(context, R.color.accent_gold)
        } else {
            subjectBoxPaint.color = ContextCompat.getColor(context, R.color.white_70)
        }
        canvas.drawPath(path, subjectBoxPaint)
    }

    private fun drawDirectionalArrow(canvas: Canvas) {
        val from = currentState.subjectCenter
        val to = currentState.targetPoint
        val dist = hypot(to.x - from.x, to.y - from.y)

        // Chỉ vẽ mũi tên nếu khoảng cách đáng kể (> 35dp) và chưa đạt điểm tối ưu
        val threshold = 35f * resources.displayMetrics.density
        if (dist <= threshold || currentState.score >= 85) return

        val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()).toFloat()

        // Dời điểm đầu và đuôi tránh đè lên tâm
        val startGap = 20f * resources.displayMetrics.density
        val endGap = 20f * resources.displayMetrics.density

        val startX = from.x + cos(angle.toDouble()).toFloat() * startGap
        val startY = from.y + sin(angle.toDouble()).toFloat() * startGap
        val endX = to.x - cos(angle.toDouble()).toFloat() * endGap
        val endY = to.y - sin(angle.toDouble()).toFloat() * endGap

        canvas.drawLine(startX, startY, endX, endY, arrowPaint)

        // Đầu mũi tên tam giác sắc nét
        val headSize = 8f * resources.displayMetrics.density
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

    private fun drawScoreRing(canvas: Canvas, w: Float) {
        val dp = resources.displayMetrics.density
        val ringRadius = 26f * dp
        val cx = w - ringRadius - 20f * dp
        val cy = 115f * dp

        // Glass background disk
        canvas.drawCircle(cx, cy, ringRadius + 4f * dp, scoreBgPaint)
        canvas.drawCircle(cx, cy, ringRadius + 4f * dp, guidanceStrokePaint)

        // Track
        canvas.drawCircle(cx, cy, ringRadius, scoreTrackPaint)

        // Dynamic Progress Color
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

        // Outer Auto-Capture Hold Progress Arc
        if (currentState.autoCaptureProgress > 0f) {
            val autoOval = RectF(
                cx - ringRadius - 6f * dp,
                cy - ringRadius - 6f * dp,
                cx + ringRadius + 6f * dp,
                cy + ringRadius + 6f * dp
            )
            val autoSweep = currentState.autoCaptureProgress * 360f
            canvas.drawArc(autoOval, -90f, autoSweep, false, autoCaptureRingPaint)
        }

        // Score text
        val textY = cy + (scoreTextPaint.textSize / 3f) - 2f * dp
        canvas.drawText("$score", cx, textY, scoreTextPaint)
    }

    private fun drawGuidancePill(canvas: Canvas, w: Float) {
        val guide = currentState.guidanceText
        if (guide.isEmpty()) return

        val dp = resources.displayMetrics.density
        val paddingH = 18f * dp
        val heightPill = 38f * dp
        val textWidth = guidanceTextPaint.measureText(guide)
        val pillWidth = textWidth + paddingH * 2f

        val left = (w - pillWidth) / 2f
        val top = 160f * dp
        val right = left + pillWidth
        val bottom = top + heightPill
        val radius = heightPill / 2f

        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, guidanceBgPaint)
        canvas.drawRoundRect(rect, radius, radius, guidanceStrokePaint)

        val textY = top + (heightPill / 2f) + (guidanceTextPaint.textSize / 3f)
        canvas.drawText(guide, w / 2f, textY, guidanceTextPaint)
    }
}
