package com.pocketlinux.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder

class LinuxRuntimeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
