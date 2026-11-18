package com.lycoslane.purepodcast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lycoslane.purepodcast.core.preferences.LocaleHelper
import com.lycoslane.purepodcast.ui.PurePodcastApp
import com.lycoslane.purepodcast.ui.theme.PurePodcastTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val uiPrefs = getSharedPreferences("ui_state", MODE_PRIVATE)
            if (!uiPrefs.getBoolean("notif_permission_asked", false) &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Ask only once; repeated prompts are annoying and the OS stops showing them anyway.
                uiPrefs.edit().putBoolean("notif_permission_asked", true).apply()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }

        val app = application as PurePodcastApplication
        setContent {
            PurePodcastTheme {
                PurePodcastApp(app)
            }
        }
    }
}
