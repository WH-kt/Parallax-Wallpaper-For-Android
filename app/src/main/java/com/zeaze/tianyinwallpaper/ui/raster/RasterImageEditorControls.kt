// Fork addition (2026): liquid-glass zoom and rotation slider controls for raster image composition.
package com.zeaze.tianyinwallpaper.ui.raster

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.model.RasterImageTransform
import java.util.Locale
import kotlin.math.round

/** 控制面板使用独立的标准滑块触摸区域，拖动轨道即可调整，不要求精准捏住滑块。 */
@Composable
internal fun RasterImageEditorControls(
    transform: RasterImageTransform,
    minimumScale: Float,
    backdrop: Backdrop,
    enableGlass: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onScaleChange: (Float) -> Unit,
    onRotationChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val glass = if (enableGlass) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedRectangle(28.dp) },
            effects = {
                vibrancy()
                blur(12.dp.toPx())
                lens(10.dp.toPx(), 20.dp.toPx())
            },
            highlight = { Highlight.Plain },
            shadow = null,
            onDrawSurface = { drawRect(Color(0xFF10131C).copy(alpha = 0.52f)) }
        )
    } else {
        Modifier.clip(shape).background(Color(0xEB20232B))
    }
    val rotation = (transform.rotation % 360f).let {
        when {
            it > 180f -> it - 360f
            it < -180f -> it + 360f
            else -> it
        }
    }
    val maximumScale = maxOf(RasterImageTransform.MAX_SCALE, minimumScale + 0.01f)

    Column(
        modifier = modifier
            .then(glass)
            .border(
                0.75.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f))),
                shape
            )
            // 消费空白区域的点击，滑块与按钮仍由自己的手势处理。
            .clickable(interactionSource = null, indication = null, onClick = {})
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("构图调整", style = TextStyle(Color.White, 16.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            EditorControlButton("居中铺满", onReset)
        }
        BasicText(
            "单指拖动图片 · 双击恢复居中",
            style = TextStyle(Color.White.copy(alpha = 0.65f), 12.sp),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText("缩放", style = TextStyle(Color.White, 14.sp, FontWeight.Medium))
            BasicText(String.format(Locale.ROOT, "%.2f×", transform.scale), style = TextStyle(Color.White, 14.sp, FontWeight.Medium))
        }
        Slider(
            value = transform.scale.coerceIn(minimumScale, maximumScale),
            onValueChange = onScaleChange,
            valueRange = minimumScale..maximumScale,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accentColor, inactiveTrackColor = Color.White.copy(alpha = 0.22f)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "图片缩放" }
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("旋转", style = TextStyle(Color.White, 14.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
            BasicText(String.format(Locale.ROOT, "%.1f°", rotation), style = TextStyle(Color.White, 14.sp, FontWeight.Medium), modifier = Modifier.padding(end = 12.dp))
            EditorControlButton("归零") { onRotationChange(0f) }
        }
        Slider(
            value = rotation.coerceIn(-180f, 180f),
            onValueChange = { onRotationChange(round(it * 10f) / 10f) },
            valueRange = -180f..180f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accentColor, inactiveTrackColor = Color.White.copy(alpha = 0.22f)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "图片旋转角度" }
        )
    }
}

@Composable
private fun EditorControlButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextStyle(Color.White, 12.sp, FontWeight.Medium), maxLines = 1, softWrap = false)
    }
}
