# ShellyElevate
> [!CAUTION]
> All content in this repository is provided "as is" and may render your device unusable. Always exercise caution when working with your device. No warranty or guarantee is provided.

Shelly Elevate is an app designed for the Shelly Wall Display, codenamed Stargate, that hosts a NanoHTTPD REST server as a background service upon booting. It automatically launches Chrome with Home Assistant, if available. The README provides a detailed tutorial on hacking your device, installing a launcher, setting up Chrome, configuring Shelly Elevate, and integrating it with Home Assistant.

## Hacking your Wall Display
There are two different methods to jailbreak your wall display, and you'll need to choose one:
* **UART**: This method is highly reliable, with nearly a 100% success rate. However, it requires soldering cables to your device and purchasing an inexpensive [USB2UART](https://aliexpress.com/item/32694152202.html) adapter.
* **MTKClient**: Although this method didn't work for me, it has been successful for others in the community. But it **can** work for you.

### Using UART
#### Install the required Software
* To interact with the UART2USB adapter, download a software like [PuTTY](https://www.putty.org).

* Obtain the [Anroid plattform tools](https://developer.android.com/tools/releases/platform-tools) for necessary interactions with your device.

* From the release tab, download the latest versions of the ShellyElevate APK, Chrome APK, and the ultra-small launcher APK.

#### Disassembly and Soldering
##### 1. Remove Backplate:
* Remove the four screws holding the backplate in place and store them safely.
* Lift the backplate cautiously and disconnect the FPC cable from the motherboard.
##### 2. Remove Daughterboard:
* Remove the three screws securing the small daughterboard.
##### 3. Soldering:
* Prepare your soldering iron and two small wires.
* Prepare your soldering iron and two small wires. <sub>rip two wall displays</sub>
* Your setup should look like this this:
![img1](https://github.com/RapierXbox/ShellyElevate/assets/65401386/df5491c8-02c4-4b11-9984-849048d78136)
##### 4. Reconnect Power and Connect Adapter
* Connect power to the daughterboard and connect it to the motherboard
![img2](https://github.com/RapierXbox/ShellyElevate/assets/65401386/948a7e51-815b-4deb-8b2d-58e2f3c88134)
* Connect the UART-to-USB adapter. Your setup should look like this:
![img3](https://github.com/RapierXbox/ShellyElevate/assets/65401386/af3a176d-3b8d-4a5e-9afe-1b01265e4920)
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
* Install Chrome and the ShellyElevate app: <br>
`./adb install Chrome.apk` <br>
`./adb install ShellyElevate.apk`
##### 8. Reboot and Launch
* Reboot the device with: `./adb shell reboot`
* After rebooting, Chrome should open with your Home Assistant instance. If it doesn't, specify your IP and compile the app yourself.
##### 9. Troublshooting:
* To return to the home screen, use: `./adb shell input keyevent 3`
