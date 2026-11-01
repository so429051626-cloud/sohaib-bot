package com.madix.beam.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.madix.beam.R
import com.madix.beam.ui.theme.Brand
import com.madix.beam.ui.theme.MadixTheme

/** Full-screen background with slowly drifting aurora glows. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MadixTheme.colors
    val background = MaterialTheme.colorScheme.background
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h) * 0.55f
                drawCircle(
                    brush = Brush.radialGradient(listOf(colors.auroraA, Color.Transparent), Offset(w * (0.15f + 0.2f * drift), h * 0.08f), r),
                    radius = r,
                    center = Offset(w * (0.15f + 0.2f * drift), h * 0.08f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(colors.auroraB, Color.Transparent), Offset(w * (0.95f - 0.25f * drift), h * 0.35f), r * 0.9f),
                    radius = r * 0.9f,
                    center = Offset(w * (0.95f - 0.25f * drift), h * 0.35f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(colors.auroraC, Color.Transparent), Offset(w * 0.3f, h * (0.95f - 0.1f * drift)), r * 0.8f),
                    radius = r * 0.8f,
                    center = Offset(w * 0.3f, h * (0.95f - 0.1f * drift)),
                )
            },
        content = content,
    )
}

/** Translucent card used throughout the interface. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MadixTheme.colors
    val base = modifier
        .clip(shape)
        .background(colors.glass)
        .border(BorderStroke(1.dp, colors.glassBorder), shape)
    val clickable = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Column(modifier = clickable.padding(contentPadding), content = content)
}

/** Primary call to action with the brand gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    brush: Brush = MadixTheme.colors.beamGradient,
) {
    val shape = RoundedCornerShape(18.dp)
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "buttonAlpha")
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .alpha(alpha)
            .clip(shape)
            .background(brush)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Secondary outlined action. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val shape = RoundedCornerShape(18.dp)
    val colors = MadixTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(colors.glass)
            .border(BorderStroke(1.dp, colors.glassBorder), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Circular badge with a gradient fill and a white glyph. */
@Composable
fun GradientIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    brush: Brush = MadixTheme.colors.beamGradient,
    size: Dp = 48.dp,
    iconSize: Dp = size * 0.5f,
    shape: RoundedCornerShape = RoundedCornerShape(size * 0.32f),
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** Top bar with an optional back action, transparent over the aurora background. */
@Composable
fun MadixTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Small statistic with a caption. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class BannerKind { INFO, SUCCESS, WARNING, ERROR }

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, kind: BannerKind = BannerKind.INFO) {
    val colors = MadixTheme.colors
    val tint = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.primary
        BannerKind.SUCCESS -> colors.success
        BannerKind.WARNING -> colors.warning
        BannerKind.ERROR -> colors.danger
    }
    val icon = when (kind) {
        BannerKind.INFO -> Icons.Rounded.Info
        BannerKind.SUCCESS -> Icons.Rounded.CheckCircle
        BannerKind.WARNING, BannerKind.ERROR -> Icons.Rounded.WarningAmber
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Circular progress indicator with a gradient sweep. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    stroke: Dp = 10.dp,
    indeterminate: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(400, easing = FastOutSlowInEasing), label = "ring")
    val transition = rememberInfiniteTransition(label = "ringSpin")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val track = MaterialTheme.colorScheme.outlineVariant
    val brush = Brush.sweepGradient(listOf(Brand.Cyan500, Brand.Violet500, Brand.Rose400, Brand.Cyan500))
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokePx))
            rotate(if (indeterminate) spin else -90f) {
                drawArc(
                    brush = brush,
                    startAngle = 0f,
                    sweepAngle = if (indeterminate) 90f else 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** Brand mark drawn with vectors: scanner brackets around a pixel "M". */
@Composable
fun MadixLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    withBackground: Boolean = true,
    glyphAlpha: Float = 1f,
) {
    Canvas(modifier = modifier.size(size)) {
        // Canvas drawing is not mirrored in right-to-left layouts, which keeps the mark intact.
        if (withBackground) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(Brand.Violet800, Brand.Violet600, Brand.Cyan500),
                    start = Offset.Zero,
                    end = Offset(this.size.width, this.size.height),
                ),
                cornerRadius = CornerRadius(this.size.width * 0.28f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = this.size.width * 0.45f,
                ),
                radius = this.size.width * 0.45f,
            )
        }
        drawMadixGlyph(alpha = glyphAlpha)
    }
}

