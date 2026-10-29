package com.shengzimao.reader.mobile.reader

import androidx.compose.ui.geometry.Offset
import com.shengzimao.reader.mobile.data.TapTarget

/** 点按落点分类（纯逻辑，方便单测）。 */
object TapClassifier {

    /**
     * 按热区划分判断点按意图。
     * 在左右分区时竖直方向不分区；上下分区时横向不分区。
     */
    fun classify(
        point: Offset,
        width: Float,
        height: Float,
        vertical: Boolean,
        edge: Float,
    ): TapTarget = if (vertical) {
        val v = point.y
        when {
            v < height * 0.35f -> TapTarget.PREV
            v > height * 0.65f -> TapTarget.NEXT
            else -> TapTarget.MENU
        }
    } else {
        val zone = width * edge.coerceIn(0f, 0.5f)
        when {
            point.x < zone -> TapTarget.PREV
            point.x > width - zone -> TapTarget.NEXT
            else -> TapTarget.MENU
        }
    }
}

/** 滑动方向判定（纯逻辑，方便单测）。 */
object SwipeClassifier {

    /** 水平方向判定为翻页所需的最小位移占比。 */
    const val HORIZONTAL_THRESHOLD = 0.04f

    /**
     * 竖直方向判定为滚动所需的最小位移占比。
     *
     * 原值 0.004f（约 10px）过于灵敏：点按时的轻微抖动就会被判成滚动并吃掉这次点按，
     * 于是「点中间呼出菜单」时灵时不灵。抬到约 2% 屏高，与 touchSlop 同量级。
     */
    const val VERTICAL_THRESHOLD = 0.020f

    /** 竖直位移超过该比例时，即使横向也达标，也按滚动处理（避免斜滑误翻页）。 */
    const val VERTICAL_PRIORITY = 0.030f

    enum class Result { NONE, NEXT, PREV, SCROLL }

    /**
     * @param dx 累计横向位移（正为手指向右）
     * @param dy 累计纵向位移（正为手指向下）
     * @param touchSlopPx 系统 touchSlop（像素），给竖直判定兜底，避免阈值小于系统防抖
     */
    fun decide(
        dx: Float,
        dy: Float,
        width: Float,
        height: Float,
        allowHorizontal: Boolean,
        allowVertical: Boolean,
        touchSlopPx: Float = 0f,
    ): Result {
        if (width <= 0f || height <= 0f) return Result.NONE
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        val horizontalGate = maxOf(width * HORIZONTAL_THRESHOLD, touchSlopPx * 1.5f)
        val horizontal = ax > horizontalGate && ax > ay * 1.15f
        if (allowHorizontal && horizontal) {
            // 手指左滑 -> 下一页
            return if (dx < 0f) Result.NEXT else Result.PREV
        }
        val verticalGate = maxOf(height * VERTICAL_THRESHOLD, touchSlopPx * 1.5f)
        if (allowVertical && ay > verticalGate && ay > ax * 0.8f) return Result.SCROLL
        return Result.NONE
    }
}

/** 滚动模式的位移换算：手指上滑对应正文下移（继续往后读）。 */
object ScrollMath {
    /** 滚动内容总量相对视口高度的倍数。 */
    fun contentScale(pageCount: Int): Float = (pageCount.toFloat() - 1f).coerceAtLeast(0f)

    fun fractionOf(offsetY: Float, viewportHeight: Float, pageCount: Int): Float {
        val total = viewportHeight * contentScale(pageCount)
        if (total <= 0f) return 0f
        return (offsetY / total).coerceIn(0f, 1f)
    }

    /** 松手后的吸附目标：滚动按页吸附，避免停在半页。 */
    fun snapPage(fraction: Float, pageCount: Int): Int {
        if (pageCount <= 1) return 0
        return Math.round(fraction * (pageCount - 1)).coerceIn(0, pageCount - 1)
    }
}
