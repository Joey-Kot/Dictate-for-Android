package com.joeykot.dictate.audio

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.VadConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier

internal object NativeVad {
    init {
        System.loadLibrary("dictate_vad")
    }

    /** Interleaved start/end frame indices on the original timeline. Null means cancelled. */
    external fun analyze(
        analysisPath: String,
        sourceRate: Int,
        sourceFrames: Long,
        paddingMs: Int,
        startThreshold: Double,
        cancelled: BooleanSupplier,
    ): LongArray?
}

/** Encode one complete upload from the retained recording. */
internal fun AudioTranscoder.transcodeRecording(
    jobId: Long,
    original: File,
    format: Pcm16Format,
    output: File,
    plan: AudioEncodingPlan,
    vad: VadConfig,
    cancelled: () -> Boolean,
): AudioTranscoder.RecordingResult {
    if (cancelled()) return AudioTranscoder.Result.Cancelled
    if (!vad.enabled) return transcode(jobId, original, format, output, plan, cancelled)

    var temporary: File? = null
    return try {
        Files.deleteIfExists(output.toPath())
        val root = Files.createDirectories(output.absoluteFile.toPath().parent)
        val directory = Files.createTempDirectory(root, "vad-$jobId-").toFile()
        temporary = directory
        when (val speech = selectSpeechPcm(jobId, original, format, directory, vad, cancelled)) {
            is AudioTranscoder.Result.Success -> transcode(jobId, speech.output, format, output, plan, cancelled)
            else -> speech
        }
    } catch (error: Exception) {
        if (cancelled()) AudioTranscoder.Result.Cancelled else vadFailure(error)
    } finally {
        temporary?.deleteRecursively()
    }
}

/** The caller owns directory and the selected PCM until encoding or segment export finishes. */
internal fun AudioTranscoder.selectSpeechPcm(
    jobId: Long,
    original: File,
    format: Pcm16Format,
    directory: File,
    vad: VadConfig,
    cancelled: () -> Boolean,
): AudioTranscoder.RecordingResult {
    if (cancelled()) return AudioTranscoder.Result.Cancelled
    return try {
        val source = inspectPcmSource(original, format)
        val analysis = if (format == Pcm16Format.LEGACY_MONO_16_KHZ) original else {
            val pcm = File(directory, "analysis-16k.pcm")
            val analysisPlan = AudioEncodingPlan.resolve(
                AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.S16LE, sampleRate = 16_000, bitDepth = 16),
                format,
            )
            val result = transcode(jobId, original, format, pcm, analysisPlan, cancelled)
            if (result !is AudioTranscoder.Result.Success) return result
            pcm
        }
        val ranges = NativeVad.analyze(
            analysis.absolutePath, format.sampleRateHz, source.totalFrames,
            vad.paddingMs, vad.startThreshold, BooleanSupplier(cancelled),
        ) ?: return AudioTranscoder.Result.Cancelled
        if (analysis != original) Files.delete(analysis.toPath())
        if (cancelled()) return AudioTranscoder.Result.Cancelled
        if (ranges.isEmpty()) return AudioTranscoder.RecordingResult.NoSpeech
        require(ranges.size % 2 == 0) { "Incomplete VAD interval" }

        val selected = if (ranges.size == 2 && ranges[0] == 0L && ranges[1] == source.totalFrames) original else {
            val pcm = File(directory, "selected.pcm")
            val intervals = (ranges.indices step 2).asSequence().map { SourceInterval(ranges[it], ranges[it + 1]) }
            copyPcmIntervals(original, source, format, intervals, pcm) {
                if (cancelled()) throw CancellationException()
            }
            pcm
        }
        AudioTranscoder.Result.Success(selected)
    } catch (_: CancellationException) {
        AudioTranscoder.Result.Cancelled
    } catch (error: LinkageError) {
        vadFailure(error)
    } catch (error: Exception) {
        if (cancelled()) AudioTranscoder.Result.Cancelled else vadFailure(error)
    }
}

private fun vadFailure(error: Throwable) = AudioTranscoder.Result.Failure(
    AppStrings.get(R.string.vad_failed, "Unable to prepare audio with VAD: %1\$s", error.localizedMessage ?: error.javaClass.simpleName),
)
