package com.drdisagree.teledrive.core.update

/**
 * Never reports an update on iOS. The JVM checker reads the upstream TeleDrive releases, which do
 * not apply to Strato, and Strato is sideloaded from pre-releases that a "latest stable" check
 * would skip anyway.
 */
actual class UpdateChecker actual constructor(
    @Suppress("unused") private val currentVersion: String
) {
    actual suspend fun newerRelease(): AppRelease? = null
}
