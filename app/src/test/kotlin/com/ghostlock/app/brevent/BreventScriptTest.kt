package com.ghostlock.app.brevent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreventScriptTest {
    @Test
    fun shellQuoteEscapesQuotes() {
        assertEquals("'a'\\''b'", BreventScript.shellQuote("a'b"))
        assertEquals("''", BreventScript.shellQuote(""))
    }

    @Test
    fun parseExitFindsMarker() {
        val output = "some text\nGHOSTLOCK_EXIT:5\nmore"
        assertEquals(5, BreventScript.parseExit(output))
    }

    @Test
    fun parseExitReturnsLastMarker() {
        val output = "GHOSTLOCK_EXIT:1\nGHOSTLOCK_EXIT:-2"
        assertEquals(-2, BreventScript.parseExit(output))
    }

    @Test
    fun parseExitMissingReturnsNull() {
        assertEquals(null, BreventScript.parseExit("no marker"))
    }

    @Test
    fun buildCommandProducesValidScript() {
        val cmd = BreventScript.buildCommand(
            binaryPath = "/data/app/~~id/lib/arm64/libghostlock.so",
            stdin = byteArrayOf(0x01, 0x02, 0x03),
            args = listOf("--ghostlock-app-call", "--force-attack"),
        )
        assertTrue(cmd.contains("rm -rf"))
        assertTrue(cmd.contains("base64 -d >"))
        assertTrue(cmd.contains("GHOSTLOCK_EXIT:$?"))
    }
}
