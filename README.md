# ShellyElevate

> **Warning**
> This is third-party firmware. It can brick your device if something goes wrong. There is no warranty. If you're not comfortable with `adb` and the idea of rooting a device that costs real money, stop here.

ShellyElevate replaces the stock software on a Shelly Wall Display. The stock app works, but the WebView crashes, hardware access is locked down, and you can't really build anything on top of it. This project gives you a stable Home Assistant kiosk, exposes the relays, sensors and buttons over MQTT and HTTP, and lets your dashboard talk to the hardware directly from JavaScript.

https://github.com/user-attachments/assets/adf46edd-9bf1-45da-b553-bf7781d17fbd

## What you get

- A WebView wrapper that doesn't fall over after a few hours
- MQTT with full Home Assistant auto-discovery (temperature, humidity, light, proximity, relays, buttons, swipe events)
- A REST API on port 8080 for everything the device can do
- A JavaScript bridge so your dashboard can read sensors and flip relays without going through HA
- Auto-brightness from the light sensor, screensavers, wake-on-proximity
- Optional extras: Home Assistant voice pipeline (with wake word), Bluetooth proxy for HA, dimmer support over UART
- A "lite" mode if you'd rather use Fully Kiosk or a Companion app but still want the hardware exposed

## Supported devices

Every Shelly Wall Display model is supported, with the right temperature offsets and relay/button counts picked automatically. See [Supported Devices](https://github.com/RapierXbox/ShellyElevate/wiki/Supported-Devices) for the full list.

## Get started

1. [Install ShellyElevate](https://github.com/RapierXbox/ShellyElevate/wiki/Installation): enable developer mode, sideload the APK over `adb`
2. [First-time setup](https://github.com/RapierXbox/ShellyElevate/wiki/First-Time-Setup): point it at your Home Assistant URL
3. Pick what you want to use:
   - [Home Assistant integration](https://github.com/RapierXbox/ShellyElevate/wiki/Home-Assistant-Integration) (the common path)
   - [HTTP API](https://github.com/RapierXbox/ShellyElevate/wiki/HTTP-API) or [MQTT](https://github.com/RapierXbox/ShellyElevate/wiki/MQTT) (if you're not using HA, or just want to script things)
   - [JavaScript bridge](https://github.com/RapierXbox/ShellyElevate/wiki/JavaScript-Interface) (if you're building your own dashboard)

Prebuilt APKs are on the [Releases page](https://github.com/RapierXbox/ShellyElevate/releases).

## Documentation

Everything lives in the [Wiki](https://github.com/RapierXbox/ShellyElevate/wiki):

| | |
|---|---|
| Setup | [Installation](https://github.com/RapierXbox/ShellyElevate/wiki/Installation) · [First-Time Setup](https://github.com/RapierXbox/ShellyElevate/wiki/First-Time-Setup) · [Supported Devices](https://github.com/RapierXbox/ShellyElevate/wiki/Supported-Devices) · [Configuration Reference](https://github.com/RapierXbox/ShellyElevate/wiki/Configuration-Reference) |
| Integration | [Home Assistant](https://github.com/RapierXbox/ShellyElevate/wiki/Home-Assistant-Integration) · [MQTT](https://github.com/RapierXbox/ShellyElevate/wiki/MQTT) · [HTTP API](https://github.com/RapierXbox/ShellyElevate/wiki/HTTP-API) · [JavaScript Interface](https://github.com/RapierXbox/ShellyElevate/wiki/JavaScript-Interface) |
| Features | [Screensavers](https://github.com/RapierXbox/ShellyElevate/wiki/Screensavers) · [Voice Assistant](https://github.com/RapierXbox/ShellyElevate/wiki/Voice-Assistant) · [Bluetooth Proxy](https://github.com/RapierXbox/ShellyElevate/wiki/Bluetooth-Proxy) · [Kiosk & Lite Mode](https://github.com/RapierXbox/ShellyElevate/wiki/Kiosk-and-Lite-Mode) |
| Reference | [Hardware Reference](https://github.com/RapierXbox/ShellyElevate/wiki/Hardware-Reference) · [Building from Source](https://github.com/RapierXbox/ShellyElevate/wiki/Building-from-Source) · [Troubleshooting](https://github.com/RapierXbox/ShellyElevate/wiki/Troubleshooting) |

## Contributing

Bug reports and PRs are welcome. If you're sending a PR, test on actual hardware. The codebase is full of small workarounds for specific Shelly models and emulators won't catch them.

For issues, the [issue tracker](https://github.com/RapierXbox/ShellyElevate/issues) is the right place. If you're stuck on setup, check [Troubleshooting](https://github.com/RapierXbox/ShellyElevate/wiki/Troubleshooting) first. Most setup problems are covered there.

## License

See [LICENSE](LICENSE). Provided "as is", at your own risk.
