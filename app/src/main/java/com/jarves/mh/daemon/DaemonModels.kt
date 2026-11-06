package com.jarves.mh.daemon

import androidx.annotation.StringRes
import com.jarves.mh.R

/**
 * Operating mode for executing agent tasks and terminal commands.
 */
enum class RuntimeMode(
    val stableId: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    AUTO_DETECT(
        stableId = "auto_detect",
        titleRes = R.string.runtime_mode_auto,
        descriptionRes = R.string.runtime_mode_auto_desc,
    ),
    SHARED_DAEMON(
        stableId = "shared_daemon",
        titleRes = R.string.runtime_mode_shared,
        descriptionRes = R.string.runtime_mode_shared_desc,
    ),
    STANDALONE_PROOT(
        stableId = "standalone_proot",
        titleRes = R.string.runtime_mode_standalone,
        descriptionRes = R.string.runtime_mode_standalone_desc,
    );

    companion object {
        fun fromStored(value: String?): RuntimeMode =
            entries.firstOrNull { it.stableId == value || it.name.equals(value, ignoreCase = true) }
                ?: AUTO_DETECT
    }
}

/**
 * Current availability status of the AntiGem daemon on localhost.
 */
enum class DaemonStatus {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    NOT_INSTALLED,
}

/**
 * Snapshot of AntiGem daemon discovery and health.
 */
data class DaemonDiscoveryState(
    val status: DaemonStatus = DaemonStatus.CHECKING,
    val activePort: Int? = null,
    val installedPackage: String? = null,
    val lastCheckedMillis: Long = 0L,
    val isChecking: Boolean = false,
) {
    val isAvailable: Boolean get() = status == DaemonStatus.AVAILABLE && activePort != null
}

/**
 * Command execution result from the daemon.
 */
data class DaemonCommandResult(
    val output: String,
    val exitCode: Int,
    val cwd: String,
)
