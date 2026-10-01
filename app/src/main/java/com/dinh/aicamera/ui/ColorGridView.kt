package com.dinh.aicamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Lưới chọn màu 2D phong cách iPhone Camera:
 * - Trục ngang: sắc ấm/lạnh (cam <-> xanh dương)
 * - Trục dọc: cường độ
 * - Chạm/kéo để chọn điểm, hiển thị marker vòng tròn tại vị trí đã chọn
 */
class ColorGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onPointChanged: ((x: Float, y: Float) -> Unit)? = null

    // Giá trị chuẩn hóa 0..1 (mặc định tại tâm 0.5f, 0.5f)
    var pointX: Float = 0.5f
        private set
    var pointY: Float = 0.5f
        private set

    private val density = resources.displayMetrics.density
    private val cornerRadius = 16f * density
    private val markerRadius = 13f * density

    private val backgroundBounds = RectF()
    private var gradientBitmap: Bitmap? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#4DFFFFFF") // glass stroke
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#33FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    private val markerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.parseColor("#40000000")
    }

    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }

    private val markerCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F8A9C4") // Accent pink
    }

    init {
        // Cho phép touch mượt mà
        isClickable = true
        isFocusable = true
    }

    fun setPoint(x: Float, y: Float, notify: Boolean = false) {
        pointX = x.coerceIn(0f, 1f)
        pointY = y.coerceIn(0f, 1f)
        invalidate()
        if (notify) {
            onPointChanged?.invoke(pointX, pointY)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val size = if (width in 1 until height) width else if (height > 0) height else width
        val finalSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(finalSpec, finalSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            backgroundBounds.set(0f, 0f, w.toFloat(), h.toFloat())
            generateGradientBitmap(w, h)
        }
    }

    /**
     * Tạo bitmap gradient 2 chiều chính xác:
     * - Trục X: Cam (#FF9500) -> Neutral xám sáng (#ECECF2) -> Xanh dương (#0084FF)
     * - Trục Y: Cường độ & sáng tối (trên sáng tươi, dưới trầm sâu)
     */
    private fun generateGradientBitmap(width: Int, height: Int) {
        val bmpWidth = 128
        val bmpHeight = 128
        val pixels = IntArray(bmpWidth * bmpHeight)

        for (y in 0 until bmpHeight) {
            val v = y.toFloat() / (bmpHeight - 1) // 0..1 (dọc)
            val brightnessFactor = 1.15f - v * 0.35f // 1.15..0.80

            for (x in 0 until bmpWidth) {
                val u = x.toFloat() / (bmpWidth - 1) // 0..1 (ngang)

                val r: Float
                val g: Float
                val b: Float

                if (u < 0.5f) {
                    // Cam (255, 145, 20) -> Neutral (220, 220, 225)
                    val t = u / 0.5f
                    r = (255f * (1f - t) + 220f * t) * brightnessFactor
                    g = (145f * (1f - t) + 220f * t) * brightnessFactor
                    b = (20f * (1f - t) + 225f * t) * brightnessFactor
                } else {
                    // Neutral (220, 220, 225) -> Xanh dương (15, 140, 255)
                    val t = (u - 0.5f) / 0.5f
                    r = (220f * (1f - t) + 15f * t) * brightnessFactor
                    g = (220f * (1f - t) + 140f * t) * brightnessFactor
                    b = (225f * (1f - t) + 255f * t) * brightnessFactor
                }

                val ir = r.toInt().coerceIn(0, 255)
                val ig = g.toInt().coerceIn(0, 255)
                val ib = b.toInt().coerceIn(0, 255)

                pixels[y * bmpWidth + x] = Color.rgb(ir, ig, ib)
            }
        }

        gradientBitmap?.recycle()
        gradientBitmap = Bitmap.createBitmap(pixels, bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Lưu layer để clip bo tròn góc
        val saveCount = canvas.save()
        val path = android.graphics.Path().apply {
            addRoundRect(backgroundBounds, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(path)

        // 2. Vẽ gradient 2 chiều
        gradientBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, null, backgroundBounds, bitmapPaint)
        }

        // 3. Vẽ chữ thập đánh dấu tâm (0.5, 0.5)
        val centerX = w * 0.5f
        val centerY = h * 0.5f
        canvas.drawLine(centerX, 0f, centerX, h, crosshairPaint)
        canvas.drawLine(0f, centerY, w, centerY, crosshairPaint)

        // 4. Vẽ viền glass stroke bo góc
        canvas.drawRoundRect(backgroundBounds, cornerRadius, cornerRadius, borderPaint)

        canvas.restoreToCount(saveCount)

        // 5. Vẽ Marker tại điểm đã chọn
        val markerX = pointX * w
        val markerY = pointY * h

        // Shadow ngoài
        canvas.drawCircle(markerX, markerY, markerRadius + 1f * density, markerShadowPaint)
        // Vòng trắng chính
        canvas.drawCircle(markerX, markerY, markerRadius, markerRingPaint)
        // Tâm hồng accent
        canvas.drawCircle(markerX, markerY, 4f * density, markerCenterPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                updateFromTouch(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        pointX = (x / w).coerceIn(0f, 1f)
        pointY = (y / h).coerceIn(0f, 1f)
        invalidate()
        onPointChanged?.invoke(pointX, pointY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        gradientBitmap?.recycle()
        gradientBitmap = null
    }
}
