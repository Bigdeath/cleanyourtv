# CleanYourTV extended fork – first public build

Based on upstream CleanYourTV, including changes through upstream fork baseline `30626a8`.

### Added in this fork
- Dynamic HTTP/HTTPS wallpaper endpoints, including Immich Kiosk-style images.
- Configurable wallpaper refresh timing and crossfade transitions.
- Remapping for YouTube, Star, and Netflix buttons on supported Google TV remotes.
- Local ADB supervisor for button watcher startup after reboot.
- Optional Cast recovery workaround for a specific setupwraith-related issue.

### Tested / limitations
- Compiled with the Android Gradle release build.
- Button mapping and Cast recovery worked on the maintainer's Google TV Streamer,
  Android 14, with prior working launcher version.
- **The integrated post-merge build has not yet been device-tested.**
- ADB TCP and installation of the two optional shell scripts are required for
  watcher features; the APK alone does not activate them.
- The Netflix key can still trigger its stock action.

See [installation instructions](https://github.com/Bigdeath/cleanyourtv/blob/main/docs/FORK_INSTALLATION.md)
and [feature notes](https://github.com/Bigdeath/cleanyourtv/blob/main/docs/FORK_FEATURES.md).
