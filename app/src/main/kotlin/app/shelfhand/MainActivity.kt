package app.shelfhand

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.shelfhand.ui.ShelfhandRoot
import app.shelfhand.ui.PlayerModel

class MainActivity : ComponentActivity() {
    private val model: PlayerModel by viewModels()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { model.library.setRoot(uri); model.scan() }
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Media playback notifications need this on Android 13+, or playback controls vanish from the shade.
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { ShelfhandRoot(model, onPickFolder = { pickFolder.launch(null) }) }
    }
}
