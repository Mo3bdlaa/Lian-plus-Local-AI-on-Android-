package com.lian.plus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.lian.plus.ui.LianRoot
import com.lian.plus.ui.theme.LianTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launcher shows Theme.LianPlus.Splash (the wordmark on the brand
        // background) for the cold-start frame; swap to the plain theme before
        // the first Compose frame so that artwork is not still behind the UI.
        setTheme(R.style.Theme_LianPlus)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only needed so the API server's foreground notification is visible;
        // everything else works without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LianTheme {
                LianRoot()
            }
        }
    }
}
