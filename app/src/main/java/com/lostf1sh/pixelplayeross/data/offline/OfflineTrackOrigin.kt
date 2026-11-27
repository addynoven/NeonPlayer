package com.lostf1sh.pixelplayeross.data.offline

/**
 * Why an app-owned copy of a cloud track exists.
 *
 * [USER] is an explicit download or a retried one: the listener asked for that file, so nothing
 * may delete it behind their back. [FAVORITE] and [RECENT] are filled in by
 * [OfflineAutoSaveManager] and may be evicted when the storage budget is full.
 */
enum class OfflineTrackOrigin(val storageValue: String) {
    USER("user"),
    FAVORITE("favorite"),
    RECENT("recent");

    companion object {
        fun fromStorage(value: String?): OfflineTrackOrigin =
            entries.firstOrNull { it.storageValue == value } ?: USER
    }
}