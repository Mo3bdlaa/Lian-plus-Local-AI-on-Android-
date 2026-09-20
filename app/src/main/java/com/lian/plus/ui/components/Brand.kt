package com.lian.plus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lian.plus.R
import com.lian.plus.ui.theme.Lian

/** The Lian+ wordmark, drawn from the brand artwork. */
@Composable
fun LianWordmark(modifier: Modifier = Modifier, width: Dp = 120.dp) {
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.lian_wordmark),
        contentDescription = "Lian+",
        contentScale = ContentScale.FillWidth,
        modifier = modifier.width(width),
    )
}

/** The rounded app mark, for the About screen and empty states. */
@Composable
fun LianAppMark(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.lian_app_mark),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/**
 * Text painted with the brand ramp.
 *
 * `graphicsLayer(alpha)` forces the text into an offscreen layer so the
 * `SrcIn`-style brush blend lands on the glyphs rather than the whole node.
 */
@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    brush: Brush = Lian.gradient,
) {
    Text(
        text = text,
        modifier = modifier.graphicsLayer(alpha = 0.99f),
        style = style.copy(brush = brush),
    )
}

/** The primary call to action: a filled pill carrying the brand ramp. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Lian.gradient else Brush.linearGradient(DISABLED))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leadingIcon?.let {
                Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            trailingIcon?.let {
                Spacer(Modifier.width(8.dp))
                Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private val DISABLED = listOf(Lian.SurfaceRaised, Lian.SurfaceRaised)

/** A surface panel: raised fill, hairline outline, generous corner radius. */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val base = modifier
        .clip(shape)
        .background(if (highlighted) Lian.SurfaceRaised else Lian.Surface)
        .border(
            BorderStroke(1.dp, if (highlighted) Lian.Purple.copy(alpha = 0.55f) else Lian.Outline),
            shape,
        )
    Column(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
    ) {
        Column(Modifier.padding(contentPadding)) { content() }
    }
}

/** Alias so callers get a plain ColumnScope without importing it everywhere. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/** A square icon tile with a translucent gradient wash, as used on Home. */
@Composable
fun GradientIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(
                Brush.linearGradient(
                    listOf(Lian.Purple.copy(alpha = 0.32f), Lian.Cyan.copy(alpha = 0.22f)),
                ),
            )
            .border(1.dp, Lian.Purple.copy(alpha = 0.35f), RoundedCornerShape(size / 3.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Small uppercase label that opens a group of settings or list items. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Lian.TextMuted,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp),
    )
}

/** A tappable row: icon, title, optional value, optional trailing content. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = Lian.TextMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Lian.TextPrimary)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Lian.TextMuted)
            }
        }
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Lian.TextMuted)
        }
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }
    }
}

/** A selectable pill, used for styles, aspect ratios and filters. */
@Composable
fun BrandChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Lian.Purple.copy(alpha = 0.22f) else Lian.Surface)
            .border(
                1.dp,
                if (selected) Lian.Purple else Lian.Outline,
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) Lian.TextPrimary else Lian.TextMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A circular action button, as used under a generated image. */
@Composable
fun CircleAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Lian.SurfaceRaised)
                .border(1.dp, Lian.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = LocalContentColor.current, modifier = Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
    }
}

/** A 1px gradient rule, for separating hero sections. */
@Composable
fun GradientDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Lian.Purple.copy(alpha = 0.6f), Color.Transparent),
                ),
            ),
    )
}
