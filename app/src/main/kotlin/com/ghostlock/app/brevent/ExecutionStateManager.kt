package com.ghostlock.app.brevent

import com.ghostlock.app.domain.model.BreventAuthorization
import com.ghostlock.app.domain.model.BreventDiagnostics
import com.ghostlock.app.domain.model.BreventStatus

/**
 * Single authority for the cached Brevent state. A refresh does blocking I/O
 * (loopback probe + one command) so callers must run it off the main thread;
 * [status] and [diagnostics] are cheap reads of the cached result.
 */
class ExecutionStateManager(
    private val checker: BreventAvailabilityChecker,
    private val backendFactory: (Long) -> BreventCommandBackend,
) {
    @Volatile
    private var availability = BreventAvailability()

    @Volatile
    private var environment = BreventEnvironment()

    @Volatile
    private var checking = false

    @Volatile
    private var error: String? = null

    @Volatile
    private var selected = false

    @Volatile
    private var backend: BreventCommandBackend? = null

    fun status(): BreventStatus =
        BreventReadiness.resolve(selected, checking, error, availability, environment)

    /** Backend from the last successful refresh, or null when none is usable. */
    fun currentBackend(): BreventCommandBackend? = backend

    fun markNotRequired() {
        selected = false
        checking = false
        error = null
        availability = BreventAvailability()
        environment = BreventEnvironment()
        backend = null
    }

    fun refresh(selected: Boolean): BreventStatus {
        this.selected = selected
        if (!selected) {
            markNotRequired()
            return BreventStatus.NOT_REQUIRED
        }
        checking = true
        error = null
        try {
            val pkg = checker.checkPackage()
            availability = pkg
            if (!pkg.installed) {
                environment = BreventEnvironment()
                backend = null
                return status()
            }
            val candidate = backendFactory(pkg.versionCode)
            backend = candidate
            val probe = candidate.probeServer()
            availability = pkg.copy(
                serverReachable = probe.reachable,
                serverOk = probe.ok,
                probeError = probe.error,
            )
            environment = if (probe.reachable) {
                ExecutionEnvironmentVerifier(candidate).verify()
            } else {
                BreventEnvironment()
            }
        } catch (throwable: Throwable) {
            error = throwable.message ?: throwable::class.simpleName
            backend = null
        } finally {
            checking = false
        }
        return status()
    }

    fun diagnostics(): BreventDiagnostics = BreventDiagnostics(
        status = status(),
        installed = availability.installed,
        versionName = availability.versionName,
        versionCode = availability.versionCode,
        commandIntentResolvable = availability.commandIntentResolvable,
        serverReachable = availability.serverReachable,
        serverOk = availability.serverOk,
        probeError = availability.probeError,
        authorization = environment.authorization,
        verifiedUid = environment.uid,
        verifiedOutput = environment.rawOutput,
        notes = notes(),
    )

    private fun notes(): List<String> = buildList {
        if (!availability.installed) {
            add("Install and launch Brevent once so its server is set up.")
        } else if (!availability.serverReachable) {
            add("Brevent is installed but nothing answered on loopback:59526; " +
                "start Brevent (its command server must be running) and check again.")
        }
        if (availability.serverReachable && !availability.serverOk) {
            add("The loopback port answered but the handshake did not match; " +
                "the installed server may differ from the open-source v2.8.6 protocol.")
        }
        when (environment.authorization) {
            BreventAuthorization.DENIED -> add(
                "Brevent refused the command. Its command channel is gated behind " +
                    "android.permission.DEVICE_POWER (signature), which a third-party " +
                    "app cannot hold; this cannot be granted from GhostLock.",
            )

            BreventAuthorization.ERROR -> add(
                "The command ran but the environment could not be verified: " +
                    "${environment.error ?: "unknown error"}.",
            )

            BreventAuthorization.GRANTED -> add("Verified a ${environment.uid} uid through the Brevent channel.")
            BreventAuthorization.NOT_PROBED -> Unit
        }
        if (availability.installed && !availability.commandIntentResolvable) {
            add("Brevent's command activity is not resolvable; the installed app may " +
                "hide it from the <queries> declaration.")
        }
        add("Use direct mode if Brevent cannot provide a verified shell here.")
    }
}
