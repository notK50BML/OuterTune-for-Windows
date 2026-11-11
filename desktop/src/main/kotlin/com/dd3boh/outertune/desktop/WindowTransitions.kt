/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows' own animations for one window - opening, minimising, maximising - turned off and back
 * on, through the desktop window manager's DWMWA_TRANSITIONS_FORCEDISABLED.
 *
 * For one thing: a window that was maximised last time comes up as an ordinary window and is then
 * maximised, since asked to start maximised it does not (see Main). With the animation on, that
 * showed as the window visibly growing to fill the screen at every start.
 */
object WindowTransitions {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val DWMWA_TRANSITIONS_FORCEDISABLED = 3

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    /** Turns [window]'s animations on or off. Does nothing where there is no DWM. */
    fun setEnabled(window: java.awt.Window, enabled: Boolean) {
        val api = dwm ?: return
        runCatching {
            api.DwmSetWindowAttribute(
                Pointer(Native.getWindowID(window)),
                DWMWA_TRANSITIONS_FORCEDISABLED,
                IntByReference(if (enabled) 0 else 1),
                4,
            )
        }
    }
}
