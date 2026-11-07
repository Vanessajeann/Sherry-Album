/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 SiRan-Dev
 */

package io.github.vanessajeann.sherry.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mp4TimePatcher / ContainerDateParser 单元测试：
 * 构造合成 MP4 结构（不依赖真实视频文件），验证 box 遍历、
 * mvhd 原位补丁与读回的正确性，以及容器时间字符串解析。
 */
class Mp4TimePatcherTest {

    /** 写 4 字节大端整数。 */
    private fun be32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    /** 写 8 字节大端整数。 */
    private fun be64(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = (v ushr ((7 - i) * 8)).toByte()
        return b
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        require(type.length == 4)
        val out = ByteArray(8 + body.size)
        System.arraycopy(be32((8 + body.size).toLong()), 0, out, 0, 4)
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, out, 4, 4)
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    /** mvhd version 0：body = ver/flags(4) + creation(4) + modification(4) + timescale(4) + duration(4)。 */
    private fun mvhdV0(creation: Long, modification: Long) = box(
        "mvhd",
        concat(byteArrayOf(0, 0, 0, 0), be32(creation), be32(modification), be32(600), be32(12000))
    )

    /** mvhd version 1：body = ver/flags(4) + creation(8) + modification(8) + timescale(4) + duration(8)。 */
    private fun mvhdV1(creation: Long, modification: Long) = box(
        "mvhd",
        concat(byteArrayOf(1, 0, 0, 0), be64(creation), be64(modification), be32(600), be64(12000))
    )

