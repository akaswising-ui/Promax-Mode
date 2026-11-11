package com.promax.mode.audio

import java.io.File
import java.io.RandomAccessFile

object WavFile {
    fun writePcm16MonoHeader(file: File, sampleRate: Int, sampleCount: Long) {
        val dataBytes = (sampleCount * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeIntLe(raf, 36 + dataBytes)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLe(raf, 16)
            writeShortLe(raf, 1)
            writeShortLe(raf, 1)
            writeIntLe(raf, sampleRate)
            writeIntLe(raf, sampleRate * 2)
            writeShortLe(raf, 2)
            writeShortLe(raf, 16)
            raf.writeBytes("data")
            writeIntLe(raf, dataBytes)
        }
    }

    private fun writeIntLe(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value ushr 8) and 0xff)
        raf.write((value ushr 16) and 0xff)
        raf.write((value ushr 24) and 0xff)
    }

    private fun writeShortLe(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write((value ushr 8) and 0xff)
    }
}
