package app.ewe

import android.app.Application
import app.ewe.data.LocalPrefs
import app.ewe.data.Repository
import app.ewe.data.Supabase

class EweApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = LocalPrefs(this)
        repo = Repository(this, prefs, Supabase(prefs))
    }

    companion object {
        lateinit var instance: EweApp
            private set
    }
}
