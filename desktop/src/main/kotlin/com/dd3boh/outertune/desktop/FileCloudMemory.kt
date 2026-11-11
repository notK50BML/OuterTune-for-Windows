/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.io.File
import java.util.Properties

/**
 * Cloud relay's memory of this computer: its id in the relay, what it last sent, which bundles it
 * has taken - in a small file beside the library rather than in the library's settings.
 *
 * The settings live in library.db, which a backup carries whole. Restored onto a second computer,
 * the same id would make two devices one in the relay's eyes, and bundles one took would count as
 * taken by both. Kept apart, a restored computer starts as a new device: a new id, and everything
 * asked for once from the others, which the play identity makes harmless.
 */
class FileCloudMemory(private val file: File = File(defaultDataDirectory(), "cloud-relay.properties")) : CloudSyncMemory {
    private val props = Properties().also { p -> runCatching { file.inputStream().use(p::load) } }

    override var deviceId: String?
        get() = props.getProperty("device")?.ifBlank { null }
        set(value) = save { if (value == null) remove("device") else setProperty("device", value) }

    override var sentUpTo: Long
        get() = props.getProperty("sent")?.toLongOrNull() ?: 0L
        set(value) = save { setProperty("sent", value.toString()) }

    override var taken: Set<String>
        get() = props.getProperty("taken").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(value) = save { setProperty("taken", value.joinToString(",")) }

    @Synchronized
    private fun save(change: Properties.() -> Unit) {
        props.change()
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "OuterTune Cloud relay - this computer only") }
        }
    }
}
