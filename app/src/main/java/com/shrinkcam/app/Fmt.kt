package com.shrinkcam.app

import java.util.Locale
import java.util.concurrent.TimeUnit

object Fmt {
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun duration(us: Long): String {
        if (us <= 0) return "00:00"
        val s = TimeUnit.MICROSECONDS.toSeconds(us)
        val m = s / 60
        val sec = s % 60
        return if (m >= 60) {
            String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", m, sec)
        }
    }

    fun kbps(bps: Int): String = if (bps >= 1_000_000) {
        String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f)
    } else {
        "${bps / 1000} kbps"
    }

    /** 倍数，例如 12.4x */
    fun ratio(src: Long, dst: Long): String {
        if (dst <= 0 || src <= 0) return "-"
        return String.format(Locale.US, "%.1fx", src.toDouble() / dst.toDouble())
    }

    fun percent(saved: Double): String = String.format(Locale.US, "%.0f%%", saved * 100)
}
