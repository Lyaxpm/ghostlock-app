package com.ghostlock.app.brevent

import android.content.Context
import com.ghostlock.app.BuildConfig
import com.ghostlock.app.domain.model.CpuPair
import java.io.File

/**
 * Brevent backend runner.
 *
 * Runs the native exploit through the Brevent command channel after the state
 * machine proved a shell/root uid over that channel. There is deliberately no
 * local `ProcessBuilder` fallback: if the channel is not usable the caller must
 * not run, because doing so would be an app-uid process with seccomp still on.
 */
class BreventExecRunner(private val context: Context) {
    fun run(
        pair: CpuPair,
        forceAttack: Boolean,
        profileBlob: ByteArray,
        debugDir: String?,
        backend: BreventCommandBackend,
        onLog: (String) -> Unit,
    ): Int {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.isFile) {
            onLog("<s> error: missing GhostLock binary: ${binary.absolutePath}")
            return 1
        }

        val args = mutableListOf("--ghostlock-app-call")
        if (forceAttack) args += "--force-attack"
        if (!debugDir.isNullOrEmpty()) args += listOf("--dump-kernel-log", debugDir)

        val command = BreventScript.buildCommand(
            binaryPath = binary.absolutePath,
            stdin = frame(profileBlob),
            args = args,
        )

        onLog("<b> Brevent backend selected (uid verified over command channel)")
        if (BuildConfig.DEBUG) {
            onLog("<s> cpu pair ${pair.primary}/${pair.consumer}")
        }

        val outcome = backend.execute(command, onOutput = { chunk -> emit(chunk, onLog) })
        if (!outcome.authorized) {
            onLog(
                "<s> error: Brevent refused the command" +
                    (outcome.error?.let { " ($it)" } ?: ""),
            )
            return 1
        }

        val exitCode = BreventScript.parseExit(outcome.output)
        if (exitCode == null) {
            onLog("<-> warning: could not read an exit code from the Brevent output")
            return 1
        }
        onLog("<b> native exited code=$exitCode")
        return exitCode
    }

    private fun emit(chunk: String, onLog: (String) -> Unit) {
        var start = 0
        for (index in chunk.indices) {
            if (chunk[index] == '\n') {
                if (index > start) onLog(chunk.substring(start, index))
                start = index + 1
            }
        }
        if (start < chunk.length) onLog(chunk.substring(start))
    }

    /** GLK1 stdin framing: 4-byte big-endian length followed by the blob. */
    private fun frame(blob: ByteArray): ByteArray {
        val size = blob.size
        return ByteArray(4 + size).also { framed ->
            framed[0] = (size ushr 24).toByte()
            framed[1] = (size ushr 16).toByte()
            framed[2] = (size ushr 8).toByte()
            framed[3] = size.toByte()
            blob.copyInto(framed, destinationOffset = 4)
        }
    }

    private companion object {
        const val BINARY_NAME = "libghostlock.so"
    }
}