private val PIXEL_M = listOf("X...X", "XX.XX", "X.X.X", "X...X", "X...X")

internal fun DrawScope.drawMadixGlyph(alpha: Float = 1f) {
    // Geometry expressed in the 108-unit adaptive icon grid.
    val unit = size.minDimension / 108f
    val ox = (size.width - 108f * unit) / 2f
    val oy = (size.height - 108f * unit) / 2f
    fun x(v: Float) = ox + v * unit
    fun y(v: Float) = oy + v * unit
    val white = Color.White.copy(alpha = alpha)
    val stroke = Stroke(width = 3.8f * unit, cap = StrokeCap.Round)
    val lo = 27.5f
    val hi = 80.5f
    val arm = 13.5f
    // Brackets as quarter-rounded corners.
    val corners = listOf(Offset(lo, lo) to 180f, Offset(hi, lo) to 270f, Offset(hi, hi) to 0f, Offset(lo, hi) to 90f)
    for ((corner, start) in corners) {
        val radius = 5f
        val cx = if (corner.x == lo) lo + radius else hi - radius
        val cy = if (corner.y == lo) lo + radius else hi - radius
        drawArc(
            color = white,
            startAngle = start,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(x(cx - radius), y(cy - radius)),
            size = Size(2 * radius * unit, 2 * radius * unit),
            style = stroke,
        )
        val horizontalEnd = if (corner.x == lo) lo + arm else hi - arm
        val verticalEnd = if (corner.y == lo) lo + arm else hi - arm
        drawLine(white, Offset(x(cx), y(corner.y)), Offset(x(horizontalEnd), y(corner.y)), stroke.width, StrokeCap.Round)
        drawLine(white, Offset(x(corner.x), y(cy)), Offset(x(corner.x), y(verticalEnd)), stroke.width, StrokeCap.Round)
    }
    val module = 6.8f
    val gap = 0.9f
    val start = 54f - (5 * module + 4 * gap) / 2f
    val beam = Brush.linearGradient(
        listOf(Brand.Cyan300.copy(alpha = alpha), Color(0xFFE9FDFF).copy(alpha = alpha)),
        start = Offset(x(40f), y(64f)),
        end = Offset(x(68f), y(36f)),
    )
    PIXEL_M.forEachIndexed { row, pattern ->
        pattern.forEachIndexed { col, c ->
            if (c == 'X') {
                val left = start + col * (module + gap)
                val top = start + row * (module + gap)
                val topLeft = Offset(x(left), y(top))
                val rectSize = Size(module * unit, module * unit)
                val radius = CornerRadius(1.7f * unit)
                if (col in 1..3) {
                    drawRoundRect(brush = beam, topLeft = topLeft, size = rectSize, cornerRadius = radius)
                } else {
                    drawRoundRect(color = white, topLeft = topLeft, size = rectSize, cornerRadius = radius)
                }
            }
        }
    }
}

/** Icon matching a MIME type. */
fun iconForMime(mime: String, isText: Boolean = false): ImageVector = when {
    isText -> Icons.Rounded.TextSnippet
    mime.startsWith("image/") -> Icons.Rounded.Image
    mime.startsWith("video/") -> Icons.Rounded.VideoFile
    mime.startsWith("audio/") -> Icons.Rounded.AudioFile
    mime == "application/pdf" -> Icons.Rounded.PictureAsPdf
    mime == "application/vnd.android.package-archive" -> Icons.Rounded.Android
    mime.contains("zip") || mime.contains("compressed") || mime.contains("rar") || mime.contains("tar") -> Icons.Rounded.FolderZip
    mime.startsWith("text/") -> Icons.Rounded.Article
    mime.contains("document") || mime.contains("msword") || mime.contains("sheet") || mime.contains("presentation") -> Icons.Rounded.Description
    else -> Icons.Rounded.InsertDriveFile
}

/** Placeholder shown when a list is empty. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GradientIcon(icon, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Pill-shaped label. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
    }
}
