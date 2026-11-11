package com.promax.mode.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Optional unknown-number screening. Android only binds this service after
 * the user grants Promax the Call Screening role.
 */
class PromaxCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val prefs = getSharedPreferences("promax_settings", MODE_PRIVATE)
        val incoming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            details.callDirection == Call.Details.DIRECTION_INCOMING
        val shouldReject = incoming && prefs.getBoolean("block_unknown_calls", false)

        val response = CallResponse.Builder()
            .setDisallowCall(shouldReject)
            .setRejectCall(shouldReject)
            .setSkipCallLog(false)
            .setSkipNotification(shouldReject)
            .build()
        respondToCall(details, response)
    }
}
