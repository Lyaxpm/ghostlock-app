package com.ghostlock.app.brevent

import com.ghostlock.app.domain.model.BreventAuthorization

/**
 * Proves the runtime execution environment through the real Brevent channel.
 *
 * This is the only thing that may turn the state into
 * [com.ghostlock.app.domain.model.BreventStatus.READY]: a genuine command must
 * run through the vendor loopback server and report a shell/root uid. The fact
 * that Brevent is installed, or that WRITE_SECURE_SETTINGS was granted, is
 * never used as evidence of shell access.
 */
class ExecutionEnvironmentVerifier(private val backend: BreventCommandBackend) {
    fun verify(): BreventEnvironment {
        val outcome = backend.execute(
            command = PROBE_COMMAND,
            timeoutMs = PROBE_TIMEOUT_MS,
            overallTimeoutMs = PROBE_OVERALL_MS,
        )
        return when {
            outcome.timedOut -> BreventEnvironment(
                authorization = BreventAuthorization.ERROR,
                error = outcome.error ?: "timeout",
                rawOutput = outcome.output.trim().ifEmpty { null },
            )

            !outcome.authorized -> BreventEnvironment(
                authorization = BreventAuthorization.DENIED,
                error = outcome.error,
                rawOutput = outcome.output.trim().ifEmpty { null },
            )

            else -> {
                val uid = parseUid(outcome.output)
                if (uid != null) {
                    BreventEnvironment(
                        authorization = BreventAuthorization.GRANTED,
                        uid = uid,
                        rawOutput = outcome.output.trim(),
                    )
                } else {
                    BreventEnvironment(
                        authorization = BreventAuthorization.ERROR,
                        error = "unparsable uid output",
                        rawOutput = outcome.output.trim().ifEmpty { null },
                    )
                }
            }
        }
    }

    companion object {
        /** `id -u` prints a single integer on toybox. */
        const val PROBE_COMMAND = "id -u"
        const val PROBE_TIMEOUT_MS = 5000
        const val PROBE_OVERALL_MS = 10_000L

        private val UidPattern = Regex("(?m)^\\s*(-?\\d+)\\s*$")

        fun parseUid(output: String): Int? {
            UidPattern.find(output)?.let { return it.groupValues[1].toIntOrNull() }
            return Regex("-?\\d+").find(output)?.value?.toIntOrNull()
        }
    }
}
