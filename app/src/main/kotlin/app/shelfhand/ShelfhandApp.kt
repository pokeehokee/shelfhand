package app.shelfhand

import android.app.Application
import app.shelfhand.data.Library
import app.shelfhand.data.ShelfDb

class ShelfhandApp : Application() {
    val db: ShelfDb by lazy { ShelfDb.open(this) }
    val library: Library by lazy { Library(this, db) }
}

val android.content.Context.shelf: ShelfhandApp get() = applicationContext as ShelfhandApp
