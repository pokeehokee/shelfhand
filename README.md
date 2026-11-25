# Shelfhand

An Android audiobook player that keeps its own library. Design: see the Shelfhand design page.

## What works in this first cut

- **Library import.** Pick your audiobook folder once (e.g. `Download/audiobooks`). Each folder that holds audio files is a book; author, series and number are read from the path (`Author/Series 03 - Title`, `Author/Series/Book 3 - Title`, `Author/Title`, `Author - Title`). Files play in natural order, so chapter 10 comes after chapter 9.
- **Player.** Background playback with lock-screen, notification and Bluetooth controls; pauses when headphones come out; ±30 s; speed 1-2x (0.5-3x supported); chapter list; sleep timer (minutes or end of chapter) with a 10-second fade and +5 min.
- **Resume.** Position saved every 10 s, on pause and on chapter change; the app reopens where you stopped. Steps back a little on resume, more the longer you were away.
- **Finished.** Recorded when playback reaches the last 2% (or last minute), or when you tap Mark finished. When a book ends, an Up next dialog offers the next book in the series, then books in progress.

- **Find tab**: the whole LibriVox catalog on the phone, filtered by length, series or standalone, genres combined (all/any) or excluded, and language, sorted by title, author, length or newest.

## Not yet

- Chapters inside a single `.m4b` (it plays as one chapter for now).
- Torrent engine, sources, series tracking, recommendations (later steps in the design).

## Build

```bash
./gradlew :core:test        # pure Kotlin rules, no Android needed
./gradlew :app:assembleDebug
```

CI builds the APK on every push and publishes it as a pre-release from `main`.
The toolchain is pinned to the versions Marketbot builds with (AGP 8.7.3, Kotlin 2.0.21, Media3 1.5.1),
so the first builds test the app rather than a toolchain upgrade.
