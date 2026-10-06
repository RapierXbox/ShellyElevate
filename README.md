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
