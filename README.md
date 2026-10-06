# ShellyElevate

> [!WARNING]
> **This is unofficial, third-party firmware. Use it at your own risk.**
>
> - Flashing it can **permanently brick your device**. There is no warranty from me or from Shelly.
> - **Shelly will not service or replace devices modified this way.** They asked me to state this clearly, because many people followed this guide, bricked their devices, and then asked Shelly for a replacement. Please don't.
> - If something goes wrong, you're on your own: open an issue here or try to recover it yourself.
>
> If you're not comfortable with `adb` and with rooting a device you paid real money for, **stop here**.

ShellyElevate replaces the stock software on a Shelly Wall Display. The stock app works, but the WebView crashes, hardware access is locked down, and you can't really build anything on top of it. This project gives you a stable Home Assistant kiosk, exposes the relays, sensors and buttons over MQTT and HTTP, and lets your dashboard talk to the hardware directly from JavaScript.

https://github.com/user-attachments/assets/adf46edd-9bf1-45da-b553-bf7781d17fbd

## What you get

- A WebView wrapper that doesn't fall over after a few hours
- The [Shelly Elevate Home Assistant integration](https://github.com/RapierXbox/shellyelevateintegration): every display is one HA device with its relays, sensors, screen, media player, voice assistant and Bluetooth proxy, paired over an encrypted local API (TLS on port 8443)
- MQTT for systems other than Home Assistant (temperature, humidity, light, proximity, relays, inputs, buttons, swipe events, screen brightness and night mode control), with optional Home Assistant auto-discovery
- A REST API on port 8080 for everything the device can do
- A JavaScript bridge so your dashboard can read sensors and flip relays without going through HA
- External wall switches and push-buttons on the SW terminal work like stock: Button/Switch input modes with local relay control
- Auto-brightness from the light sensor, screensavers, wake-on-proximity
- In-app updates for both the app and the WebView, with optional self-install as a system app so updates don't need a cable
- ADB over Wi-Fi you can toggle from settings, handy once the display is on the wall with no USB reachable
- Optional extras: voice assistant with an on-device wake word, Bluetooth proxy, dimmer support over UART (voice and Bluetooth go through the Home Assistant integration)
- A "lite" mode if you'd rather use Fully Kiosk or a Companion app but still want the hardware exposed

> [!IMPORTANT]
> **Using Home Assistant? Set the display up with the [Shelly Elevate integration](https://github.com/RapierXbox/shellyelevateintegration).**
> It installs the app over ADB, pairs the display over an encrypted connection and gives you one device with everything on it. MQTT discovery, the ESPHome proxy and long-lived tokens are no longer needed for Home Assistant.

## What changed and what no longer works

These features were removed because the Home Assistant integration replaces them:

| Removed | Use instead |
|---|---|
| ESPHome Bluetooth proxy on port 6053 (adding the display in HA's ESPHome integration) | The integration's Bluetooth proxy. Turn on **Settings → Bluetooth → Bluetooth proxy via Home Assistant integration** on the display |
| The app's own voice assistant with a Home Assistant long-lived token and pipeline id | The integration's Assist satellite. Turn on **Settings → Audio and voice → Voice assistant via Home Assistant integration**. The wake word still runs on the display |
| `tools/uart-setup.py` | Install over ADB from the integration's **Install** tab (or the [manual ADB install](https://github.com/RapierXbox/ShellyElevate/wiki/Installation)) |

What this means after updating:

- If you used the ESPHome proxy, the integration's Bluetooth proxy is switched on for you; it starts once the display is **paired with the integration**. Remove the old ESPHome device from Home Assistant.
- If you used the token voice assistant, voice through the integration is switched on for you only if the display was already paired. Otherwise pair it and turn on **Voice assistant via Home Assistant integration**.
- Not using Home Assistant at all? You can turn the integration API off under **Settings → Home Assistant → Allow pairing with Home Assistant**.
- The long-lived token and pipeline id you entered are deleted from the display. The integration lets you pick the Assist pipeline in Home Assistant.
- If you also keep MQTT on with Home Assistant discovery, the display shows up twice. Turn off **MQTT Home Assistant discovery** once the integration is set up (the integration offers a repair for this).
- After pairing, the plain HTTP API on port 8080 no longer shows passwords and refuses changes to the MQTT broker, MQTT login and dashboard URL. Change those on the display or through the integration.

Still working as before: the dashboard and kiosk, relays, inputs and buttons, screensavers, MQTT for non-HA systems, the HTTP API on port 8080, the JavaScript bridge and in-app updates. The display keeps working without Home Assistant.

## Supported devices

Every Shelly Wall Display model is supported, with the right temperature offsets and relay/button counts picked automatically. See [Supported Devices](https://github.com/RapierXbox/ShellyElevate/wiki/Supported-Devices) for the full list.

## Get started

**With Home Assistant (recommended):**

1. Install the [Shelly Elevate integration](https://github.com/RapierXbox/shellyelevateintegration) through HACS and restart Home Assistant.
2. On the display, enable ADB as described in the integration's README (Wi-Fi, newest Shelly firmware, unlock the Android settings, turn on ADB over Wi-Fi).
3. Open **Shelly Elevate** in the Home Assistant sidebar, go to **Install** and enter the display's IP. Home Assistant installs the app, pairs it and adds the device.

Already running ShellyElevate? Update the app, then add the display under **Settings → Devices & services** in Home Assistant (it is found automatically) and enter the code the display shows.

**Without Home Assistant:**

1. [Install ShellyElevate](https://github.com/RapierXbox/ShellyElevate/wiki/Installation): enable developer mode, sideload the APK over `adb`
2. [First-time setup](https://github.com/RapierXbox/ShellyElevate/wiki/First-Time-Setup): point it at your dashboard URL
3. Use the [HTTP API](https://github.com/RapierXbox/ShellyElevate/wiki/HTTP-API), [MQTT](https://github.com/RapierXbox/ShellyElevate/wiki/MQTT) or the [JavaScript bridge](https://github.com/RapierXbox/ShellyElevate/wiki/JavaScript-Interface)

Prebuilt APKs are on the [Releases page](https://github.com/RapierXbox/ShellyElevate/releases).

## Documentation

Everything lives in the [Wiki](https://github.com/RapierXbox/ShellyElevate/wiki):

| | |
|---|---|
| Setup | [Installation](https://github.com/RapierXbox/ShellyElevate/wiki/Installation) · [First-Time Setup](https://github.com/RapierXbox/ShellyElevate/wiki/First-Time-Setup) · [Updating](https://github.com/RapierXbox/ShellyElevate/wiki/Updating) · [Supported Devices](https://github.com/RapierXbox/ShellyElevate/wiki/Supported-Devices) · [Configuration Reference](https://github.com/RapierXbox/ShellyElevate/wiki/Configuration-Reference) |
| Integration | [Home Assistant](https://github.com/RapierXbox/ShellyElevate/wiki/Home-Assistant-Integration) · [MQTT](https://github.com/RapierXbox/ShellyElevate/wiki/MQTT) · [HTTP API](https://github.com/RapierXbox/ShellyElevate/wiki/HTTP-API) · [JavaScript Interface](https://github.com/RapierXbox/ShellyElevate/wiki/JavaScript-Interface) |
| Features | [Screensavers](https://github.com/RapierXbox/ShellyElevate/wiki/Screensavers) · [Voice Assistant](https://github.com/RapierXbox/ShellyElevate/wiki/Voice-Assistant) · [Bluetooth Proxy](https://github.com/RapierXbox/ShellyElevate/wiki/Bluetooth-Proxy) · [Kiosk & Lite Mode](https://github.com/RapierXbox/ShellyElevate/wiki/Kiosk-and-Lite-Mode) |
| Reference | [Hardware Reference](https://github.com/RapierXbox/ShellyElevate/wiki/Hardware-Reference) · [Building from Source](https://github.com/RapierXbox/ShellyElevate/wiki/Building-from-Source) · [Troubleshooting](https://github.com/RapierXbox/ShellyElevate/wiki/Troubleshooting) |

## Contributing

Bug reports and PRs are welcome. If you're sending a PR, test on actual hardware. The codebase is full of small workarounds for specific Shelly models and emulators won't catch them.

For issues, the [issue tracker](https://github.com/RapierXbox/ShellyElevate/issues) is the right place. If you're stuck on setup, check [Troubleshooting](https://github.com/RapierXbox/ShellyElevate/wiki/Troubleshooting) first. Most setup problems are covered there.

## License

See [LICENSE](LICENSE). Provided "as is", at your own risk.
