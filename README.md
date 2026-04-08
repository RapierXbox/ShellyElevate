# ShellyElevate

> [!CAUTION]
> All content in this repository is provided "as is" and may render your device unusable. Always exercise caution when working with your device. No warranty or guarantee is provided.

**ShellyElevate** transforms your Shelly Wall Display into a powerful, full-featured kiosk for Home Assistant or any web dashboard. Unlike the buggy built-in WebView, ShellyElevate provides a stable, fast WebView wrapper with complete hardware integration - giving you access to temperature/humidity sensors, relays, proximity sensor, hardware buttons, and more.

https://github.com/user-attachments/assets/adf46edd-9bf1-45da-b553-bf7781d17fbd

## Why ShellyElevate?

The Shelly Wall Display has excellent hardware but the stock software is limited. ShellyElevate unlocks the full potential by providing:

- **Stable WebView** - No more crashes every few hours
- **Hardware Access** - Full control of sensors, relays, buttons via MQTT, HTTP, and JavaScript
- **Home Assistant Integration** - Auto-discovery makes setup seamless
- **Flexible Dashboard** - Load any URL, not just Home Assistant
- **Kiosk Mode** - Full-screen, auto-start, watchdog service keeps it running
- **Smart Features** - Auto brightness, screensavers, swipe gestures, media playback

## Features

| Category | Features |
|----------|----------|
| **Display** | Full-screen kiosk mode • Auto-start on boot • Load any URL or Home Assistant • SSL error handling |
| **Hardware** | Temperature/humidity sensors • Light sensor • Proximity sensor • Relay control • Hardware button mapping |
| **Integration** | MQTT with HA auto-discovery • HTTP REST API • JavaScript bridge to hardware • Sensor state publishing |
| **Screen** | Automatic brightness (lux-based) • Manual brightness control • Multiple screensavers • Wake on proximity/touch |
| **Control** | Swipe gestures • Hardware button events • Remote wake/sleep • Settings via API |
| **Advanced** | Media playback • Crash recovery • Hidden settings UI • Lite mode • Debug logging |

## Supported Devices

ShellyElevate supports all Shelly Wall Display models with automatic hardware detection:

| Device | Model Code | Proximity | Buttons | Notes |
|--------|-----------|-----------|---------|-------|
| **Shelly Wall Display** | SAWD-0A1XX10EU1 | ❌ | 0 | Original model |
| **Shelly Wall Display 2** | SAWD-1A1XX10EU1 | ✅ | 0 | Enhanced version |
| **Shelly Wall Display X2** | SAWD-2A1XX10EU1 | ✅ | 0 | V1 generation |
| **Shelly Wall Display XL** | SAWD-3A1XE10EU2 | ✅ | 4 | Large display, V2 |
| **Shelly Wall Display U1** | SAWD-4A1XE10US0 | ✅ | 0 | US model *(coming soon)* |
| **Shelly Wall Display X2i** | SAWD-5A1XX10EU0 | ✅ | 0 | V2 generation |
| **Shelly Wall Display XLi** | SAWD-6A1XX10EU0 | ✅ | 4 | Large display *(coming soon)* |

Each device has calibrated temperature/humidity offsets and automatic relay/button configuration based on the detected model.

---

## Installation

### Prerequisites
- Shelly Wall Display (any model)
- USB cable (USB-A to USB-C)
- ADB tools installed on your computer
- Root access on the device (or developer mode enabled)

### Step 1: Enable Developer Mode

The Shelly Wall Display has a hidden developer mode. Enable it by navigating to **Settings → About Device** and tapping the version codes in this sequence:

```
F → H → F → F → H → F → H → H
```

Where **F** = Firmware version and **H** = Hardware revision. Developer mode is now active.

### Step 2: Connect via ADB

Connect your Shelly Wall Display to your computer via USB and verify the connection:

```bash
adb devices
```

You should see your device listed. If not, ensure USB debugging is enabled.

### Step 3: Install ShellyElevate

