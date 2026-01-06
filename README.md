
<img width="1280" height="2856" alt="Screenshot_20260106-212115" src="https://github.com/user-attachments/assets/23ca24d2-cd20-431f-936e-66b9abb8aa8e" />

<p align="center">
  <b><h1>Utility for Stadia Controller</h1></b>
</p>

Stadia Controller Dumper is a professional-grade open-source utility built for Android to interface with Google Stadia hardware (H02A). 
It enables researchers and enthusiasts to perform full firmware extractions and diagnostic dumps directly from an Android device using a USB-C cable.

Project Scope
This mobile tool leverages the Android USB Host API to communicate with the controller without requiring root access. It is designed for:

Firmware Backup: Preserving original device states before Bluetooth-mode conversion.

Hardware Analysis: Reading diagnostic strings and configuration blobs via HID.

USB Host Research: A reference implementation for low-level USB communication in Kotlin/Java.

| Feature       | Status         | Notes                  |
| ------------- |:--------------:| ---------------------- |
| Firmware Dump | ✅ Supported   | Full H02A extraction   |
| USB Handshake | ⚡ Fast         | Native Android Host API|
| MD5 Checksum  | 🛠️ In Progress | Verification step      |

| Capability| Official Web Tool	| UTFC               |
|-------------|-------------------|------------------|
| Portability	| Requires Desktop	| Mobile Ready     |
| Connection	| Chrome WebUSB	| Android USB Host     |
| Offline Mode |	No | Yes
Logging |	Minimal	| Full (wip) | 
