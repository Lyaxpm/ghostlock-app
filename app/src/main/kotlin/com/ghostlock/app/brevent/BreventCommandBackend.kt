package com.ghostlock.app.brevent

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Result of a full command exchange. [authorized] is true only when the server
 * actually streamed a [BreventResponse.Command]; the vendor "refused" sentinels
 * produce `authorized = false`.
 */
data class BreventCommandOutcome(
    val authorized: Boolean,
    val output: String,
    val timedOut: Boolean,
    val error: String?,
)

/**
 * Command transport abstraction. Keeping this an interface lets the state
 * machine be unit tested without a socket while the production implementation
 * talks to the real Brevent loopback server.
 */
interface BreventCommandBackend {
    fun probeServer(timeoutMs: Int = 1500): ServerProbe

    fun execute(
        command: String,
        timeoutMs: Int = 5000,
        overallTimeoutMs: Long = 15L * 60L * 1000L,
        onOutput: (String) -> Unit = {},
    ): BreventCommandOutcome
}

/**
 * Socket-backed backend. Each exchange opens a short-lived loopback connection,
 * exactly like the vendor client (`BreventCmd.WorkHandler.doRequest`): the
 * server streams one [BreventResponse.Command] per connection and the client
 * re-requests with `remain`/`done` until the command finishes. A token received
 * in a reply is fed back into the next request.
 */
class BreventSocketBackend(
    private val client: BreventProtocolClient,
) : BreventCommandBackend {
    @Volatile
    private var token: String = ""

    override fun probeServer(timeoutMs: Int): ServerProbe = client.probe(timeoutMs)

    override fun execute(
        command: String,
        timeoutMs: Int,
        overallTimeoutMs: Long,
        onOutput: (String) -> Unit,
    ): BreventCommandOutcome {
        val collected = StringBuilder()
        var current = command
        var force = true
        val deadline = System.currentTimeMillis() + overallTimeoutMs
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                return BreventCommandOutcome(
                    authorized = false,
                    output = collected.toString(),
                    timedOut = true,
                    error = "overall timeout",
                )
            }
            val response = try {
                client.command(current, token, force, timeoutMs)
            } catch (error: IOException) {
                return BreventCommandOutcome(
                    authorized = false,
                    output = collected.toString(),
                    timedOut = error is SocketTimeoutException,
                    error = error.message ?: error::class.simpleName,
                )
            }
            force = false
            when (response) {
                is BreventResponse.Command -> {
                    if (response.output.isNotEmpty()) {
                        collected.append(response.output)
                        onOutput(response.output)
                    }
                    if (response.token.isNotEmpty()) token = response.token
                    if (response.request.isNotEmpty()) current = response.request
                    if (response.done) {
                        return BreventCommandOutcome(
                            authorized = true,
                            output = collected.toString(),
                            timedOut = false,
                            error = null,
                        )
                    }
                    /* remain != 0 means more frames follow for the same request;
                     * remain == 0 with done == 0 means the caller must re-issue
                     * the request. Yield briefly so a broken server cannot spin
                     * this loop forever. */
                    if (response.remain == 0) {
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return BreventCommandOutcome(
                                authorized = true,
                                output = collected.toString(),
                                timedOut = false,
                                error = "interrupted",
                            )
                        }
                    }
                }

                BreventResponse.Ok,
                BreventResponse.BaseOk,
                BreventResponse.Empty,
                -> return BreventCommandOutcome(
                    authorized = false,
                    output = collected.toString(),
                    timedOut = false,
                    error = null,
                )

                is BreventResponse.Unknown -> return BreventCommandOutcome(
                    authorized = false,
                    output = collected.toString(),
                    timedOut = false,
                    error = "unexpected action ${response.action}",
                )
            }
        }
    }
}
