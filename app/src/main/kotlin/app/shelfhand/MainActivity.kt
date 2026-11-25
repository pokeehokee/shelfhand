package app.shelfhand

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.shelfhand.catalog.FindModel
import app.shelfhand.ui.ShelfhandRoot
import app.shelfhand.ui.PlayerModel

class MainActivity : ComponentActivity() {
    private val model: PlayerModel by viewModels()
    private val find: FindModel by viewModels()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // A provider can refuse a lasting grant; say so rather than scan a folder we will lose on restart.
            runCatching { model.library.setRoot(uri) }
                .onSuccess { model.scan() }
                .onFailure { model.scanMessage.value = "Android would not let Shelfhand keep access to that folder. Try a folder under Download or Audiobooks." }
        }
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Media playback notifications need this on Android 13+, or playback controls vanish from the shade.
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { ShelfhandRoot(model, find, onPickFolder = { pickFolder.launch(null) }) }
    }
}
