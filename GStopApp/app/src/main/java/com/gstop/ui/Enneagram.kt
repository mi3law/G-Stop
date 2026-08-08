package com.gstop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Bright orange stroke; green phrase. Nothing else appears on the stop screen. */
val EnneagramOrange = Color(0xFFFF7A1A)
val PhraseGreen = Color(0xFF3AD16A)

/** اُذْكُرِ اللَّهَ — "remember God". */
const val REMEMBRANCE_PHRASE = "اُذْكُرِ اللَّهَ"

/**
 * The enneagram: the circle, the 9–3–6 triangle, and the 1–4–2–8–5–7 hexad, with the phrase
 * centred inside it.
 *
 * Point k sits at angle -90° + k·40°, so 9 is at the top.
 */
@Composable
fun Enneagram(
    modifier: Modifier = Modifier,
    strokeColor: Color = EnneagramOrange,
    phrase: String? = REMEMBRANCE_PHRASE,
    phraseColor: Color = PhraseGreen
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = minOf(size.width, size.height) / 2f * 0.92f
            val center = Offset(size.width / 2f, size.height / 2f)
            val strokeWidth = radius * 0.022f

            fun point(k: Int): Offset {
                val angle = (-90.0 + k * 40.0) * PI / 180.0
                return Offset(
                    center.x + (radius * cos(angle)).toFloat(),
                    center.y + (radius * sin(angle)).toFloat()
                )
            }

            val stroke = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )

            drawCircle(color = strokeColor, radius = radius, center = center, style = stroke)

            fun polyline(vertices: List<Int>) {
                val path = Path()
                vertices.forEachIndexed { index, k ->
                    val p = point(k)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, color = strokeColor, style = stroke)
            }

            polyline(listOf(9, 3, 6))
            polyline(listOf(1, 4, 2, 8, 5, 7))
        }

        if (phrase != null) {
            Text(
                text = phrase,
                color = phraseColor,
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 34.sp, lineHeight = 52.sp),
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .padding(4.dp)
            )
        }
    }
}
