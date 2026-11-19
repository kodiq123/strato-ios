package com.drdisagree.teledrive.core.update

/** Looks for a newer stable release of the app than [currentVersion]. */
expect class UpdateChecker(currentVersion: String) {
    /** The newer release, or null when there is none or the check failed. */
    suspend fun newerRelease(): AppRelease?
}
