package com.joeykot.dictate.audio

import com.joeykot.dictate.model.Pcm16Format
import java.io.File
import java.io.RandomAccessFile

internal data class PcmSource(
    val bytes: Long,
    val totalFrames: Long,
)

internal fun inspectPcmSource(rawInput: File, inputFormat: Pcm16Format): PcmSource {
    require(rawInput.isFile) { "Original PCM recording is missing" }
    val bytes = rawInput.length()
    require(bytes >= 0L && bytes % inputFormat.bytesPerFrame == 0L) {
        "Original PCM recording does not end on a complete frame"
    }
    return PcmSource(bytes = bytes, totalFrames = bytes / inputFormat.bytesPerFrame)
}

/** Concatenate half-open source-frame intervals without decoding or changing the samples. */
internal fun copyPcmIntervals(
    sourceFile: File,
    source: PcmSource,
    inputFormat: Pcm16Format,
    intervals: Sequence<SourceInterval>,
    destination: File,
    checkCancellation: () -> Unit,
) {
    checkCancellation()
    require(sourceFile != destination && sourceFile.length() == source.bytes) { "Invalid PCM copy source" }
    destination.parentFile?.mkdirs()
    val buffer = ByteArray(64 * 1024)
    RandomAccessFile(sourceFile, "r").use { input ->
        destination.outputStream().use { output ->
            var previousEnd = 0L
            for (interval in intervals) {
                require(interval.startFrame >= previousEnd && interval.startFrame < interval.endFrame &&
                    interval.endFrame <= source.totalFrames) { "Invalid PCM copy interval" }
                input.seek(Math.multiplyExact(interval.startFrame, inputFormat.bytesPerFrame.toLong()))
                var remaining = Math.multiplyExact(interval.frameCount, inputFormat.bytesPerFrame.toLong())
                while (remaining > 0L) {
                    checkCancellation()
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count)
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                previousEnd = interval.endFrame
            }
        }
    }
    require(sourceFile.length() == source.bytes) { "PCM recording changed during export" }
}
