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

1. Clone the repository:

   ```bash
   git clone https://github.com/yourusername/your-repo-name.git
   ```

2. Open the project in Android Studio.  
3. Connect your Android device or use an emulator with API 29+.  
4. Build and run the app via Android Studio’s Run configuration.

---

## Notes

- Uses Sony’s deprecated Camera Remote API — some features may not work on all camera firmware versions.  
- Intervalometer and burst capture features are fully functional but file transfer is handled externally for now.  
- Developed with minimal UI to focus on core camera control functionality.  

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

## Contact

For questions or feedback, please open an issue on GitHub or reach out via email.
