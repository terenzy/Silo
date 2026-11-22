package dev.silo.app

import android.app.Application
import dev.silo.app.data.SiloRepository

class SiloApp : Application() {
    val repository: SiloRepository by lazy { SiloRepository() }
}
