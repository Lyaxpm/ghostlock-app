package com.ghostlock.app.domain.model

data class CpuPair(val primary: Int, val consumer: Int) {
    override fun toString(): String = "$primary,$consumer"
}

data class KernelSnapshot(
    val deviceName: String,
    val kernelRelease: String,
    val socName: String = "",
    val kernelSupported: Boolean,
    val cpuPairs: List<CpuPair>,
    val cpuPairLabels: List<String>,
    val selectedCpuPair: Int,
    val safeModeEnabled: Boolean,
    /** Skip the pre-attack KernelSU check and run the exploit as a test. */
    val forceAttackTest: Boolean = false,
    /** Profile/imported offsets force the Brevent path. */
    val recommendBrevent: Boolean,
    /** User-selected Brevent path for kernels that do not require it. */
    val breventEnabled: Boolean = false,
    val breventStatus: BreventStatus,
)

/**
 * Honest Brevent readiness. Every value except [READY] is a distinct,
 * observable failure; [READY] is only reached after a command actually ran over
 * the vendor loopback channel and reported a shell/root uid.
 */
enum class BreventStatus {
    /** The user has not selected the Brevent path. */
    NOT_REQUIRED,

    /** `me.piebridge.brevent` is not installed. */
    NOT_INSTALLED,

    /** Installed, but nothing is listening on the loopback command port. */
    SERVICE_NOT_RUNNING,

    /** A check is in flight. */
    CHECKING,

    /** The server is up but refused the command (vendor token/DEVICE_POWER). */
    AUTHORIZATION_REQUIRED,

    /** The server answered, but the execution environment could not be proven. */
    EXECUTION_UNAVAILABLE,

    /** A command ran and verified a shell/root uid. */
    READY,

    /** The check itself failed (unexpected error). */
    ERROR,
}

/** Whether the Brevent command channel actually accepted a command. */
enum class BreventAuthorization { NOT_PROBED, GRANTED, DENIED, ERROR }

/**
 * Full, copyable record of the last Brevent check. It is intentionally verbose
 * and lists the evidence behind [status]; nothing here is inferred from the
 * fact that Brevent is merely installed.
 */
data class BreventDiagnostics(
    val status: BreventStatus,
    val packageName: String = "me.piebridge.brevent",
    val installed: Boolean = false,
    val versionName: String? = null,
    val versionCode: Long = 0L,
    val commandIntentResolvable: Boolean = false,
    val serverReachable: Boolean = false,
    val serverOk: Boolean = false,
    val probeError: String? = null,
    val authorization: BreventAuthorization = BreventAuthorization.NOT_PROBED,
    val verifiedUid: Int? = null,
    val verifiedOutput: String? = null,
    val notes: List<String> = emptyList(),
) {
    fun report(): String = buildString {
        appendLine("GhostLock Brevent diagnostics")
        appendLine("status: ${status.name}")
        appendLine("package: $packageName")
        appendLine("installed: $installed")
        appendLine("versionName: ${versionName ?: "(unknown)"}")
        appendLine("versionCode: $versionCode")
        appendLine("commandIntentResolvable: $commandIntentResolvable")
        appendLine("serverReachable: $serverReachable (loopback:${ BreventPort })")
        appendLine("serverHandshakeOk: $serverOk")
        appendLine("probeError: ${probeError ?: "(none)"}")
        appendLine("authorization: ${authorization.name}")
        appendLine("verifiedUid: ${verifiedUid ?: "(none)"}")
        appendLine("verifiedOutput: ${verifiedOutput?.trim() ?: "(none)"}")
        if (notes.isNotEmpty()) {
            appendLine("notes:")
            notes.forEach { appendLine("  - $it") }
        }
    }

    private companion object {
        const val BreventPort = 59526
    }
}

enum class LogTone { Default, Error, Success, Warning, Progress, Kotlin, Brevent }

data class LogEntry(val text: String, val tone: LogTone)

data class OffsetCandidate(val release: String, val document: String)

/** One verbatim user-imported document in the user profile folder. */
data class UserProfileFile(
    val name: String,
    val releases: List<String>,
    val importedAt: Long,
    val sizeBytes: Long,
    val parseError: Boolean,
    /** 1 = legacy layout (load only), 2 = current layout (editable). */
    val version: Int = 2,
)

