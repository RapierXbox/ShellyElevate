# ShellyElevate

> [!WARNING]
> **This is unofficial, third-party software. Use it at your own risk.**
>
> - Installing it can **permanently brick your display**. There is no warranty, not from me and not from Shelly.
> - **Shelly will not repair or replace displays modified this way.** They asked me to say this clearly, because people bricked their displays following this guide and then asked Shelly for a replacement. Please don't do that.
> - If something goes wrong, you're on your own. Open an issue here or try to recover it yourself.
>
> If you're not comfortable with `adb` and with modifying a device you paid real money for, **stop here**.

I bought a Shelly Wall Display to hang a Home Assistant dashboard on the wall. The hardware is great. The stock app wasn't: the WebView kept crashing, the hardware was locked away, and there was no way to build anything on top of it.

So I replaced it. ShellyElevate is an Android app that takes over the display. It gives you a dashboard that stays up, opens up the relays, sensors and buttons, and talks to Home Assistant properly. And if you don't use Home Assistant, everything still works on its own.

https://github.com/user-attachments/assets/adf46edd-9bf1-45da-b553-bf7781d17fbd

<sub>this video is really old but you get the jist<sub/>

## Using Home Assistant? Start with the integration

Don't set things up by hand. Install the **[Shelly Elevate integration](https://github.com/RapierXbox/shellyelevateintegration)** and let it do the work. It installs the app on the display over ADB, pairs it over an encrypted connection and adds the display as a single device with everything on it. No MQTT discovery, no ESPHome, no long-lived tokens.

## What it can do

**On the screen**
- A dashboard that stays up for weeks, not hours. If the page dies or the network drops, it shows an offline page and reloads by itself.
- Show any web page (your Home Assistant dashboard or anything else), or put an Android app on the main screen instead.
- An app switcher: swipe up with two fingers to jump between the dashboard and other apps.
- Automatic brightness from the light sensor with a minimum you choose, plus a night mode that dims even further.
- Screensavers: screen off, a clock, a clock with the date, or an always-on display.
- Wake it with a touch or just by walking up to it (proximity sensor), and keep it awake for a while after.
- Optional power saving while the screen is off, in two levels.
- A lite mode, if you'd rather run Fully Kiosk or the Companion app and only want the hardware parts.

**The hardware**
- Relays, the SW input terminal and the buttons all work. Wall switches and push buttons on the SW input behave like they do on the stock firmware, in button or switch mode, with optional local relay control.
- Buttons and swipes can switch relays directly, so your light still works when your server doesn't.
- Single, double, triple and long presses on every button, and swipe gestures with up to five fingers.
- Temperature and humidity, corrected for the heat the display itself gives off.
- The light and proximity sensors, and the chip temperatures if you want them.
- The dimmer backplate.

**With Home Assistant** (through the integration)
- One device per display: relays as switches or lights, every sensor, the screen as a light, night mode, and events for buttons and swipes.
- A media player with a queue, and announcements that turn the music down while they play.
- A voice assistant. The wake word runs on the display, Home Assistant runs your Assist pipeline, and timers show up on screen.
- A Bluetooth proxy, so every display extends your Bluetooth range.
- Screenshots, pop-up messages on the screen, app updates and every display setting, right from Home Assistant.
- An optional thermostat that switches a relay based on the display's own sensor or any other temperature sensor you have.
- A sidebar panel to install new displays, copy settings from one display to another, keep backups, and put a display back on the stock Shelly app.

**And the rest**
- MQTT for openHAB, ioBroker, Node-RED or anything else that speaks it, with optional Home Assistant discovery.
- A plain HTTP API on port 8080 for your own scripts.
- A JavaScript bridge, so your own dashboard can read the sensors and flip relays with no server in between.
- Updates from inside the app, for the app and for the WebView, with an opt-in pre-release channel. Installed as a system app, it even updates itself without a cable.
- Wi-Fi settings on the display: scan, join networks and set a static IP.
- ADB over Wi-Fi that you can switch on in the settings. Handy once the display is on the wall.

## Getting started

**With Home Assistant (recommended)**

