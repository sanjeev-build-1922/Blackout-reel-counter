package com.blackout.focus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blackout.focus.ui.theme.PureWhite
import java.util.Locale

val UnlitNodeGray = Color(0xFF222222)

/**
 * Minimalist 5x7 crisp square-pixel matrix renderer matching Blackout's rectangular monochrome UI.
 */
@Composable
fun DotMatrixNumber(
    value: Int,
    minDigits: Int = 3,
    dotSize: Dp = 6.dp,
    dotSpacing: Dp = 3.dp,
    digitSpacing: Dp = 14.dp,
    activeColor: Color = PureWhite,
    inactiveColor: Color = UnlitNodeGray,
    modifier: Modifier = Modifier
) {
    val formatted = String.format(Locale.US, "%0${minDigits}d", value.coerceAtLeast(0))

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(digitSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        formatted.forEach { char ->
            DotMatrixGlyph(
                char = char,
                dotSize = dotSize,
                dotSpacing = dotSpacing,
                activeColor = activeColor,
                inactiveColor = inactiveColor
            )
        }
    }
}

@Composable
fun DotMatrixGlyph(
    char: Char,
    dotSize: Dp,
    dotSpacing: Dp,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val matrix = DIGIT_PATTERNS[char] ?: DIGIT_PATTERNS['0']!!
    val cols = 5
    val rows = 7

    val totalWidth = (dotSize * cols) + (dotSpacing * (cols - 1))
    val totalHeight = (dotSize * rows) + (dotSpacing * (rows - 1))

    Canvas(
        modifier = modifier
            .width(totalWidth)
            .height(totalHeight)
    ) {
        val side = dotSize.toPx()
        val step = side + dotSpacing.toPx()

        for (r in 0 until rows) {
            val rowMask = matrix[r]
            for (c in 0 until cols) {
                val isLit = rowMask[c] == '1'
                val x = c * step
                val y = r * step
                drawRect(
                    color = if (isLit) activeColor else inactiveColor,
                    topLeft = Offset(x, y),
                    size = Size(side, side)
                )
            }
        }
    }
}

@Composable
fun DotMatrixProgressBar(
    progress: Float,
    totalColumns: Int = 28,
    activeColor: Color = PureWhite,
    inactiveColor: Color = UnlitNodeGray,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val litColumns = (clamped * totalColumns).toInt()

    Canvas(
        modifier = modifier.height(12.dp)
    ) {
        val rows = 2
        val side = 4.dp.toPx()
        val horizontalSpacing = size.width / totalColumns.toFloat()
        val verticalSpacing = size.height / rows.toFloat()

        for (c in 0 until totalColumns) {
            val isLit = c < litColumns
            for (r in 0 until rows) {
                val x = c * horizontalSpacing
                val y = r * verticalSpacing
                drawRect(
                    color = if (isLit) activeColor else inactiveColor,
                    topLeft = Offset(x, y),
                    size = Size(side, side)
                )
            }
        }
    }
}

private val DIGIT_PATTERNS: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00110", "01000", "10000", "11111"),
    '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100")
)
