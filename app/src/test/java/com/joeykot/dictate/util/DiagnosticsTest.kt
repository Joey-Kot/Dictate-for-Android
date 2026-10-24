package com.joeykot.dictate.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsTest {
    @Test
    fun panelShowsMoreThanEightyEntriesAndReloadsThePersistedLog() {
        val context = RuntimeEnvironment.getApplication()
        val original = Diagnostics(context)
        original.clear()
        repeat(250) { original.info("http", "request=$it") }
        val reloaded = Diagnostics(context)
        assertEquals(original.snapshot(), reloaded.snapshot())
        assertEquals(250, reloaded.snapshot().lines().size)
        assertTrue(reloaded.snapshot().contains("request=0\n"))
        assertEquals(3, reloaded.snapshot(3).lines().size)
        reloaded.clear()
        assertEquals("", Diagnostics(context).snapshot())
    }

    @Test
    fun expandedLogsRemainBoundedAndKeepRecentFailuresAcrossRotation() {
        val context = RuntimeEnvironment.getApplication()
        val diagnostics = Diagnostics(context)
        diagnostics.clear()
        repeat(2_200) { diagnostics.info("http", "request=$it " + "metadata ".repeat(80)) }
        diagnostics.error("http", "latest failure Bearer private-token")
        assertEquals(2_000, diagnostics.snapshot().lines().size)
        assertTrue(File(context.filesDir, "diagnostics.log").length() < 514 * 1024)
        val persisted = Diagnostics(context).snapshot()
        assertTrue(persisted.contains("latest failure Bearer [REDACTED]"))
        assertFalse(persisted.contains("private-token"))
        assertFalse(persisted.contains("request=0 "))
    }
}
