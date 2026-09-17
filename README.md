# SparkTube

A lightweight YouTube client for Android built with **Kotlin** and
**[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)**.

SparkTube is fully anonymous: no Google account, no API keys, no tracking.
On first launch it asks for your country/region and then suggests videos for
that region. It intentionally **never shows live streams** — not in the home
feed, not in search results, not in the player.

## Features

- First-launch country/region selection (searchable list, changeable later)
- Floating bottom navigation bar: Home / Music / Library / Menu
- Home tab shows trending videos for your selected region
- Music tab shows trending music plus genre chips (Pop, Hip-Hop, Bollywood,
  Bangla, Rock, EDM, Lofi and more)
- Video search (live results are always filtered out)
- Built-in player based on Media3 / ExoPlayer
- Local watch history and favorites (stays on your device)
- Dark Material 3 design

## Tech stack

- Kotlin, coroutines
- NewPipeExtractor v0.26.5 (JitPack)
- OkHttp (NewPipeExtractor downloader)
- Media3 ExoPlayer + PlayerView
- Coil for image loading
- ViewBinding, Material 3

## Building

CI builds a debug APK on every push — grab it from the
[Actions](https://github.com/devfahim00/SparkTube/actions) artifacts.

To build locally:

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio (JDK 17 required).

## Disclaimer

This project is for educational purposes only. It is not affiliated with
YouTube or Google. Please respect the YouTube Terms of Service when using it.

## Credits

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) and the
  NewPipe community for the extraction library.
- Inspired by the NewPipe project.
