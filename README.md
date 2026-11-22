# OuterTune for Windows

A YouTube Music player for Windows, built to look and work like the
[OuterTune Android app](https://github.com/notK50BML/OuterTune). It is an independent port: a
separate project from the original OuterTune, under the same GPL-3.0 licence.

### YouTube Music playback

- Streamed Opus 140kbps streams
- Full access to the YouTube Music song library
- Song artist and album linking to YouTube Music artist pages
- All songs from the YouTube Music library are downloadable for offline playback
- Personalised home feed, similar to the actual YouTube Music app
- Cloud playlists

### Local music playback

- Ability to play most music file formats (MP3, FLAC, OGG and more)
- Smart linking of artists to their YouTube Music pages
- Lyrics from multiple providers (more on that in the Lyrics section)

### Lyrics

- Word-synced lyrics from multiple providers, such as BetterLyrics, YouLy+, LRCLIB word-synced and QQMusic
- Word-synced lyrics interpolation to quickly give decent word-synced lyrics from line synced sources
- Choice of word-synced lyrics rendering effects
- Self-scrolling lyrics
- Lyrics editor
- Offset timer, with steps in 10ms or 50ms
- Reorderable lyrics provider priority

### Equaliser
- 12-band parametric equaliser
- Adjustable frequency, Q factor, filter type and decibel value
- Bass and treble shelving
- Extensive clipping and distortion protection
- Ability to fine-tune audio L/R balance
- Compressor with attack, release, compression ratio, gain and limiter settings
- Easy and intuitive gestures (such as double-tap dials to reset them)
- AutoEQ support
[The README is kinda WIP rn]
- Search and play YouTube Music, with albums, artists, playlists and a signed-in home feed
- Word-by-word lyrics (BetterLyrics, QQ Music, LrcLib, KuGou)
- Local music files: MP3, FLAC and Ogg Vorbis, with their tags and cover art
- Downloads, liked songs, recently played and local playlists; imports a backup from the phone app
- Twelve-band equaliser with AutoEQ, compressor, tempo and pitch
- Dynamic theming from the cover
- Listen together with a phone or another PC on the same network
- Discord rich presence, Windows media controls, sleep timer
- Updates itself from this repo's releases

See [desktop/PARITY.md](desktop/PARITY.md) for a feature-by-feature comparison with the Android app.

## Install

Download `OuterTune-setup-<version>.exe` from [Releases](../../releases) and run it. It can be installed only for the current user, or for all users if the UAC prompt is approved. You can also download `OuterTune-windows.zip`, unzip it anywhere and run `OuterTune.exe` to start the app.

Needs Windows 10 or 11 (64-bit).

## Build

Needs JDK 21.

```
gradlew :desktop:run          # run from source
gradlew :desktop:packageApp   # portable app: desktop/build/dist/OuterTune-windows.zip
gradlew :desktop:installer    # installer; needs Inno Setup 6
```

Signing in is explained in [desktop/SIGN-IN.md](desktop/SIGN-IN.md), and
[desktop/HANDOVER.md](desktop/HANDOVER.md) covers how the app is put together.

## Shared code

`innertube`, `lrclib`, `kugou`, `betterlyrics`, `qqmusic` and `kizzy` are copied from the Android
app's repo, where they are shared with the phone. Fixes to them should be copied across both ways.

Listen together uses the same wire format as the phone app (`Protocol.kt` in each repo). Add new
frame types rather than changing existing ones, so phones and PCs on different versions still
work together.

## Licence

GPL-3.0, see [LICENSE](LICENSE).
