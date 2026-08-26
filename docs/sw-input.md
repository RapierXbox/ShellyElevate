# SW input (external switch / push-button)

The mounting base has an SW screw terminal for an external wall switch or
push-button. Stock firmware couples it to the relay with an "Input Type:
Button/Switch" setting; ShellyElevate does the same, locally on the device —
no MQTT broker or Home Assistant round-trip involved, and the input keeps
working while the screensaver or the settings screen is open.

## How the hardware reports the input

The device does not deliver the SW contact as a held key. Each contact
*transition* arrives as a short (~5 ms) down+up key pulse, and the keycode
encodes the direction: keycode 141 (`KEY_F11`) = the line went active,
keycode 142 (`KEY_F12`) = the line went inactive. Verified on a Wall Display
X1i; every supported model has exactly one SW terminal. ShellyElevate decodes
those pulses into a contact level and applies the configured mode to it.

## Modes

Set per input under Settings → "External switch input (SW)", or via the
`swInputMode0` key in the settings API.

| Value | Mode | Relay | Reported |
|---|---|---|---|
| 0 | Detached | never touched | PRESS/RELEASE per edge, JS event per press |
| 1 | Button (default) | toggles on every press | PRESS/RELEASE + press gestures |
| 2 | Switch, edge | toggles on every flip | PRESS/RELEASE per edge |
| 3 | Switch, follow | tracks the contact position | PRESS/RELEASE per edge |

- **Button** is for momentary push-buttons (tasters): the relay toggles the
  moment the button is pressed. Gestures are published like the capacitive
  buttons: `short`/`long`/`double`/`triple` on `shellyelevatev2/<id>/button/100`.
- **Switch (edge)** is for toggle switches when the relay is also switched from
  the dashboard or HA: every flip changes the relay state, no flip is wasted.
- **Switch (follow)** forces the relay to the contact position on every change
  (stock wording: "the position of the switch corresponds to the output").
  Until the first flip after boot the relay and the switch can disagree — the
  contact level is only known once it changes.
- **Detached** is the behavior of previous ShellyElevate versions: events are
  reported, the relay is not touched.

## Which mode for which wiring

- Push-buttons wired in parallel to SW (each press pulls the line active,
  release lets it go): **Button**. Press toggles, hold duration drives the
  long-press gesture.
- Push-buttons reusing an existing multi-way circuit (Wechsel-/Kreuzschaltung),
  where each press flips the line once and the direction alternates press to
  press: **Switch — every flip toggles relay**. Every press toggles, regardless
  of direction.
- Maintained flip switches: **Switch — every flip toggles** (never a wasted
  flip when the relay is also controlled from the display or HA) or
  **Switch — relay follows position** for strict position coupling.

## Settings keys

| Key | Type | Default | Meaning |
|---|---|---|---|
| `swInputMode0` | int | `1` | see the mode table above |
| `swInputRelayMap0` | int | `0` | relay index driven by the input, `-1` = none |
| `swInputInvert0` | bool | `false` | invert the contact level (NC wiring) |

All current models have exactly one SW input (index 0). Changes apply
immediately, no restart needed.

```bash
# read all settings
curl http://<ip>:8080/settings
# switch input 0 to switch-edge mode driving relay 0
# (the json content type is required or the server rejects the body)
curl -X POST -H "Content-Type: application/json" \
  http://<ip>:8080/settings -d '{"swInputMode0":2,"swInputRelayMap0":0}'
# current input state; state is null until the first edge after app start
curl "http://<ip>:8080/device/input?num=0"
```

## MQTT

- `shellyelevatev2/<id>/switch_state` gets `PRESS` on contact close and
  `RELEASE` on contact open in every mode. Previous versions only ever sent
  `PRESS`, which left the Home Assistant binary_sensor stuck on.
- In button mode, press gestures go to `shellyelevatev2/<id>/button/100` and a
  matching HA event entity is announced via discovery.
- On every (re)connect the current level is re-published so HA stays in sync.

## JavaScript bridge

`onButtonPressed(100 + input)` fires once per press in detached and button
mode (on the press edge now, previously on release), and on every edge in the
two switch modes.

## Relay control and priv-app installs

On newer models (XL/U1/X2i/X1i) the relay is driven through an init script
(`ctl.start` on `cloud.shelly.<codename>.relayN`), which only works when the
app runs as a system/priv-app. A plain `adb install` lacks that privilege —
logcat then shows a harmless `Unable to set property "ctl.start" ... error
code: 0x20` warning and the app falls back to a direct sysfs write, which
drives the relay fine. Installing as priv-app (offered on first run, or
`tools/install-privapp.sh`) removes the warning.

## Upgrading note

Previously the SW input never controlled the relay. The default is now
**Button** driving relay 0, matching stock firmware: an external taster
toggles the light out of the box, even without MQTT or Home Assistant. If you
used the input purely as a sensor for automations, set `swInputMode0` to `0`
(Detached) to keep the old behavior.
