// Fork addition (2026): liquid-glass thumbnail card and image actions for raster group setup.
package com.zeaze.tianyinwallpaper.ui.raster

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图片单独记录为背景，玻璃控件只采样图片，避免把按钮自身反复采入背景。 */
@Composable
internal fun RasterImageThumbnailCard(
    imageUri: String,
    index: Int,
    enableLiquidGlass: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val photoBackdrop = rememberLayerBackdrop()
    val bitmap by produceState<Bitmap?>(null, imageUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ThumbnailUtils.loadImageThumbnail(context, imageUri, 512, 768)
            }.getOrNull()
        }
    }
    val cardShape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .clip(cardShape)
            .clickable(role = Role.Button, onClickLabel = "调整第${index + 1}张图片", onClick = onEdit)
            .border(
                width = if (index == 0) 1.5.dp else 0.75.dp,
                color = if (index == 0) accentColor else Color.White.copy(alpha = 0.3f),
                shape = cardShape
            )
    ) {
        Box(Modifier.fillMaxSize().layerBackdrop(photoBackdrop)) {
            val image = bitmap
            if (image != null) {
                Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF555965)))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .height(28.dp * fontScale)
                    .thumbnailGlass(photoBackdrop, enableLiquidGlass, 14.dp)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = if (index == 0) "${index + 1} · 封面" else "${index + 1}",
                    style = TextStyle(Color.White, 11.sp, FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .thumbnailGlass(photoBackdrop, enableLiquidGlass, 14.dp)
                    .semantics { contentDescription = "删除第${index + 1}张图片" }
                    .clickable(role = Role.Button, onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(10.dp)) {
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), 1.6.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), 1.6.dp.toPx(), StrokeCap.Round)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
                .fillMaxWidth()
                .height(38.dp * fontScale)
                .thumbnailGlass(photoBackdrop, enableLiquidGlass, 16.dp)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(role = Role.Button, onClick = onEdit),
                contentAlignment = Alignment.Center
            ) {
                BasicText("调整", style = TextStyle(Color.White, 12.sp, FontWeight.SemiBold), maxLines = 1, softWrap = false)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onReplace),
                contentAlignment = Alignment.Center
            ) {
                BasicText("替换", style = TextStyle(Color.White, 12.sp, FontWeight.Medium), maxLines = 1, softWrap = false)
            }
        }
    }
}

private fun Modifier.thumbnailGlass(backdrop: Backdrop, enabled: Boolean, radius: Dp): Modifier {
    val shape = RoundedCornerShape(radius)
    val surface = if (enabled) {
        drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedRectangle(radius) },
            effects = {
                vibrancy()
                blur(5.dp.toPx())
                lens(6.dp.toPx(), 10.dp.toPx())
            },
            highlight = { Highlight.Plain },
            shadow = null,
            onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.28f)) }
        )
    } else {
        clip(shape).background(Color.Black.copy(alpha = 0.58f))
    }
    return surface.border(
        0.75.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.3f))),
        shape
    )
}