    /** 大端读取无符号 32 位。 */
    private fun readU32(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    /** 大端读取无符号 64 位。 */
    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    private fun tempFile(prefix: String, suffix: String, bytes: ByteArray): File =
        File.createTempFile(prefix, suffix).apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    @Test
    fun `patch v0 mvhd writes creation and modification in place`() {
        val mp4 = concat(
            box("ftyp", byteArrayOf(1, 2, 3, 4)),
            box("mdat", ByteArray(64)),
            box("moov", concat(box("trak", ByteArray(16)), mvhdV0(0, 0))),
        )
        val file = tempFile("box0", ".mp4", mp4)
        val millis = 1_789_000_000_000L
        assertTrue(Mp4TimePatcher.patch(file, millis))

        val after = file.readBytes()
        val expected = Mp4TimePatcher.toMp4Seconds(millis)
        // mvhd body 内 creation_time 偏移：ftyp(12) + mdat(72) + moov头(8) + trak(24) + mvhd头(8) + ver/flags(4)
        val creationOffset = 12 + 72 + 8 + 24 + 8 + 4
        assertEquals(expected, readU32(after, creationOffset))
        assertEquals(expected, readU32(after, creationOffset + 4))
        // 文件大小不变，mvhd 之前的字节（含 mdat）不被触碰
        assertEquals(mp4.size, after.size)
        assertTrue(
            after.copyOfRange(0, creationOffset).contentEquals(mp4.copyOfRange(0, creationOffset))
        )
        file.delete()
    }

    @Test
    fun `patch v1 mvhd writes 64-bit fields`() {
        val mp4 = concat(
            box("ftyp", byteArrayOf(1, 2, 3, 4)),
            box("moov", mvhdV1(0, 0)),
        )
        val file = tempFile("box1", ".mov", mp4)
        val millis = 1_789_000_000_000L
        assertTrue(Mp4TimePatcher.patch(file, millis))

        val after = file.readBytes()
        val expected = Mp4TimePatcher.toMp4Seconds(millis)
        // creation_time 偏移：ftyp(12) + moov头(8) + mvhd头(8) + ver/flags(4)
        val creationOffset = 12 + 8 + 8 + 4
        assertEquals(expected, readU64(after, creationOffset))
        assertEquals(expected, readU64(after, creationOffset + 8))
        file.delete()
    }

    @Test
    fun `readCreationMillis returns patched value with second precision`() {
        val mp4 = concat(
            box("ftyp", byteArrayOf(1, 2, 3, 4)),
            box("moov", mvhdV0(0, 0)),
        )
        val file = tempFile("read", ".mp4", mp4)
        val millis = 1_789_000_000_123L
        assertEquals(0L, Mp4TimePatcher.readCreationMillis(file)) // 初始为纪元零值
        assertTrue(Mp4TimePatcher.patch(file, millis))
        assertEquals(millis / 1000 * 1000, Mp4TimePatcher.readCreationMillis(file))
        file.delete()
    }

    @Test
    fun `unsupported structure is rejected without writing`() {
        val millis = 1_789_000_000_000L

        // 缺少 moov
        val noMoov = concat(box("ftyp", byteArrayOf(1, 2, 3, 4)), box("mdat", ByteArray(16)))
        val f1 = tempFile("nomoov", ".mp4", noMoov)
        assertFalse(Mp4TimePatcher.patch(f1, millis))
        assertTrue(f1.readBytes().contentEquals(noMoov))
        f1.delete()

        // 不支持的扩展名（MKV/WebM 等）
        val withMoov = concat(box("moov", mvhdV0(0, 0)))
        val f2 = tempFile("video", ".mkv", withMoov)
        assertFalse(Mp4TimePatcher.patch(f2, millis))
        assertTrue(f2.readBytes().contentEquals(withMoov))
        f2.delete()

        // box 大小异常（损坏结构）：size=3 < 头大小 8
        val corrupt = concat(be32(3), "movi".toByteArray(Charsets.ISO_8859_1))
        val f3 = tempFile("bad", ".mp4", corrupt)
        assertFalse(Mp4TimePatcher.patch(f3, millis))
        assertTrue(f3.readBytes().contentEquals(corrupt))
        f3.delete()
    }

    @Test
    fun `readDurationMillis returns duration for both versions`() {
        // v0：timescale=600，duration=12000 → 20 秒
        val f0 = tempFile("dur0", ".mp4", concat(box("ftyp", byteArrayOf(1)), box("moov", mvhdV0(0, 0))))
        assertEquals(20_000L, Mp4TimePatcher.readDurationMillis(f0))
        f0.delete()
        // v1
        val f1 = tempFile("dur1", ".mov", concat(box("ftyp", byteArrayOf(1)), box("moov", mvhdV1(0, 0))))
        assertEquals(20_000L, Mp4TimePatcher.readDurationMillis(f1))
        f1.delete()
        // 不支持的容器
        val f2 = tempFile("dur2", ".mkv", byteArrayOf(1, 2, 3, 4))
        assertEquals(0L, Mp4TimePatcher.readDurationMillis(f2))
        f2.delete()
    }

    @Test
    fun `epoch conversion round trip`() {
        val millis = 1_789_000_000_000L
        assertEquals(millis / 1000 * 1000, Mp4TimePatcher.fromMp4Seconds(Mp4TimePatcher.toMp4Seconds(millis)))
    }

    @Test
    fun `container date parser handles common formats`() {
        // Android MediaMetadataRetriever 常见格式（含时区）：2026-07-16 20:05:46 +08:00 → UTC 12:05:46
        assertEquals(
            1_784_203_546_000L,
            ContainerDateParser.parse("20260716T200546.000000+0800")
        )
        // UTC Z 后缀
        assertEquals(
            1_784_232_346_000L,
            ContainerDateParser.parse("20260716T200546Z")
        )
        // ISO 分隔符格式
        assertEquals(
            ContainerDateParser.parse("2026-07-16T20:05:46+08:00"),
            ContainerDateParser.parse("20260716T200546+0800")
        )
    }

    @Test
    fun `container date parser rejects invalid values`() {
        assertEquals(0L, ContainerDateParser.parse(null))
        assertEquals(0L, ContainerDateParser.parse(""))
        assertEquals(0L, ContainerDateParser.parse("19700101T000000.000000Z")) // 纪元零值
        assertEquals(0L, ContainerDateParser.parse("not a date"))
        assertEquals(0L, ContainerDateParser.parse("20261301T200546Z")) // 月份越界
    }
}

