/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 SiRan-Dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.vanessajeann.sherry.album

import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.TimeZone

/**
 * MP4/MOV 容器时间原位补丁工具（对应电脑端 ExifTool 写 QuickTime:CreateDate/ModifyDate 的能力）。
 *
 * 视频的「拍摄时间」不在 EXIF 里，而在 MP4 容器 moov/mvhd box 的
 * creation_time / modification_time 字段中。这两个字段是文件内
 * 固定偏移上的 4 字节（version 0）或 8 字节（version 1）整数，
 * 因此可以只改这几处字节、完全不重写文件——GB 级视频也是毫秒级完成，
 * 也不存在重写整个文件导致损坏的风险面。
 *
 * 安全策略：任何结构不符合预期（box 大小异常、类型不符、字段越界）都直接放弃，
 * 绝不在校验未通过的位置落笔；写入失败只影响容器时间，不影响文件时间修复主流程。
 *
 * 时间纪元：MP4 时间戳以 1904-01-01 00:00:00 UTC 为零点，
 * 与 Unix 纪元相差 2082844800 秒。
 */
object Mp4TimePatcher {

    /** Unix 纪元到 MP4 纪元（1904-01-01 UTC）的秒数差。 */
    const val MP4_EPOCH_OFFSET_SECONDS = 2082844800L

    /** 支持原位补丁的容器扩展名（moov/mvhd 结构），MKV/WebM 等不支持。 */
    private val supportedExtensions = setOf("mp4", "mov", "m4v", "3gp")

    fun isSupported(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in supportedExtensions
    }

    /** Unix 毫秒 → MP4 秒（1904 纪元）。 */
    fun toMp4Seconds(millis: Long): Long = millis / 1000 + MP4_EPOCH_OFFSET_SECONDS

    /** MP4 秒（1904 纪元）→ Unix 毫秒。 */
    fun fromMp4Seconds(seconds: Long): Long = (seconds - MP4_EPOCH_OFFSET_SECONDS) * 1000