/** One editable advisory execution value (PROFILE-SUGGEST-01 / profile-ui). */
data class ExecutionFieldValue(
    val path: String,
    val value: Long,
    val overridden: Boolean,
)

/** Debug-only export preferences shown by the hidden debug screen. */
data class DebugSettings(
    val exportEnabled: Boolean = true,
    val exportLocation: String = "Download/ghostlock-debug-log",
    val kernelLogEnabled: Boolean = true,
)

/** One node of the resolved profile tree: a JSON group or a numeric leaf. */
data class ProfileFieldNode(
    val path: String,
    val name: String,
    val value: Long? = null,
    /** True when this field or any descendant has an explicit override. */
    val overridden: Boolean = false,
    val children: List<ProfileFieldNode> = emptyList(),
) {
    val isGroup: Boolean get() = children.isNotEmpty()
}

/** Resolved execution view for the advanced editor (controller-owned). */
data class ProfileConfig(
    val release: String,
    val hasProfile: Boolean,
    /** Hierarchical view of every numeric leaf, override flags included. */
    val roots: List<ProfileFieldNode> = emptyList(),
    /** General (execution tuning) subset exposed by the parameters screen. */
    val general: List<ExecutionFieldValue> = emptyList(),
    /** Explicit route from the profile; null means geometry inference. */
    val route: String? = null,
    /** Declared fallback route ("none"/"<route>"); null means unset. */
    val fallbackTo: String? = null,
    /** Dotted paths whose resolved value violates the geometry rules. */
    val invalidPaths: Set<String> = emptySet(),
) {
    companion object {
        /** Routes a profile may declare ("" is the inference fallback). */
        val Routes = listOf("tcp_zerocopy", "select_stack", "multicast_waiter")

        /**
         * Route-independent execution tuning paths the general editor always
         * exposes. Route-specific tuning is appended dynamically from the
         * resolved profile for the active route (plus its fallback), so a
         * profile never shows another route's knobs.
         */
        val GeneralPaths = listOf(
            "execution.selected_cpus.main",
            "execution.selected_cpus.consumer",
            "execution.heap.prepare_max_attempts",
            "execution.heap.prepare_timeout_ms",
            "execution.heap.kernelsnitch_timeout_ms",
            "execution.race.route_wait_ms",
            "execution.race.route_done_timeout_ms",
            "execution.race.setup_settle_us",
            "execution.race.state_poll_interval_us",
            "execution.stages.w1_attempts",
            "execution.stages.w1_settle_us",
            "execution.stages.w1_scratch_repair_attempts",
            "execution.stages.w2_attempts",
            "execution.stages.w2_settle_us",
            "execution.stages.w3_chain_rounds",
            "execution.stages.w3_attempts",
            "execution.stages.w3_settle_us",
            "execution.handoff.pre_dispatch_settle_ms",
            "execution.handoff.module_poll_attempts",
            "execution.handoff.module_poll_interval_ms",
            "execution.handoff.enforce_poll_attempts",
            "execution.handoff.enforce_poll_interval_ms",
        )
    }
}

data class KernelOffsets(
    val release: String,
    val scalars: Map<String, Long?>,
    val symbols: Map<String, Long?>,
    val structFields: Map<String, Long?>,
)

sealed interface OffsetImportResult {
    data class Imported(val releases: List<String>) : OffsetImportResult
    data class RequiresOverwrite(val releases: List<String>) : OffsetImportResult
    data object AlreadyPresent : OffsetImportResult
    data class MissingIncludes(val files: List<String>) : OffsetImportResult
    data class Failed(val reason: String) : OffsetImportResult
}

sealed interface ParseResult {
    /** Fields only an xbl_config/uefi sidecar can supply (e.g.
     * `kernel_phys_load`) that the parsed profile is still missing. */
    data class Parsed(
        val releases: List<String>,
        val missing: Set<String> = emptySet(),
        /** Name of the stored document, so the caller can auto-load it. */
        val documentName: String? = null,
    ) : ParseResult

    data class RequiresOverwrite(
        val releases: List<String>,
        val missing: Set<String> = emptySet(),
    ) : ParseResult

    data object AlreadyPresent : ParseResult

    data class Failed(val code: Int, val reason: String? = null) : ParseResult
}
