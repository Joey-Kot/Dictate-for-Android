package com.joeykot.dictate.audio

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.job.ConnectivityTestAudio
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.VadConfig
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Opt-in host JNI/FFmpeg integration. The native audio CI script supplies both binaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
class VadAudioIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun trimsOnlyOriginal48kSamplesAndPreservesTheRecording() {
        val ffmpeg = System.getenv("DICTATE_VAD_TEST_FFMPEG")
        assumeNotNull(ffmpeg)
        val app = RuntimeEnvironment.getApplication() as DictateApplication
        val dir = temporary.root
        Files.createSymbolicLink(File(dir, "libffmpeg.so").toPath(), File(checkNotNull(ffmpeg)).toPath())
        val context = object : ContextWrapper(app) {
            override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).apply { nativeLibraryDir = dir.path }
        }
        val transcoder = AudioTranscoder(context, app.diagnostics)
        val word = File(dir, "word.pcm").also { ConnectivityTestAudio.writeTo(app, it) }.readBytes()
        val pause = ByteArray(32000)
        val clip = pause + word + pause + word + pause
        val samples = ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val bytes = ByteBuffer.allocate(clip.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        // Distinct source frames above the detector's bandwidth expose any 16 kHz round trip.
        while (samples.hasRemaining()) {
            val value = samples.get().toInt().coerceIn(-32600, 32600)
            bytes.putShort((value + 100).toShort()).putShort((value - 100).toShort()).putShort(value.toShort())
        }
        val originalBytes = bytes.array()
        val original = File(dir, "original.pcm").apply { writeBytes(originalBytes) }
        val format = Pcm16Format(48000, 1)
        val config = AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.S16LE, bitDepth = 16, sampleRate = 48000)
        val plan = AudioEncodingPlan.resolve(config, format)
        val analysis = File(dir, "reference-16k.pcm")
        assertTrue(transcoder.transcode(1, original, format, analysis, AudioEncodingPlan.resolve(config.copy(sampleRate = 16000), format)) { false } is AudioTranscoder.Result.Success)
        val frames = original.length() / format.bytesPerFrame
        val ranges = checkNotNull(NativeVad.analyze(analysis.path, 48000, frames, 100, 0.6) { false })
        assertTrue("Both words must be detected", ranges.size >= 4)
        val expected = ByteArrayOutputStream()
        for (index in ranges.indices step 2) {
            expected.write(originalBytes, (ranges[index] * 2).toInt(), ((ranges[index + 1] - ranges[index]) * 2).toInt())
        }
        assertTrue(expected.size() in 1 until originalBytes.size)
        val output = File(dir, "upload.pcm")
        var observedOriginalCopy = false
        val result = transcoder.transcodeRecording(2, original, format, output, plan, VadConfig(enabled = true)) {
            val scratch = dir.listFiles().orEmpty().firstOrNull { it.name.startsWith("vad-2-") }
            if (scratch != null && File(scratch, "selected.pcm").isFile) {
                observedOriginalCopy = true
                assertFalse("Analysis PCM must already be released", File(scratch, "analysis-16k.pcm").exists())
            }
            false
        }
        assertTrue(observedOriginalCopy)
        assertEquals(AudioTranscoder.Result.Success(output), result)
        assertArrayEquals(expected.toByteArray(), output.readBytes())

        assertEquals(AudioTranscoder.Result.Success(output),
            transcoder.transcodeRecording(3, original, format, output, plan, VadConfig()) { false })
        assertArrayEquals(originalBytes, output.readBytes())
        assertArrayEquals(originalBytes, original.readBytes())

        val silence = File(dir, "silence.pcm").apply { writeBytes(pause) }
        val unused = File(dir, "no-speech.pcm")
        assertEquals(AudioTranscoder.RecordingResult.NoSpeech,
            transcoder.transcodeRecording(4, silence, Pcm16Format.LEGACY_MONO_16_KHZ, unused, plan, VadConfig(true)) { false })
        var checks = 0
        assertEquals(AudioTranscoder.Result.Cancelled,
            transcoder.transcodeRecording(5, silence, Pcm16Format.LEGACY_MONO_16_KHZ, unused, plan, VadConfig(true)) { ++checks >= 3 })
        assertFalse(unused.exists())
        assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith("vad-") })
    }
}
