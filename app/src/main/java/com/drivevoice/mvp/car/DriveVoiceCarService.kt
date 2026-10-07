package com.drivevoice.mvp.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class DriveVoiceCarService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // ALLOW_ALL is for development only. Release builds use Google's sample
        // Android Auto / Automotive host allow-list as the safer baseline.
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = DriveVoiceCarSession()
}
