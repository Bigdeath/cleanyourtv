# CleanYourTV – Extended Fork

This fork extends the original CleanYourTV launcher with additional
features for Google TV devices.

## Features

### Dynamic URL wallpapers

- Load wallpapers directly from HTTP/HTTPS image endpoints
- Compatible with Immich Kiosk and similar image services
- Configurable refresh intervals from 10 seconds to 30 minutes
- Each image remains fully visible for the selected interval
- Configurable crossfade from 500 ms to 10 seconds
- Background image loading without blocking the launcher
- No permanent local image collection required

### Remote button customization

- Configure YouTube, Star and Netflix buttons through launcher settings
- Launch installed Android TV applications
- Uses a local ADB-based input watcher
- Does not require Accessibility key filtering
- Preserves continuous volume adjustment

Note: The Netflix button may still trigger its original system action.

### Google TV Streamer support

- Automatic button watcher startup after reboot
- Persistent local ADB supervisor
- Cast recovery workaround for devices affected by disabling setupwraith
- No external server required during normal operation

## Requirements

The remote button and Cast workarounds require additional setup:

- Android TV / Google TV device
- ADB over TCP enabled on the device
- Initial authorization of the launcher's ADB key
- Installation of the included shell scripts

These features are primarily tested on the Google TV Streamer
running Android 14.

## Security

ADB over TCP can expose privileged debugging functionality to
other devices on the network.

Only enable it on trusted networks and restrict network access
where possible.

The launcher generates its own ADB authentication key.
Private keys must never be committed to Git.

## Limitations

- Some manufacturer-specific remote buttons cannot be fully overridden.
- The Netflix button may launch Netflix alongside the configured application.
- Cast recovery behavior depends on firmware and Google TV components.
- The ADB-based workarounds are not guaranteed to work on every device.

## Credits

Based on the original CleanYourTV project by Gathin23.

Original project:
https://github.com/Gathin23/cleanyourtv

Licensed under the original MIT license.
