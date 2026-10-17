package com.ghostlock.app.brevent

import android.util.Base64

/**
 * Builds the single-line shell command sent through the Brevent command channel.
 *
 * The vendor server runs the command but gives the client no stdin, so the GLK1
 * profile is inlined as base64 and decoded to a temp file that is then fed to
 * the binary's stdin. Everything user-controlled is single-quoted, so no value
 * can break out of the shell word. Kept pure so it can be unit tested.
 */
object BreventScript {
    const val WORK_DIR = "/data/local/tmp/.ghostlock"
    const val EXIT_MARKER = "GHOSTLOCK_EXIT:"

    fun buildCommand(
        binaryPath: String,
        stdin: ByteArray,
        args: List<String>,
        workDir: String = WORK_DIR,
    ): String {
        val encoded = Base64.encodeToString(stdin, Base64.NO_WRAP)
        val quotedArgs = args.joinToString(" ") { shellQuote(it) }
        return buildString {
            append("d=").append(workDir).append("; ")
            append("rm -rf $d; mkdir -p $d || exit 7; ")
            append("printf %s ").append(shellQuote(encoded))
                .append(" | base64 -d > $d/in.bin || exit 8; ")
            append("cp ").append(shellQuote(binaryPath)).append(" $d/ghostlock || exit 9; ")
            append("chmod 0755 $d/ghostlock || exit 9; ")
            append("cd $d || exit 9; ")
            append("GHOSTLOCK_HOME=$d TMPDIR=$d HOME=$d GHOSTLOCK_KSU_LOG=$d/ksu.log ")
            append("$d/ghostlock ")
            if (quotedArgs.isNotEmpty()) append(quotedArgs).append(' ')
            append("< $d/in.bin; ")
            append("echo ").append(EXIT_MARKER).append("$?")
        }
    }

    /** POSIX single-quote escaping: `'` -> `'\''`. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Reads the last `GHOSTLOCK_EXIT:<n>` marker from streamed output. */
    fun parseExit(output: String): Int? {
        val index = output.lastIndexOf(EXIT_MARKER)
        if (index < 0) return null
        val tail = output.substring(index + EXIT_MARKER.length).trimStart()
        val digits = tail.takeWhile { it.isDigit() || it == '-' }
        return digits.toIntOrNull()
    }
}
