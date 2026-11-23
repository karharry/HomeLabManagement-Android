package com.techloungegr.homelabmanagement

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.techloungegr.homelabmanagement.data.Store
import com.techloungegr.homelabmanagement.session.Scripts
import com.techloungegr.homelabmanagement.session.SessionManager
import com.techloungegr.homelabmanagement.ui.HomeLabApp

class HomeLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this); Scripts.init(this)
        SessionManager.load()
        // Poll only while the app is visible; sessions are closed cleanly when it goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { SessionManager.setForeground(true) }
            override fun onStop(owner: LifecycleOwner) {
                SessionManager.setForeground(false)
                try { com.techloungegr.homelabmanagement.widget.WidgetData.save(com.techloungegr.homelabmanagement.widget.WidgetData.fromSessions()) } catch (_: Exception) {}
            }
        })
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HomeLabApp() }
    }
}
