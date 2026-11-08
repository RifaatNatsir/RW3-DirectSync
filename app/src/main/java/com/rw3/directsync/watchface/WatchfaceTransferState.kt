package com.rw3.directsync.watchface

/**
 * State of watchface operations and transfer progress on Redmi Watch 3.
 */
sealed class WatchfaceTransferState {
    data object Idle : WatchfaceTransferState()
    data object QueryingList : WatchfaceTransferState()
    data class Selecting(val id: String) : WatchfaceTransferState()
    data class Deleting(val id: String) : WatchfaceTransferState()
    data class PreparingUpload(val watchfaceId: String, val totalBytes: Int) : WatchfaceTransferState()
    data class Uploading(
        val watchfaceId: String,
        val bytesSent: Int,
        val totalBytes: Int,
        val percent: Int
    ) : WatchfaceTransferState()
    data class Verifying(val watchfaceId: String) : WatchfaceTransferState()
    data class Success(val watchfaceId: String, val message: String) : WatchfaceTransferState()
    data class Error(val message: String) : WatchfaceTransferState()
}

