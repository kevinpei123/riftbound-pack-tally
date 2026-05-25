package com.riftbound.packtally

import android.app.Application
import com.riftbound.packtally.core.carddb.CardDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CardDatabase.init(this)
    }
}
