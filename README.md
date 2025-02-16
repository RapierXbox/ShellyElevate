# ShellyElevate
> [!CAUTION]
> All content in this repository is provided "as is" and may render your device unusable. Always exercise caution when working with your device. No warranty or guarantee is provided.

Shelly Elevate is an app designed for the Shelly Wall Display, codenamed Stargate, that add's full homeassistant functionality to the device. . The README provides a detailed tutorial on hacking your device, installing a launcher, configuring Shelly Elevate, and integrating it with Home Assistant.<br>
Video: https://github.com/user-attachments/assets/d6095593-97f1-4036-b6d5-d9b3466c385f <sub>old video<sub/>
## Hacking your Wall Display
There are two different methods to jailbreak your wall display, and you'll need to choose one:
* **UART**: This method is highly reliable, with nearly a 100% success rate. However, it requires soldering cables to your device and purchasing an inexpensive [USB2UART](https://aliexpress.com/item/32694152202.html) adapter.
* **MTKClient**: Although this method didn't work for me, it has been successful for others in the community. But it **can** work for you. It also doesnt require soldering.**TODO**

### Using UART
#### Install the required Software
* To interact with the UART2USB adapter, download a software like [PuTTY](https://www.putty.org).

* Obtain the [Anroid plattform tools](https://developer.android.com/tools/releases/platform-tools) for necessary interactions with your device.

* From the release tab, download the latest versions of the ShellyElevate APK, and the ultra-small launcher APK.

#### Disassembly and Soldering
##### 1. Remove Backplate:
* Remove the four screws holding the backplate in place and store them safely.<br>
<img src="https://github.com/RapierXbox/ShellyElevate/assets/65401386/9e2cd91a-49cb-4e09-ba2c-795ce61a37f5" width=350><img/>
* Lift the backplate cautiously and disconnect the FPC cable from the motherboard.<br>
<img src="https://github.com/RapierXbox/ShellyElevate/assets/65401386/e191d87a-7436-48a0-b0da-768f65cfd05b" width=550><img/>
##### 2. Remove Daughterboard:
* Remove the three screws securing the small daughterboard.<br>
<img src="https://github.com/RapierXbox/ShellyElevate/assets/65401386/0c880fed-36c8-47e6-9321-e538faafdd22" width=350><img/>
##### 3. Soldering:
* Prepare your soldering iron and two small wires.
* Prepare your soldering iron and two small wires.
* Your setup should look like this this:<br>
<img src="https://github.com/RapierXbox/ShellyElevate/assets/65401386/df5491c8-02c4-4b11-9984-849048d78136" width=300><img/>
##### 4. Reconnect Power and Connect Adapter
* Connect power to the daughterboard and connect it to the motherboard<br>
<img src="https://github.com/RapierXbox/ShellyElevate/assets/65401386/948a7e51-815b-4deb-8b2d-58e2f3c88134" width=300><img/><sub>picture by luka177<sub/>
* Connect the UART-to-USB adapter. Your setup should look like this:<br>
<img src="https://github.com/RapierXbox/ShellyElevate/assets/65401386/af3a176d-3b8d-4a5e-9afe-1b01265e4920" width=400><img/>
(**NOTE:** I accidentally switched up 3v3 and rx in this picture but you should get the idea.)
#### Connect to your Display
##### 1. Connect to UART Adapter:
* Connect to your UART adapter at 921600bps.
##### 2. Get root access:
* In the shell, type `su` to get root access.
##### 3. Enable ADB:
* Execute the following commands to enable ADB permanently: <br>
`settings put global development_settings_enabled 1` <br>
`settings put global adb_enabled 1`<br>
`setprop persist.adb.tcp.port 5555`<br>
`setprop service.adb.tcp.port 5555`<br>
`stop adbd`<br>
`start adbd`<br>
##### 4. Get IP Address:
* Obtain your IP address by typing `ifconfig`.
##### 5. Connect via ADB:
* On your computer, navigate to the platform tools directory and connect using: `./adb connect <your ip>:5555`
##### 6. Disable Shelly App:
* Disable the Shelly app with: `./adb root` <br>
`./adb shell pm disable cloud.shelly.stargate`<br>
##### 7. Install Launchers and Apps:
* Install the ultra-small launcher: `./adb install ultra-small-launcher.apk`
* Install the ShellyElevate app: <br>
`./adb install -g ShellyElevateV2.apk`
##### 8. Reboot and Launch
* Reboot the device with: `./adb shell reboot`
* After rebooting you are going to be met with a white screen!
##### 9. Setting up the Shelly Elevate app
* Click the bottom right of the display 10 times <br>
* After that click the bottom left of the display 10 times <br>
* You now will be in the settings. If you lost track of the times you pressed the right button just press the left button once to reset the count. <br>
* Now press on the Button to automaticly get the Home Assistant IP <br>
* If that doesnt work for you enter it in the text field below <br>
##### 10. Troublshooting:
* To return to the home screen, use: `./adb shell input keyevent 3`
## Configuration in Home Assistant
* In your configuration.yaml file add this:
```
switch:
  - platform: rest
    name: Relay Shelly Walldisplay
    resource: http://<your ip>:8080/relay
    body_on: "true"
    body_off: "false"
    is_on_template: "{{ value_json.state }}"
    headers:
      Content-Type: application/json
    scan_interval: 10

sensor:
  - platform: rest
    name: Temperature Shelly Walldisplay
    resource: http://<your ip>:8080/getTemp
    value_template: "{{ value_json.temperature }}"
    unit_of_measurement: "°C"
    scan_interval: 120

  - platform: rest
    name: Humidity Shelly Walldisplay
    resource: http://<your ip>:8080/getHumidity
    value_template: "{{ value_json.humidity }}"
    unit_of_measurement: "%"
    scan_interval: 120
```
### Using the Wall Display as a Thermostat
To use the Wall Display as a thermostat, add the following code to your configuration.yaml file. You can also replace the target heater with another entity if needed.
```
climate:
  - platform: generic_thermostat
    name: Thermostat Shelly Wall Display
    heater: switch.relay_shelly_walldisplay
    target_sensor: sensor.temperature_shelly_walldisplay
```

