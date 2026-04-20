package com.example.mediaplayer.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.mediaplayer.R
import kotlin.math.hypot
import kotlin.math.pow

class VisualizerView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var fftBytes: ByteArray? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val BAR_COUNT = 45
    private var smoothedMagnitudes = FloatArray(BAR_COUNT)
    

    private val smoothingFactor = 0.25f 
    private val barSpacing = 4f
    private val cornerRadius = 15f

    private var gradient: LinearGradient? = null
    private val colorStart by lazy { ContextCompat.getColor(context, R.color.accent_purple) }
    private val colorEnd by lazy { ContextCompat.getColor(context, R.color.accent_cyan) }

    fun update(bytes: ByteArray) {
        fftBytes = bytes
        invalidate()
    }

    fun clear() {
        fftBytes = null
        for (i in 0 until BAR_COUNT) smoothedMagnitudes[i] = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradient(h.toFloat())
    }

    private fun updateGradient(h: Float) {
        gradient = LinearGradient(
            0f, h, 0f, 0f,
            intArrayOf(colorStart, colorEnd), 
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val data = fftBytes
        
        val contentW = (width - paddingLeft - paddingRight).toFloat()
        val contentH = (height - paddingTop - paddingBottom).toFloat()

        if (contentW <= 0 || contentH <= 0) return

        if (data == null || data.isEmpty()) {
            drawIdleState(canvas, contentW, contentH)
            return
        }

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        val barWidth = contentW / BAR_COUNT
        val numBins = data.size / 2

        for (i in 0 until BAR_COUNT) {

            val startBin = (numBins.toDouble().pow(i.toDouble() / BAR_COUNT)).toInt()
            val endBin = (numBins.toDouble().pow((i + 1).toDouble() / BAR_COUNT)).toInt().coerceAtMost(numBins)
            
            var maxMag = 0f
            for (binIdx in startBin until endBin) {
                val mag = if (binIdx == 0) {
                    Math.abs(data[0].toInt()).toFloat()
                } else if (binIdx == numBins - 1) {
                    Math.abs(data[1].toInt()).toFloat()
                } else {
                    val re = data[2 * binIdx].toInt()
                    val im = data[2 * binIdx + 1].toInt()
                    hypot(re.toFloat(), im.toFloat())
                }
                if (mag > maxMag) maxMag = mag
            }


            val normalized = (maxMag / 65f).coerceIn(0f, 1f)
            

            smoothedMagnitudes[i] = smoothedMagnitudes[i] + (normalized - smoothedMagnitudes[i]) * smoothingFactor


            val barHeight = (smoothedMagnitudes[i] * contentH * 0.95f).coerceAtLeast(6f)
            
            val left = i * barWidth + barSpacing / 2
            val right = (i + 1) * barWidth - barSpacing / 2
            val top = contentH - barHeight
            val bottom = contentH

            rect.set(left, top, right, bottom)
            
            paint.shader = gradient
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
        canvas.restore()
        
        postInvalidateOnAnimation()
    }

    private fun drawIdleState(canvas: Canvas, w: Float, h: Float) {
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        paint.shader = null
        paint.color = colorEnd
        paint.alpha = 40
        val centerY = h / 2
        val idleHeight = 6f
        canvas.drawRoundRect(barSpacing, centerY - idleHeight/2, w - barSpacing, centerY + idleHeight/2, 4f, 4f, paint)
        paint.alpha = 255
        canvas.restore()
    }
}