    /**
     * 把 [millis] 原位写入 [file] 的 mvhd creation_time / modification_time。
     * 结构校验失败、非 MP4/MOV 容器或 IO 异常时返回 false（文件保持原样）。
     */
    fun patch(file: File, millis: Long): Boolean {
        if (!isSupported(file.name)) return false
        val seconds = toMp4Seconds(millis)
        if (seconds <= 0) return false
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val moov = findBox(raf, 0L, raf.length(), "moov") ?: return false
                val mvhdRange = findBox(raf, moov.first + moov.third, moov.second, "mvhd")
                    ?: return false
                patchMvhd(raf, mvhdRange.first, mvhdRange.second, seconds)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 读取容器 mvhd 的 creation_time（Unix 毫秒），读取失败返回 0。
     * 供扫描兜底使用（文件名无时间戳的视频）。
     */
    fun readCreationMillis(file: File): Long {
        if (!isSupported(file.name)) return 0
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val moov = findBox(raf, 0L, raf.length(), "moov") ?: return 0
                val mvhdRange = findBox(raf, moov.first + moov.third, moov.second, "mvhd")
                    ?: return 0
                raf.seek(mvhdRange.first + 8)
                val version = raf.readByte().toInt()
                val value = if (version == 1) {
                    if (mvhdRange.second - mvhdRange.first < 28) return 0
                    raf.seek(mvhdRange.first + 8 + 4)
                    readUnsignedLong(raf)
                } else {
                    if (mvhdRange.second - mvhdRange.first < 20) return 0
                    raf.seek(mvhdRange.first + 8 + 4)
                    readUnsignedInt(raf)
                }
                if (value <= 0) return 0
                fromMp4Seconds(value)
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 读取容器 mvhd 的视频时长（毫秒），读取失败或非支持容器返回 0。
     * mvhd body 布局：ver/flags(4) → creation → modification → timescale(4) → duration(4/8)。
     * 供异常判定容差使用：文件修改时间（录制结束）与拍摄时间（录制开始）的
     * 自然差值即视频时长，判定时应加上时长容差，避免误报。
     */
    fun readDurationMillis(file: File): Long {
        if (!isSupported(file.name)) return 0
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val moov = findBox(raf, 0L, raf.length(), "moov") ?: return 0
                val mvhdRange = findBox(raf, moov.first + moov.third, moov.second, "mvhd")
                    ?: return 0
                raf.seek(mvhdRange.first + 8)
                val version = raf.readByte().toInt()
                // timescale 位于 ver/flags + creation + modification 之后
                val timescaleOffset = mvhdRange.first + 8 + 4 + if (version == 1) 16 else 8
                // 之后还需容纳 timescale(4) + duration(4/8)
                val minSize = (timescaleOffset - mvhdRange.first) + if (version == 1) 12 else 8
                if (mvhdRange.second - mvhdRange.first < minSize) return 0
                raf.seek(timescaleOffset)
                val timescale = readUnsignedInt(raf)
                if (timescale <= 0) return 0
                val duration = if (version == 1) readUnsignedLong(raf) else readUnsignedInt(raf)
                if (duration <= 0) return 0
                duration * 1000 / timescale
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 在 [start, end) 范围内查找类型为 [type] 的直接子 box，
     * 返回 (boxStart, boxEnd, headerSize)；未找到返回 null。
     */
    private fun findBox(raf: RandomAccessFile, start: Long, end: Long, type: String): Triple<Long, Long, Long>? {
        var offset = start
        while (offset + 8 <= end) {
            raf.seek(offset)
            val size32 = readUnsignedInt(raf)
            val boxType = readFourCc(raf)
            var headerSize = 8L
            var boxSize = size32
            when {
                size32 == 1L -> {
                    // 64 位 largesize：真实大小在 header 之后 8 字节
                    if (offset + 16 > end) return null
                    boxSize = readUnsignedLong(raf)
                    headerSize = 16L
                }
                size32 == 0L -> {
                    // size 为 0 表示 box 延伸到父容器/文件末尾
                    boxSize = end - offset
                }
            }
            if (boxSize < headerSize || offset + boxSize > end) return null
            if (boxType == type) return Triple(offset, offset + boxSize, headerSize)
            offset += boxSize
        }
        return null
    }

    /**
     * 补丁 mvhd：version 0 写两个 4 字节（无符号），version 1 写两个 8 字节。
     * mvhd body 布局：version/flags(4) → creation_time → modification_time → timescale…
     * box 剩余空间不足以容纳两个时间字段时拒绝写入，绝不越界。
     */
    private fun patchMvhd(raf: RandomAccessFile, boxStart: Long, boxEnd: Long, seconds: Long): Boolean {
        raf.seek(boxStart + 8)
        val version = raf.readByte().toInt()
        when (version) {
            1 -> {
                if (seconds < 0 || boxEnd - boxStart < 28) return false
                raf.seek(boxStart + 8 + 4)
                writeBigEndianLong(raf, seconds)
                writeBigEndianLong(raf, seconds)
            }
            0 -> {
                // 32 位无符号可表示到 2040 年左右；超出或为负时不写入，避免污染
                if (seconds < 0 || seconds > 0xFFFFFFFFL || boxEnd - boxStart < 20) return false
                raf.seek(boxStart + 8 + 4)
                writeBigEndianInt(raf, seconds.toInt())
                writeBigEndianInt(raf, seconds.toInt())
            }
            else -> return false
        }
        return true
    }

    private fun readUnsignedInt(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return ((b[0].toLong() and 0xFF) shl 24) or
            ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or
            (b[3].toLong() and 0xFF)
    }

    private fun readUnsignedLong(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (b[i].toLong() and 0xFF)
        }
        return v
    }

    private fun readFourCc(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }

    private fun writeBigEndianInt(raf: RandomAccessFile, v: Int) {
        raf.write(
            byteArrayOf(
                (v ushr 24).toByte(),
                (v ushr 16).toByte(),
                (v ushr 8).toByte(),
                v.toByte(),
            )
        )
    }

    private fun writeBigEndianLong(raf: RandomAccessFile, v: Long) {
        val b = ByteArray(8)
        for (i in 0 until 8) {
            b[i] = (v ushr ((7 - i) * 8)).toByte()
        }
        raf.write(b)
    }
}

/**
 * 解析 MediaMetadataRetriever.METADATA_KEY_DATE 返回的容器时间字符串。
 * 常见格式：20260716T200546.000000+0800、20260716T200546Z、2026-07-16T20:05:46+08:00。
 * 无时区时按设备本地时区解释；年份不在 2000..2100 视为无效
 * （互传/录屏视频常见 1970 纪元零值），返回 0。
 */
object ContainerDateParser {

    private val regex = Regex(
        """(\d{4})-?(\d{2})-?(\d{2})[T\s]?(\d{2})?:?(\d{2})?:?(\d{2})?""" +
            """(?:\.(\d+))?(?:\s*(Z)|([+-])(\d{2}):?(\d{2}))?"""
    )

    fun parse(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        val m = regex.matchEntire(value.trim()) ?: return 0
        val g = m.groupValues
        val year = g[1].toIntOrNull() ?: return 0
        val month = g[2].toIntOrNull() ?: return 0
        val day = g[3].toIntOrNull() ?: return 0
        val hour = g[4].toIntOrNull() ?: 0
        val minute = g[5].toIntOrNull() ?: 0
        val second = g[6].toIntOrNull() ?: 0
        if (year !in 2000..2100 || month !in 1..12 || day !in 1..31 ||
            hour !in 0..23 || minute !in 0..59 || second !in 0..59
        ) {
            return 0
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        var millis = cal.timeInMillis
        when {
            g[9] == "+" -> millis -= (g[10].toInt() * 60 + g[11].toInt()) * 60_000L
            g[9] == "-" -> millis += (g[10].toInt() * 60 + g[11].toInt()) * 60_000L
        }
        return millis
    }
}

