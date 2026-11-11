/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Stand-ins drawn where a page's content will be while it is fetched - the phone's shimmer, more or
 * less. Each is the size of the thing it stands for, so when the real content arrives it takes the
 * same space and nothing on the page moves: a spinner in the middle of an empty page meant the header
 * dropped in, then the shelves pushed everything down one by one.
 */

/** The colour placeholders are drawn in: the text colour, faint, breathing slowly so it reads as loading. */
@Composable
internal fun placeholderColour(): Color {
    val pulse by rememberInfiniteTransition(label = "placeholder").animateFloat(
        initialValue = 0.06f,
        targetValue = 0.13f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "placeholder",
    )
    return MaterialTheme.colorScheme.onSurface.copy(alpha = pulse)
}

/**
 * Bars standing in for [lines] lines of text in [style], the last one [lastLineFraction] as wide.
 *
 * Sized by an invisible empty text of the same style and line count rather than by a guessed height,
 * so it is exactly as tall as the text that replaces it whatever the font and text scale.
 */
@Composable
internal fun PlaceholderLines(
    style: TextStyle,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    lastLineFraction: Float = 0.6f,
) {
    val colour = placeholderColour()
    val density = LocalDensity.current
    // Shorter than the line, as the letters are, so stacked bars read as lines rather than a block.
    val bar: Dp = if (style.fontSize.isSp) with(density) { style.fontSize.toDp() } * 0.7f else 12.dp
    Box(modifier = modifier.fillMaxWidth()) {
        Text("", style = style, minLines = lines, maxLines = lines, modifier = Modifier.alpha(0f))
        Column(modifier = Modifier.matchParentSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(lines) { line ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (line == lines - 1) lastLineFraction else 1f)
                        .height(bar)
                        .clip(RoundedCornerShape(bar / 2))
                        .background(colour),
                )
            }
        }
    }
}

/** A plain shape in the placeholder colour - a picture not yet known, a row's thumbnail. */
@Composable
internal fun PlaceholderBox(modifier: Modifier, cornerRadius: Dp = 8.dp) {
    Box(modifier = modifier.clip(RoundedCornerShape(cornerRadius)).background(placeholderColour()))
}

/**
 * A song row while the list loads: the same padding and 48dp thumbnail as SongRow and the artist
 * page's numbered rows, so the real rows land exactly where these were.
 */
@Composable
internal fun SongRowPlaceholder(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        PlaceholderBox(Modifier.size(48.dp), cornerRadius = 6.dp)
        Box(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            PlaceholderLines(MaterialTheme.typography.bodyLarge, lastLineFraction = 0.45f)
            PlaceholderLines(MaterialTheme.typography.bodySmall, lastLineFraction = 0.3f)
        }
    }
}