1. Install the [Shelly Elevate integration](https://github.com/RapierXbox/shellyelevateintegration) through HACS and restart Home Assistant.
2. Turn on ADB on the display. The integration's README walks you through it: connect to Wi-Fi, update to the newest Shelly firmware, unlock the Android settings and switch on ADB over Wi-Fi.
3. Open **Shelly Elevate** in the Home Assistant sidebar, go to **Install** and type in the display's IP address. Home Assistant installs the app, pairs it and adds the device.

Already running ShellyElevate? Update the app. Home Assistant finds the display by itself under **Settings → Devices & services**. Add it and type in the code the display shows you.

**Without Home Assistant**

1. [Install ShellyElevate](https://github.com/RapierXbox/ShellyElevate/wiki/Installation) over `adb`.
2. [Set it up](https://github.com/RapierXbox/ShellyElevate/wiki/First-Time-Setup) and point it at your dashboard.
3. Connect it through [MQTT](https://github.com/RapierXbox/ShellyElevate/wiki/MQTT), the [HTTP API](https://github.com/RapierXbox/ShellyElevate/wiki/HTTP-API) or the [JavaScript bridge](https://github.com/RapierXbox/ShellyElevate/wiki/JavaScript-Interface).

Ready-to-install APKs are on the [releases page](https://github.com/RapierXbox/ShellyElevate/releases).

## Using the display

### Opening the settings

The settings open by themselves the first time the app starts. After that there's no visible button, so nobody in the hallway changes your setup by accident. Pick whichever way suits you:

- **The corner knock.** Tap the **bottom right** corner 10 times, then the **bottom left** corner 10 times. Each corner is the bottom 15% of the screen height and width. Don't pause for more than 2 seconds between taps, or the count starts over.
- **The app switcher.** Swipe up with two fingers and tap **Settings**.
- **From your dashboard.** A link to `shellyelevate:settings` opens them, so you can put a settings button on your own dashboard.
- **Over the network.** `curl -X POST http://<display-ip>:8080/device/settings`

### Finding your way around

The settings are split into pages:

| Page | What's in it |
|---|---|
| Screen content | Dashboard URL or app on the main screen, lite mode, app switcher |
| Display | Brightness, automatic brightness and night mode |
| Screensaver and sleep | Screensaver, power saving, waking by touch or proximity |
| Wi-Fi and network | Wi-Fi networks, static IP, HTTP server, ADB over Wi-Fi |
| Controls | Swipe gestures, power button, SW input, buttons and relays |
| Home Assistant | Pairing with the integration, MQTT broker and discovery |
| Sensors | Chip temperatures and the temperature correction |
| Audio and voice | Volume, media and the voice assistant |
| Bluetooth | Bluetooth proxy |
| Updates | App and WebView updates, pre-release channel |

Changes are saved when you leave the settings with the back arrow. The menu in the top right corner has **Android settings**, **Reboot** and **Exit**. **Exit** drops you to Android underneath. A reboot brings ShellyElevate back.

### Everyday use

- **Waking up.** Touch the screen or walk up to it if proximity wake is on. A touch that wakes the screen doesn't also press whatever is under your finger.
- **Back.** The back gesture never leaves the dashboard. Inside an app or the settings it works as usual.
- **Switching apps.** Swipe up with two fingers for the app switcher. Swipe sideways between the dashboard and your open apps, tap one to open it, swipe a card up to close that app. You can change the number of fingers and the direction, or turn it off, under **Screen content**.
- **Buttons and gestures.** Out of the box a swipe toggles the relay, and button presses are only reported to Home Assistant or MQTT. Turn on **Buttons switch relays** under **Controls** if a button should switch its relay directly, so the light still works when your server is down. The SW input terminal is set up on the same page.
- **When the dashboard is down.** You get an offline page that keeps retrying on its own. Your relays and buttons keep working in the meantime.

## What changed with the integration

Some things the app used to do on its own now go through the integration, so I removed them from the app:

| Gone | Use this instead |
|---|---|
| The ESPHome Bluetooth proxy on port 6053 | The integration's Bluetooth proxy. On the display: **Settings → Bluetooth → Bluetooth proxy via Home Assistant integration** |
| The built-in voice assistant that used a long-lived token and a pipeline id | The integration's voice assistant. On the display: **Settings → Audio and voice → Voice assistant via Home Assistant integration**. The wake word still runs on the display |
| `tools/uart-setup.py` | Install over ADB from the integration's **Install** tab, or follow the [manual install](https://github.com/RapierXbox/ShellyElevate/wiki/Installation) |

If you're coming from an older version:

- **You used the ESPHome proxy.** The new Bluetooth proxy is switched on for you and starts as soon as the display is paired with the integration. Delete the old ESPHome device in Home Assistant.
- **You used the built-in voice assistant.** If the display was already paired, voice through the integration is switched on for you. If not, pair it first and then turn it on. Your old token and pipeline id are deleted from the display; you pick the pipeline in Home Assistant now.
- **You also use MQTT discovery.** The display will show up twice. Once the integration is running, turn off **MQTT Home Assistant discovery**. The integration offers to do it for you.
- **You use the HTTP API on port 8080.** It keeps working. Once a display is paired, though, port 8080 hides passwords and won't let anyone change the MQTT broker, the MQTT login or the dashboard URL. Change those on the display or through the integration.
- **You don't use Home Assistant at all.** Nothing changes for you. If you like, turn off **Settings → Home Assistant → Allow pairing with Home Assistant**.

Everything else works like it did: the dashboard, relays, buttons, screensavers, MQTT, the HTTP API, the JavaScript bridge and updates.

## Supported devices

Every Shelly Wall Display works: the original Wall Display, the Wall Display 2, X2, XL, U1, X2i, X1i and D1. The app recognizes the model and sets up the right relays, buttons and sensor corrections on its own. Details are on the [Supported Devices](https://github.com/RapierXbox/ShellyElevate/wiki/Supported-Devices) page.

## Documentation

Everything else lives in the [wiki](https://github.com/RapierXbox/ShellyElevate/wiki):

| | |
|---|---|
| Setup | [Installation](https://github.com/RapierXbox/ShellyElevate/wiki/Installation) · [First-Time Setup](https://github.com/RapierXbox/ShellyElevate/wiki/First-Time-Setup) · [Updating](https://github.com/RapierXbox/ShellyElevate/wiki/Updating) · [Supported Devices](https://github.com/RapierXbox/ShellyElevate/wiki/Supported-Devices) · [Configuration Reference](https://github.com/RapierXbox/ShellyElevate/wiki/Configuration-Reference) |
| Connecting | [Shelly Elevate integration](https://github.com/RapierXbox/shellyelevateintegration) · [MQTT](https://github.com/RapierXbox/ShellyElevate/wiki/MQTT) · [HTTP API](https://github.com/RapierXbox/ShellyElevate/wiki/HTTP-API) · [JavaScript Interface](https://github.com/RapierXbox/ShellyElevate/wiki/JavaScript-Interface) |
| Features | [Screensavers](https://github.com/RapierXbox/ShellyElevate/wiki/Screensavers) · [Kiosk & Lite Mode](https://github.com/RapierXbox/ShellyElevate/wiki/Kiosk-and-Lite-Mode) |
| Reference | [Hardware Reference](https://github.com/RapierXbox/ShellyElevate/wiki/Hardware-Reference) · [Building from Source](https://github.com/RapierXbox/ShellyElevate/wiki/Building-from-Source) · [Troubleshooting](https://github.com/RapierXbox/ShellyElevate/wiki/Troubleshooting) |

## Contributing

Bug reports and pull requests are welcome. If you send a PR, please test it on a real display. The code is full of small workarounds for specific models, and an emulator won't catch those.

Stuck on setup? Have a look at [Troubleshooting](https://github.com/RapierXbox/ShellyElevate/wiki/Troubleshooting) first, most problems are covered there. Everything else goes to the [issue tracker](https://github.com/RapierXbox/ShellyElevate/issues).

## License

See [LICENSE](LICENSE). Provided as is, at your own risk.

## Download stats

[![Total downloads](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/total?label=total%20downloads)](https://github.com/RapierXbox/ShellyElevate/releases) [![Latest release](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/latest/total?label=latest%20release)](https://github.com/RapierXbox/ShellyElevate/releases/latest)

The numbers count APK downloads from the releases page and update by themselves.

<details>
<summary>Downloads per release</summary>

| Release | Date | Downloads |
|---|---|---|
| [v3.26279.2300](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26279.2300) | 2026-10-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26279.2300/total?label=) |
| [v3.26279.2235](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26279.2235) (pre-release) | 2026-10-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26279.2235/total?label=) |
| [v3.26279.2140](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26279.2140) (pre-release) | 2026-10-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26279.2140/total?label=) |
| [v3.26279.2009](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26279.2009) | 2026-10-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26279.2009/total?label=) |
| [v3.26279.1802](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26279.1802) | 2026-10-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26279.1802/total?label=) |
| [v3.26279.1508](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26279.1508) (pre-release) | 2026-10-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26279.1508/total?label=) |
| [v3.26279.1458](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26279.1458) (pre-release) | 2026-10-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26279.1458/total?label=) |
| [v3.26278.2001](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26278.2001) | 2026-10-05 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26278.2001/total?label=) |
| [v3.26277.2350](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26277.2350) | 2026-10-04 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26277.2350/total?label=) |
| [v3.26277.2331](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26277.2331) | 2026-10-04 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26277.2331/total?label=) |
| [v3.26277.2252](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26277.2252) | 2026-10-04 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26277.2252/total?label=) |
| [v3.26277.2210](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26277.2210) | 2026-10-04 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26277.2210/total?label=) |
| [v3.26270.1814](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26270.1814) | 2026-09-27 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26270.1814/total?label=) |
| [v3.26268.1356](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26268.1356) | 2026-09-25 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26268.1356/total?label=) |
| [v3.26230.0834](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26230.0834) (pre-release) | 2026-08-18 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26230.0834/total?label=) |
| [v3.26195.1721](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26195.1721) (pre-release) | 2026-07-14 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26195.1721/total?label=) |
| [v3.26170.1522](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26170.1522) | 2026-06-19 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26170.1522/total?label=) |
| [v3.26167.1905](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26167.1905) | 2026-06-16 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26167.1905/total?label=) |
| [v3.26157.2332](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26157.2332) | 2026-06-06 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26157.2332/total?label=) |
| [v3.26142.1013](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26142.1013) | 2026-05-22 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26142.1013/total?label=) |
| [v3.26139.0727](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26139.0727) | 2026-05-19 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26139.0727/total?label=) |
| [v3.26133.1120](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26133.1120) | 2026-05-13 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26133.1120/total?label=) |
| [v3.26128.1954](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26128.1954) | 2026-05-08 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26128.1954/total?label=) |
| [v3.26122.0921](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26122.0921) | 2026-05-02 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26122.0921/total?label=) |
| [v3.2026111.1918](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.2026111.1918) | 2026-04-21 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.2026111.1918/total?label=) |
| [v3.26109.1251](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26109.1251) | 2026-04-19 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26109.1251/total?label=) |
| [v3.26104.1016](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.26104.1016) | 2026-04-14 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.26104.1016/total?label=) |
| [v3.202604.0632](https://github.com/RapierXbox/ShellyElevate/releases/tag/v3.202604.0632) | 2026-04-13 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v3.202604.0632/total?label=) |
| [v2.4.0](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.4.0) | 2025-08-14 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.4.0/total?label=) |
| [v2.3.0](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.3.0) | 2025-03-18 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.3.0/total?label=) |
| [v2.2.1](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.2.1) | 2025-03-15 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.2.1/total?label=) |
| [v2.2.0](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.2.0) | 2025-03-12 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.2.0/total?label=) |
| [v2.1.3](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.1.3) | 2025-03-09 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.1.3/total?label=) |
| [v2.1.2](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.1.2) | 2025-02-26 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.1.2/total?label=) |
| [v2.1.1](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.1.1) | 2025-02-25 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.1.1/total?label=) |
| [v2.1.0](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.1.0) | 2025-02-24 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.1.0/total?label=) |
| [v2.0.3](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.0.3) | 2025-02-23 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.0.3/total?label=) |
| [v2.0.2](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.0.2) | 2025-02-17 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.0.2/total?label=) |
| [v2.0.1](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.0.1) | 2025-02-17 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.0.1/total?label=) |
| [v2.0.0](https://github.com/RapierXbox/ShellyElevate/releases/tag/v2.0.0) | 2025-02-16 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v2.0.0/total?label=) |
| [v1.0.2](https://github.com/RapierXbox/ShellyElevate/releases/tag/v1.0.2) | 2024-05-31 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v1.0.2/total?label=) |
| [v1.0.1](https://github.com/RapierXbox/ShellyElevate/releases/tag/v1.0.1) | 2024-05-30 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v1.0.1/total?label=) |
| [v1.0.0](https://github.com/RapierXbox/ShellyElevate/releases/tag/v1.0.0) | 2024-05-29 | ![](https://img.shields.io/github/downloads/RapierXbox/ShellyElevate/v1.0.0/total?label=) |

</details>
