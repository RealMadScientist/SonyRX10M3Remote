# Sony RX10M3 Remote Control App

An Android app to remotely control the Sony RX10M3 camera using Sony’s deprecated Camera Remote API.

---

## Features

- Capture photos including bulb and burst modes  
- Video recording control  
- Intervalometer with configurable intervals and shot counts  
- Live view preview from the camera  
- Connection status and mode indicators  
- Basic UI designed for Android 10+ devices  

---

## Disclaimer

**This app is developed solely for my personal use.** Once I’m satisfied with its functionality, I plan to stop active development.  
I have no intention to maintain or officially support this app for others, but I’m happy to make it publicly available for anyone interested.

---

## Development Environment

- Tested on Android 10 (API 29) device  
- Project configured for:  
  - Compile SDK: 33  
  - Min SDK: 29  
  - Target SDK: 33  
  - Java/Kotlin compatibility: Java 17 / Kotlin JVM 17  
- Built with Android Studio Meerkat Feature Drop (2024)  
- ViewBinding enabled for easier UI coding  

---

## Getting Started

### Prerequisites

- Android device with Wi-Fi support  
- Android Studio Meerkat Feature Drop (2024) or later  
- Basic familiarity with Android app installation via USB or adb  

### Build & Run

-> From the APK:
1. Enable “Install unknown apps” for your device.
2. Download the APK.
3. Tap to install.
4. Open the app and grant camera permissions.

-> From the source code:
1. Clone the repository:

   ```bash
   git clone https://github.com/RealMadScientist/SonyRX10M3Remote.git
   ```

2. Open the project in Android Studio.  
3. Connect your Android device or use an emulator with API 29+.  
4. Build and run the app via Android Studio’s Run configuration.

---

## Notes

- Uses Sony’s deprecated Camera Remote API — some features may not work on all camera firmware versions.  
- Intervalometer and burst capture features are fully functional.
- Gallery and file transfer have been implemented, but are buggy and incomplete.
- Developed with minimal UI to focus on core camera control functionality.
- Likely littered with bugs that haven't been tested/I wasn't bothered to deal with. E.g, connection to camera only works properly if it's set to manual mode from the start.

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

## Contact

I won't be actively maintaining this app in any way. If you have a problem, feel free to create a GitHub issue on the off chance that someone has a solution, but more than likely it's just a bug that I never got around to dealing with after it started working sufficiently for me.
