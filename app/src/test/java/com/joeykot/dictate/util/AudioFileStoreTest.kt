package com.joeykot.dictate.util

import android.content.Context
import android.content.ContextWrapper
import com.joeykot.dictate.model.Pcm16Format
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AudioFileStoreTest {
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun cleanupDiscardsLegacySegmentPlansAndPreservesTheirOriginalPcm() {
        val store = newStore()
        val recording = promote(store, jobId = 1L)
        val original = recording.file.readBytes()
        val sidecar = snapshotFile(recording).apply { writeText("obsolete plan") }
        store.cleanupTemporaryFiles()
        assertFalse(sidecar.exists())
        assertEquals(recording, store.lastRecording())
        assertTrue(original.contentEquals(recording.file.readBytes()))
    }

    @Test
    fun temporaryCleanupRecursivelyRemovesAbandonedSegmentDirectories() {
        val store = newStore()
        val abandoned = File(store.segmentedUploadTemporaryRoot(), "segmented-upload-abandoned").apply { mkdirs() }
        File(abandoned, "segment-000000.wav").writeBytes(byteArrayOf(1, 2, 3))

        store.cleanupTemporaryFiles()

        assertFalse(abandoned.exists())
    }

    private fun promote(store: AudioFileStore, jobId: Long): AudioFileStore.RawRecording {
        val raw = store.newRawFile(jobId).apply { writeBytes(ByteArray(6_400) { 7 }) }
        return checkNotNull(store.promoteToLast(raw, Pcm16Format.LEGACY_MONO_16_KHZ))
    }

    private fun snapshotFile(recording: AudioFileStore.RawRecording): File =
        File(recording.file.parentFile, "${recording.file.name}.segmented-upload.json")

    private fun newStore(): AudioFileStore {
        val root = Files.createTempDirectory("dictate-audio-store-test-").toFile()
        roots += root
        return AudioFileStore(TestContext(RuntimeEnvironment.getApplication(), root))
    }

    private class TestContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
