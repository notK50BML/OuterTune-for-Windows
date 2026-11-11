/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * MaterialTheme, with the app's own hover and press highlight in place of the ripple.
 *
 * Every theme root goes through this - the main window, the player's cover-coloured scheme, the
 * tray popup - because MaterialTheme provides its ripple as the default indication, so a nested
 * MaterialTheme on its own would quietly put the old square highlight back for everything inside it.
 */
@Composable
internal fun OuterTuneTheme(
    colorScheme: ColorScheme,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        CompositionLocalProvider(LocalIndication provides RoundedHighlight, content = content)
    }
}

/**
 * The highlight a plain `clickable` shows: a rounded box a little larger than the thing itself.
 *
 * The ripple it replaces filled exactly the clickable's bounds, so anything not clipped first - an
 * album card, an artist's description, a line of text - lit up as a hard-edged rectangle hugging its
 * content, unlike the rows that clip to a rounded shape and pad inside it. Doing it once here rather
 * than adding a clip and padding at every call site keeps every such highlight in the app the same,
 * including ones written later, and changes no layout: the box is drawn outside the bounds rather
 * than made by padding them. Where a clickable is already clipped (most rows), the clip still wins
 * and the highlight is that rounded shape, as before.
 *
 * Material's own components - buttons, icon buttons, chips, menu items, tabs - pass their ripple
 * explicitly rather than reading this, so they keep their shapes.
 *
 * Drawn over the content, as the ripple was, so a clickable with its own background still shows it.
 * Coloured from LocalContentColor, also as the ripple was, so it reads on any surface.
 */
internal object RoundedHighlight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = RoundedHighlightNode(interactionSource)

    // One shared instance, so the clickable modifiers holding it compare equal and never re-create
    // their nodes.
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** How far the highlight reaches past the clickable's edges - enough to read as padded. */
private val SPREAD = 4.dp
private val CORNER = 12.dp
private val FOCUS_RING = 2.dp

// Material's state layer strengths, so the change of shape is the only thing that looks different.
private const val HOVERED = 0.08f
private const val FOCUSED = 0.10f
private const val PRESSED = 0.14f

private class RoundedHighlightNode(private val interactionSource: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private val strength = Animatable(0f)

    /**
     * Keyboard focus, drawn as a ring as well as a fill so it stays visible on a busy cover. A mouse
     * click does not focus a clickable, so this does not linger after clicking.
     */
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            val hovers = mutableListOf<HoverInteraction.Enter>()
            val focuses = mutableListOf<FocusInteraction.Focus>()
            val presses = mutableListOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovers += interaction
                    is HoverInteraction.Exit -> hovers -= interaction.enter
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    else -> return@collect
                }
                val nowFocused = focuses.isNotEmpty()
                if (nowFocused != focused) {
                    focused = nowFocused
                    invalidateDraw()
                }
                val target = when {
                    presses.isNotEmpty() -> PRESSED
                    nowFocused -> FOCUSED
                    hovers.isNotEmpty() -> HOVERED
                    else -> 0f
                }
                val pressNow = interaction is PressInteraction.Press
                // A newer change cancels an animation still running - Animatable allows one at a time.
                coroutineScope.launch {
                    // Straight to pressed: a click shorter than a fade-in would otherwise show nothing.
                    if (pressNow) strength.snapTo(target)
                    else strength.animateTo(target, tween(if (target > strength.value) 90 else 180)) { invalidateDraw() }
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = strength.value
        if (alpha <= 0f && !focused) return
        val colour = currentValueOf(LocalContentColor)
        val spread = SPREAD.toPx()
        val box = Size(size.width + spread * 2, size.height + spread * 2)
        // Never more than half the short side, so a small icon's highlight is a soft square or a
        // circle rather than something that has lost its corners altogether.
        val radius = minOf(CORNER.toPx(), box.minDimension / 2)
        val topLeft = Offset(-spread, -spread)
        if (alpha > 0f) {
            drawRoundRect(color = colour, topLeft = topLeft, size = box, cornerRadius = CornerRadius(radius), alpha = alpha)
        }
        if (focused) {
            val ring = FOCUS_RING.toPx()
            drawRoundRect(
                color = colour,
                topLeft = Offset(topLeft.x + ring / 2, topLeft.y + ring / 2),
                size = Size(box.width - ring, box.height - ring),
                cornerRadius = CornerRadius((radius - ring / 2).coerceAtLeast(0f)),
                alpha = 0.6f,
                style = Stroke(width = ring),
            )
        }
    }
}