Compile the APK by yourself (a dev container image is offered, so it's easy to do in VS Code) or download the latest APK from [Releases](https://github.com/RapierXbox/ShellyElevate/releases) and install it:

```bash
adb install shellyelevatev2.apk
```

### Build signed APKs automatically in GitHub Releases

This repository includes a GitHub Actions workflow that builds a **signed release APK** (using the app's configured release signing, currently debug key based) and uploads it to the **Releases** section.

To publish a new release APK:

```bash
git tag v3.0.0
git push origin v3.0.0
```

The workflow (`.github/workflows/release-apk.yml`) will build `app/build/outputs/apk/release/*.apk` and attach it to that GitHub release.

### Step 4: Grant Permissions

ShellyElevate requires special permissions to control screen brightness and prevent battery optimization from killing the service:

```bash
# Grant write settings permission (for brightness control)
adb shell appops set me.rapierxbox.shellyelevatev2 WRITE_SETTINGS allow

# Whitelist from battery optimization (optional but recommended)
adb shell dumpsys deviceidle whitelist +me.rapierxbox.shellyelevatev2
```

### Step 5: Launch the App

Launch ShellyElevate for the first time. On first launch, settings will open automatically:

```bash
adb shell am start -n me.rapierxbox.shellyelevatev2/.MainActivity
```

### Step 6: Configure Settings

Configure your dashboard URL and other settings. To access settings again later, tap 10 times on the left edge, then 10 times on the right edge of the screen.

Alternatively, execute this adb command:

```bash
adb shell am start -n me.rapierxbox.shellyelevatev2/.SettingsActivity
```

You can also configure it via the HTTP API (see [HTTP API](#http-api) section below).

### Step 7: Optional - Install a Launcher

To prevent the default Shelly app from starting, install a lightweight launcher:

```bash
# Install Ultra Small Launcher
adb install ultra-small-launcher.apk

# Set as default launcher when prompted
```

Download Ultra Small Launcher: [ultra-small-launcher.apk](https://blakadder.com/assets/files/ultra-small-launcher.apk)

### Step 8: Reboot

```bash
adb reboot
```

After reboot, ShellyElevate will start automatically and load your configured URL.

---

## Configuration

### Initial Setup

1. **Home Assistant URL**: Set your Home Assistant instance (or web view) URL (e.g., `http://192.168.1.100:8123`)
2. **MQTT Settings**: Configure your MQTT broker for Home Assistant integration
3. **Screen Settings**: Choose automatic or manual brightness
4. **Screensaver**: Set idle timeout and screensaver type

### Settings Access

- **Gesture**: Tap left edge 10 times, then right edge 10 times
- **HTTP API**: Send settings via `POST /settings` (see below)
- **Settings File**: Located in app's SharedPreferences

---

## Hardware Access

### Sensors

All sensors publish automatically when MQTT is enabled:

| Sensor | Unit | Update Rate | Availability |
|--------|------|-------------|--------------|
| **Temperature** | °C | 30 seconds | All models with root access |
| **Humidity** | % | 30 seconds | All models with root access |
| **Light (Lux)** | lux | On change | All models |
| **Proximity** | cm | On change | Models with proximity sensor |
| **Screen Brightness** | 0-255 | On change | All models |

### Controllable Hardware

| Hardware | Control Methods | Notes |
|----------|----------------|-------|
| **Relays** | MQTT, HTTP, JavaScript | 0-2 relays depending on model |
| **Screen Brightness** | MQTT, HTTP, JavaScript | Manual or auto-brightness mode |
| **Screen Power** | MQTT, HTTP, JavaScript | Wake/sleep display |
| **Media Playback** | HTTP, JavaScript | Play audio files (when enabled) |

---

## HTTP API

ShellyElevate includes a built-in HTTP server on **port 8080**. Enable it in settings (enabled by default).

All endpoints return JSON with `success` boolean and relevant data fields.

### Device Information

#### `GET /`
Get device info, version, and capabilities.

**Example Request:**
```bash
curl http://192.168.1.100:8080/
```

**Example Response:**
```json
{
  "name": "me.rapierxbox.shellyelevatev2",
  "version": "3.0.0",
  "modelName": "SHELLY_WALL_DISPLAY_XL",
  "proximity": "true",
  "numOfButtons": 4,
  "numOfInputs": 1
}
```

---

### Settings Management

#### `GET /settings`
Get all current settings. This could be used to backup the configuration.

**Example Request:**
```bash
curl http://192.168.1.100:8080/settings
```

**Example Response:**
```json
{
  "success": true,
  "settings": {
    "mqttEnabled": true,
    "mqttBroker": "tcp://192.168.1.50",
    "mqttPort": 1883,
    "mqttUsername": "homeassistant",
    "webviewUrl": "http://192.168.1.50:8123",
    "automaticBrightness": true,
    "screenSaver": true,
    "screenSaverDelay": 45
  }
}
```

#### `POST /settings`
Update settings. Only include fields you want to change. Include everything as a way to restore a backup.

**Example Request:**
```bash
curl -X POST http://192.168.1.100:8080/settings \
  -H "Content-Type: application/json" \
  -d '{
    "mqttEnabled": true,
    "mqttBroker": "tcp://192.168.1.50",
    "mqttPort": 1883,
    "mqttUsername": "homeassistant",
    "mqttPassword": "yourpassword"
  }'
```

**Example Response:**
```json
{
  "success": true,
  "settings": { ... }
}
```

---

### Device Control

#### `GET /device/relay?num=0`
Get relay state.

**Example:**
```bash
curl http://192.168.1.100:8080/device/relay?num=0
```

**Response:**
```json
{
  "success": true,
  "state": true
}
```

#### `POST /device/relay`
Set relay state.

**Example:**
```bash
curl -X POST http://192.168.1.100:8080/device/relay \
  -H "Content-Type: application/json" \
  -d '{"num": 0, "state": true}'
```

#### `GET /device/getTemperature`
Get current temperature.

**Response:**
```json
{
  "success": true,
  "temperature": 23.5
}
```

#### `GET /device/getHumidity`
Get current humidity.

**Response:**
```json
{
  "success": true,
  "humidity": 45.2
}
```

#### `GET /device/getLux`
Get current light level.

**Response:**
```json
{
  "success": true,
  "lux": 120.5
}
```

#### `GET /device/getProximity`
Get proximity distance (if sensor available).

**Response:**
```json
{
  "success": true,
  "distance": 5.0
}
```

#### `POST /device/wake`
Wake the screen (stop screensaver).

**Example:**
```bash
curl -X POST http://192.168.1.100:8080/device/wake
```

#### `POST /device/sleep`
Sleep the screen (start screensaver/dim).

**Example:**
```bash
curl -X POST http://192.168.1.100:8080/device/sleep
```

#### `POST /device/reboot`
Reboot the device (20 second cooldown after boot).

**Example:**
```bash
curl -X POST http://192.168.1.100:8080/device/reboot
```

#### `GET /device/free`
Get memory usage info.

**Response:**
```json
{
  "success": true,
  "Mem total memory": "959MiB",
  "Mem free memory": "41MiB",
  "Swap total memory": "512MiB",
  "Swap free memory": "480MiB"
}
```

---

### WebView Control

#### `GET /webview/refresh`
Refresh/reload the WebView.

**Example:**
```bash
curl http://192.168.1.100:8080/webview/refresh
```

#### `POST /webview/inject`
Inject JavaScript into the WebView.

**Example:**
```bash
curl -X POST http://192.168.1.100:8080/webview/inject \
  -H "Content-Type: application/json" \
  -d '{"javascript": "console.log(\"Hello from API\");"}'
```

---

### Media Control

Media playback must be enabled in settings (`mediaEnabled: true`).

#### `POST /media/play`
Play audio from a URL.

**Example:**
```bash
curl -X POST http://192.168.1.100:8080/media/play \
  -H "Content-Type: application/json" \
  -d '{
    "url": "http://example.com/sound.mp3",
    "music": true,
    "volume": 0.5
  }'
```

- `music`: `true` for music (pausable), `false` for sound effects
- `volume`: 0.0 to 1.0

#### `POST /media/pause`
Pause music playback.

#### `POST /media/resume`
Resume paused music.

#### `POST /media/stop`
Stop all audio playback.

#### `GET /media/volume`
Get current volume (0.0 to 1.0).

#### `POST /media/volume`
Set volume.

**Example:**
```bash
curl -X POST http://192.168.1.100:8080/media/volume \
  -H "Content-Type: application/json" \
  -d '{"volume": 0.8}'
```

---

## MQTT Integration

ShellyElevate includes native MQTT support with Home Assistant auto-discovery. Configure your MQTT broker in settings or via HTTP API.

### Topic Structure

All topics follow the pattern: `shellyelevatev2/<device_id>/<topic>`

The `<device_id>` is auto-generated (e.g., `shellyelevate-a3f2`) or can be customized in settings (`mqttDeviceId`).

---

### State Topics (Published by Device)

These topics publish sensor data and device state automatically:

| Topic | Payload | Update Rate | Description |
|-------|---------|-------------|-------------|
| `shellyelevatev2/<id>/temp` | `23.5` | 30 seconds | Temperature in °C |
| `shellyelevatev2/<id>/hum` | `45.2` | 30 seconds | Humidity in % |
| `shellyelevatev2/<id>/lux` | `120.5` | On change | Light level in lux |
| `shellyelevatev2/<id>/proximity` | `5.0` | On change | Proximity distance in cm |
| `shellyelevatev2/<id>/bri` | `200` | On change | Screen brightness (0-255) |
| `shellyelevatev2/<id>/sleeping` | `ON` / `OFF` | On change | Screen dimmed/sleeping state |
| `shellyelevatev2/<id>/relay_state` | `ON` / `OFF` | On change | Relay state |
| `shellyelevatev2/<id>/switch_state` | `[true, false]` | On change | Array of all relay states |

---

### Event Topics (Published by Device)

| Topic | Payload Example | Description |
|-------|----------------|-------------|
| `shellyelevatev2/<id>/button` | `{"id": 1, "type": "short"}` | Hardware button press |
| `shellyelevatev2/<id>/swipe_event` | `{"direction": "up"}` | Swipe gesture detected |
| `shellyelevatev2/<id>/hello` | `{device info}` | Published on connect |
| `shellyelevatev2/<id>/status` | `online` | Availability status |

**Button Press Types:**
- `short` - Quick press
- `long` - Held for 500ms+
- `double` - Two quick presses
- `triple` - Three quick presses

---

### Command Topics (Subscribe to Control Device)

Publish to these topics to control the device:

| Topic | Payload | Description |
|-------|---------|-------------|
| `shellyelevatev2/<id>/relay_command` | `ON` / `OFF` | Control relay 0 |
| `shellyelevatev2/<id>/relay_command_1` | `ON` / `OFF` | Control relay 1 |
| `shellyelevatev2/<id>/sleep` | any | Dim/sleep screen |
| `shellyelevatev2/<id>/wake` | any | Wake screen |
| `shellyelevatev2/<id>/reboot` | any | Reboot device |
| `shellyelevatev2/<id>/refresh_webview` | any | Reload WebView |
| `shellyelevatev2/<id>/update` | any | Request status update |

**Global Topics:**
- `shellyelevatev2/update` - Request all devices to publish status
- `homeassistant/status` - Listened for HA restarts (triggers re-publish of discovery)

---

### MQTT Examples

#### Turn on relay via MQTT
```bash
mosquitto_pub -h 192.168.1.50 -t "shellyelevatev2/shellyelevate-a3f2/relay_command" -m "ON"
```

#### Wake screen
```bash
mosquitto_pub -h 192.168.1.50 -t "shellyelevatev2/shellyelevate-a3f2/wake" -m ""
```

#### Subscribe to temperature updates
```bash
mosquitto_sub -h 192.168.1.50 -t "shellyelevatev2/+/temp"
```

#### Subscribe to button presses
```bash
mosquitto_sub -h 192.168.1.50 -t "shellyelevatev2/+/button"
```

---

### Home Assistant Auto-Discovery

When MQTT is enabled, ShellyElevate automatically publishes Home Assistant discovery configurations to:

```
homeassistant/device/<device_id>/config
```

After connecting, the device appears in Home Assistant with:

- ✅ Temperature sensor
- ✅ Humidity sensor  
- ✅ Light (lux) sensor
- ✅ Proximity sensor (if available)
- ✅ Screen brightness sensor
- ✅ Sleeping binary sensor
- ✅ Relay switch(es)
- ✅ Button event triggers
- ✅ Wake/Sleep/Reboot buttons
- ✅ Refresh WebView button

No manual configuration needed - it just works!

---

### Home Assistant Automation Examples

#### Toggle Light on Button Press

```yaml
automation:
  - alias: "Wall Display Button 1 - Toggle Kitchen Light"
    trigger:
      - platform: mqtt
        topic: "shellyelevatev2/shellyelevate-a3f2/button"
    condition:
      - condition: template
        value_template: "{{ trigger.payload_json.id == 1 and trigger.payload_json.type == 'short' }}"
    action:
      - service: light.toggle
        target:
          entity_id: light.kitchen
```

#### Wake Screen When Motion Detected

```yaml
automation:
  - alias: "Wake Display on Motion"
    trigger:
      - platform: state
        entity_id: binary_sensor.motion_sensor
        to: "on"
    action:
      - service: mqtt.publish
        data:
          topic: "shellyelevatev2/shellyelevate-a3f2/wake"
          payload: ""
```

#### Control Relay Based on Time

```yaml
automation:
  - alias: "Turn on Display Light at Sunset"
    trigger:
      - platform: sun
        event: sunset
    action:
      - service: mqtt.publish
        data:
          topic: "shellyelevatev2/shellyelevate-a3f2/relay_command"
          payload: "ON"
```

#### Use Temperature for Climate Control

```yaml
automation:
  - alias: "Adjust Climate Based on Display Temperature"
    trigger:
      - platform: mqtt
        topic: "shellyelevatev2/+/temp"
    condition:
      - condition: template
        value_template: "{{ trigger.payload | float > 25 }}"
    action:
      - service: climate.set_temperature
        target:
          entity_id: climate.living_room
        data:
          temperature: 22
```

---

## JavaScript Interface

An optional extended JavaScript interface allows the loaded webpage to directly interact with hardware. **This is disabled by default** and must be enabled in settings (`extendedJavascriptInterface: true`).

### JavaScript API Reference

All methods are accessible via the `ShellyElevate` object in the WebView context.

#### Relay Control

| Method | Parameters | Returns | Description |
|--------|-----------|---------|-------------|
| `ShellyElevate.getRelay(num)` | `num` (int): relay number | boolean | Get relay state |
| `ShellyElevate.setRelay(num, state)` | `num` (int), `state` (boolean) | void | Set relay state |

**Example:**
```javascript
// Turn on relay 0
ShellyElevate.setRelay(0, true);

// Check relay state
if (ShellyElevate.getRelay(0)) {
  console.log("Relay is ON");
}
```

---

#### Sensor Reading

| Method | Parameters | Returns | Description |
|--------|-----------|---------|-------------|
| `ShellyElevate.getTemperature()` | none | number | Temperature in °C |
| `ShellyElevate.getHumidity()` | none | number | Humidity in % |
| `ShellyElevate.getLux()` | none | number | Light level in lux |
| `ShellyElevate.getProximity()` | none | number | Proximity distance in cm |

**Example:**
```javascript
const temp = ShellyElevate.getTemperature();
const humidity = ShellyElevate.getHumidity();
const lux = ShellyElevate.getLux();

console.log(`Temperature: ${temp}°C, Humidity: ${humidity}%, Light: ${lux} lux`);
```

---

#### Screen Control

| Method | Parameters | Returns | Description |
|--------|-----------|---------|-------------|
| `ShellyElevate.getScreenBrightness()` | none | number | Current brightness (0-255) |
| `ShellyElevate.setScreenBrightness(level)` | `level` (int): 0-255 | void | Set brightness |
| `ShellyElevate.getCurrentScreenBrightness()` | none | number | Current brightness (0-255) |
| `ShellyElevate.sleep()` | none | void | Start screensaver/sleep |
| `ShellyElevate.wake()` | none | void | Stop screensaver/wake |

**Example:**
```javascript
// Dim screen to 50%
ShellyElevate.setScreenBrightness(128);

// Sleep after 5 seconds
setTimeout(() => {
  ShellyElevate.sleep();
}, 5000);
```

---

#### Screensaver Management

| Method | Parameters | Returns | Description |
|--------|-----------|---------|-------------|
| `ShellyElevate.getScreenSaverRunning()` | none | boolean | Is screensaver active |
| `ShellyElevate.getScreenSaverEnabled()` | none | boolean | Is screensaver feature enabled |
| `ShellyElevate.getScreenSaverId()` | none | number | Current screensaver ID |
| `ShellyElevate.setScreenSaverEnabled(enabled)` | `enabled` (boolean) | void | Enable/disable screensaver |
| `ShellyElevate.setScreenSaverId(id)` | `id` (int) | void | Set active screensaver |
| `ShellyElevate.keepScreenAlive(keepAlive)` | `keepAlive` (boolean) | void | Prevent screensaver activation |

**Example:**
```javascript
// Keep screen alive while video is playing
const video = document.querySelector('video');
video.addEventListener('play', () => {
  ShellyElevate.keepScreenAlive(true);
});
video.addEventListener('pause', () => {
  ShellyElevate.keepScreenAlive(false);
});
```

---

#### Device Information

| Method | Parameters | Returns | Description |
|--------|-----------|---------|-------------|
| `ShellyElevate.getDevice()` | none | string | Device model name |
| `ShellyElevate.getExtendedJavascriptInterfaceEnabled()` | none | boolean | Is extended JS interface enabled |
| `ShellyElevate.isInForeground()` | none | boolean | Is app in foreground |

**Example:**
```javascript
console.log("Device:", ShellyElevate.getDevice());
console.log("JS Interface enabled:", ShellyElevate.getExtendedJavascriptInterfaceEnabled());
```

---

#### Event Binding

| Method | Parameters | Returns | Description |
|--------|-----------|---------|-------------|
| `ShellyElevate.bind(eventName, jsFunctionName)` | `eventName` (string), `jsFunctionName` (string) | void | Bind JS callback to device event |

**Available Events:**
- `onScreenOn` - Screen powered on
- `onScreenOff` - Screen powered off
- `onScreensaverOn` - Screensaver started
- `onScreensaverOff` - Screensaver stopped
- `onMotion` - Motion/proximity detected
- `onButtonPressed` - Hardware button pressed (passes button ID)

**Example:**
```javascript
// Define callback functions
function handleScreenOn() {
  console.log("Screen turned on!");
}

function handleButtonPress(buttonId) {
  console.log("Button pressed:", buttonId);
  if (buttonId === 1) {
    // Do something with button 1
  }
}

// Bind events
ShellyElevate.bind("onScreenOn", "handleScreenOn");
ShellyElevate.bind("onButtonPressed", "handleButtonPress");
```

**Complete Example:**
```javascript
// Dashboard JavaScript integration
(function() {
  // Check if Android interface is available
  if (typeof ShellyElevate === 'undefined') {
    console.log("Android interface not available");
    return;
  }

  console.log("Device:", ShellyElevate.getDevice());

  // Event handlers
  window.handleScreenOn = function() {
    console.log("Display woke up");
    document.body.classList.remove('sleeping');
  };

  window.handleScreenOff = function() {
    console.log("Display going to sleep");
    document.body.classList.add('sleeping');
  };

  window.handleMotion = function() {
    // Keep screen alive when motion detected - this is just an example, since this could be done natively
    ShellyElevate.keepScreenAlive(true);
    setTimeout(() => ShellyElevate.keepScreenAlive(false), 30000);
  };

  window.handleButtonPress = function(buttonId) {
    console.log("Button", buttonId, "pressed");
    
    switch(buttonId) {
      case 1:
        // Toggle relay on button 1
        const state = ShellyElevate.getRelay(0);
        ShellyElevate.setRelay(0, !state);
        break;
      case 2:
        // Adjust brightness on button 2
        const brightness = ShellyElevate.getScreenBrightness();
        ShellyElevate.setScreenBrightness(Math.min(255, brightness + 50));
        break;
    }
  };

  // Bind events
  ShellyElevate.bind("onScreenOn", "handleScreenOn");
  ShellyElevate.bind("onScreenOff", "handleScreenOff");
  ShellyElevate.bind("onMotion", "handleMotion");
  ShellyElevate.bind("onButtonPressed", "handleButtonPress");

  // Display sensor data
  setInterval(() => {
    const temp = ShellyElevate.getTemperature();
    const humidity = ShellyElevate.getHumidity();
    const lux = ShellyElevate.getLux();
    
    document.getElementById('temp-display').textContent = temp.toFixed(1) + '°C';
    document.getElementById('humidity-display').textContent = humidity.toFixed(1) + '%';
    document.getElementById('lux-display').textContent = Math.round(lux) + ' lux';
  }, 5000);
})();
```

---

## Screen Savers

Multiple screensaver modes with configurable idle timeout:

| Screensaver | Description |
|-------------|-------------|
| **Clock** | Simple clock display |
| **Photo Frame** | Display images from URL |
| **Blank** | Turn screen to minimum brightness |

**Settings:**
- `screenSaver` - Enable/disable screensaver
- `screenSaverDelay` - Idle timeout in seconds
- `screenSaverMinBrightness` - Brightness when dimmed (0-255)
- `wakeOnProximity` - Wake when proximity sensor triggered
- `touchToWake` - Wake on touch

Screen savers activate after idle timeout or can be triggered manually via API/MQTT.

---

## Kiosk Mode

ShellyElevate runs as a full kiosk with robust features:

| Feature | Description |
|---------|-------------|
| **Foreground Service** | KioskService watchdog restarts app if it crashes |
| **Boot Receiver** | Auto-starts on device boot |
| **Crash Handler** | Logs crashes to `filesDir/crash_log.txt` and auto-recovers |
| **Hidden Settings** | Access via gesture: 10 taps left edge + 10 taps right edge |
| **Hardware Button Mapping** | Buttons trigger MQTT events and custom actions |
| **Lite Mode** | Disable WebView and run as hardware-only service |

---

## Lite Mode

Enable "Lite Mode" to run ShellyElevate as a background service only, without the WebView. Useful if you want to use a different dashboard app (like Home Assistant Companion, Fully Kiosk, WallPanel) but still access Shelly hardware via MQTT/HTTP.

**To enable:**
```bash
curl -X POST http://192.168.1.100:8080/settings \
  -H "Content-Type: application/json" \
  -d '{"liteMode": true}'
```

In Lite Mode:
- ✅ MQTT publishing continues
- ✅ HTTP API remains active
- ✅ Sensor reading works
- ✅ Relay control works
- ❌ WebView not loaded
- ❌ JavaScript interface unavailable

---

## Building from Source

### Requirements
- Android Studio or Gradle
- Android SDK (minSdk 24, targetSdk 24, compileSdk 35)
- Root access on target device

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

**Output:** `app/build/outputs/apk/`

---

## Contributing

Contributions, feature requests, and bug reports are welcome! Please:

1. **Fork the repository** and create a feature branch
2. **Test thoroughly** on actual hardware (Shelly Wall Display)
3. **Submit a pull request** with a clear description of changes
4. **Open issues** for bugs or feature requests

### Resources

- 📖 [Jailbreak Guide](https://github.com/RapierXbox/ShellyElevate/wiki/Jailbreak) - Hack your Shelly Wall Display
- 🏠 [Home Assistant Integration](https://github.com/RapierXbox/ShellyElevate/wiki/Integration-into-Home-Assistant) - Setup guide
- 🐛 [Issue Tracker](https://github.com/dbochicchio/ShellyElevate/issues) - Report bugs

---

## Credits & Related Projects

**ShellyElevate** is the original project by [RapierXbox](https://github.com/RapierXbox).

---

## License

This project is provided "as is" without warranty. Use at your own risk. See [LICENSE](LICENSE) for details.

---

## Troubleshooting

### Settings won't open
Try the gesture again: Tap left edge 10 times, **then** right edge 10 times. Or use the HTTP API: `curl http://<device-ip>:8080/settings`

### WebView shows offline
Check your Home Assistant URL is correct and accessible. Enable "Ignore SSL Errors" in settings if using self-signed certificates.

### MQTT not connecting
Verify broker address includes protocol (`tcp://` or `ws://`), check port, username, and password. View logs: `adb logcat | grep MQTT`

### Brightness not auto-adjusting
Ensure "Automatic Brightness" is enabled in settings and the device has a light sensor. Check `minBrightness` setting.

### App crashes on boot
Check crash log: `adb shell cat /data/data/me.rapierxbox.shellyelevatev2/files/crash_log.txt`

### Can't access HTTP API
Verify HTTP server is enabled in settings and port 8080 isn't blocked. Test: `curl http://<device-ip>:8080/`
