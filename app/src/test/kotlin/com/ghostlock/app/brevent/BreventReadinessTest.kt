package com.ghostlock.app.brevent

import com.ghostlock.app.domain.model.BreventAuthorization
import com.ghostlock.app.domain.model.BreventStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BreventReadinessTest {

    private val readyEnv = BreventEnvironment(
        authorization = BreventAuthorization.GRANTED,
        uid = 2000,
        rawOutput = "2000",
    )

    private val zeroUidEnv = BreventEnvironment(
        authorization = BreventAuthorization.GRANTED,
        uid = 0,
        rawOutput = "0",
    )

    private val deniedEnv = BreventEnvironment(
        authorization = BreventAuthorization.DENIED,
        rawOutput = "BreventOK",
    )

    private val reachableAvail = BreventAvailability(
        installed = true,
        serverReachable = true,
        serverOk = true,
        versionCode = 2018050400L,
    )

    private val reachableButMismatchedAvail = BreventAvailability(
        installed = true,
        serverReachable = true,
        serverOk = false,
        probeError = "unexpected handshake",
    )

    private val installedButUnreachableAvail = BreventAvailability(
        installed = true,
        serverReachable = false,
    )

    private val notInstalledAvail = BreventAvailability(installed = false)

    @Test
    fun notSelected_returnsNotRequired() {
        val status = BreventReadiness.resolve(
            selected = false,
            checking = false,
            error = null,
            availability = notInstalledAvail,
            environment = BreventEnvironment(),
        )
        assertEquals(BreventStatus.NOT_REQUIRED, status)
    }

    @Test
    fun checking_isTakenFirst() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = true,
            error = "anything",
            availability = installedButUnreachableAvail,
            environment = BreventEnvironment(),
        )
        assertEquals(BreventStatus.CHECKING, status)
    }

    @Test
    fun errorOverridesAllButCheckingNotSelected() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = "failure",
            availability = reachableAvail,
            environment = readyEnv,
        )
        assertEquals(BreventStatus.ERROR, status)
    }

    @Test
    fun notInstalled() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = null,
            availability = notInstalledAvail,
            environment = BreventEnvironment(),
        )
        assertEquals(BreventStatus.NOT_INSTALLED, status)
    }

    @Test
    fun installedButNotReachable() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = null,
            availability = installedButUnreachableAvail,
            environment = BreventEnvironment(),
        )
        assertEquals(BreventStatus.SERVICE_NOT_RUNNING, status)
    }

    @Test
    fun deniedAuthorization() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = null,
            availability = reachableAvail,
            environment = deniedEnv,
        )
        assertEquals(BreventStatus.AUTHORIZATION_REQUIRED, status)
    }

    @Test
    fun reachableButHandshakeUnconfirmedIsStillReachable() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = null,
            availability = reachableButMismatchedAvail,
            environment = readyEnv,
        )
        assertEquals(BreventStatus.READY, status)
    }

    @Test
    fun grantedRootUid() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = null,
            availability = reachableAvail,
            environment = zeroUidEnv,
        )
        assertEquals(BreventStatus.READY, status)
    }

    @Test
    fun grantedShellUid() {
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = null,
            availability = reachableAvail,
            environment = readyEnv,
        )
        assertEquals(BreventStatus.READY, status)
    }

    @Test
    fun unprivilegedUidButAuthorized() {
        val env = BreventEnvironment(
            authorization = BreventAuthorization.GRANTED,
            uid = 10000,
            rawOutput = "10000",
        )
        val status = BreventReadiness.resolve(
            selected = true,
            checking = false,
            error = null,
            availability = reachableAvail,
            environment = env,
        )
        assertEquals(BreventStatus.EXECUTION_UNAVAILABLE, status)
    }
}
