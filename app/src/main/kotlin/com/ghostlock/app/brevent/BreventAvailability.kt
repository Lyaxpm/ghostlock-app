package com.ghostlock.app.brevent

import com.ghostlock.app.domain.model.BreventAuthorization
import com.ghostlock.app.domain.model.BreventStatus

/** Static facts about the installed Brevent package and its loopback server. */
data class BreventAvailability(
    val installed: Boolean = false,
    val versionName: String? = null,
    val versionCode: Long = 0L,
    val commandIntentResolvable: Boolean = false,
    val serverReachable: Boolean = false,
    val serverOk: Boolean = false,
    val probeError: String? = null,
)

/** Runtime facts proven by executing a command through the Brevent channel. */
data class BreventEnvironment(
    val authorization: BreventAuthorization = BreventAuthorization.NOT_PROBED,
    val uid: Int? = null,
    val error: String? = null,
    val rawOutput: String? = null,
)

/**
 * Pure readiness policy: maps the collected evidence to a [BreventStatus].
 *
 * The precedence is deliberate. A failure to even reach a later stage must not
 * mask an earlier, more actionable one, and [BreventStatus.READY] is reachable
 * only through [BreventAuthorization.GRANTED] plus a verified shell/root uid.
 */
object BreventReadiness {
    private val PrivilegedUids = setOf(0, 2000)

    fun resolve(
        selected: Boolean,
        checking: Boolean,
        error: String?,
        availability: BreventAvailability,
        environment: BreventEnvironment,
    ): BreventStatus = when {
        !selected -> BreventStatus.NOT_REQUIRED
        checking -> BreventStatus.CHECKING
        error != null -> BreventStatus.ERROR
        !availability.installed -> BreventStatus.NOT_INSTALLED
        !availability.serverReachable -> BreventStatus.SERVICE_NOT_RUNNING
        environment.authorization == BreventAuthorization.DENIED ->
            BreventStatus.AUTHORIZATION_REQUIRED

        environment.authorization == BreventAuthorization.GRANTED &&
            environment.uid in PrivilegedUids -> BreventStatus.READY

        else -> BreventStatus.EXECUTION_UNAVAILABLE
    }

    fun isRunnable(status: BreventStatus): Boolean = status == BreventStatus.READY
}
