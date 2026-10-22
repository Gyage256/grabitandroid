package com.grabit

import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

enum class Status { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED, CANCELLED }

class Item(val id: Int, val url: String) {
    @Volatile var name: String = ""
    @Volatile var total: Long = -1
    @Volatile var done: Long = 0
    @Volatile var speed: Long = 0
    @Volatile var status: Status = Status.QUEUED
    @Volatile var error: String = ""
    @Volatile var savedAs: String = ""
    @Volatile var pause: Boolean = false
    @Volatile var cancel: Boolean = false
}

object Store {
    val items = CopyOnWriteArrayList<Item>()
    private val ids = AtomicInteger(1)

    fun add(url: String): Item {
        val item = Item(ids.getAndIncrement(), url)
        items.add(item)
        return item
    }

    fun get(id: Int): Item? = items.firstOrNull { it.id == id }

    fun active(): List<Item> =
        items.filter { it.status == Status.QUEUED || it.status == Status.DOWNLOADING }
}

fun fmtBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}
