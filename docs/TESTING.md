# Testing Ping with only ONE phone

You don't need two phones. Three options, easiest first.

## Option A — your phone + this laptop (recommended, real radios, zero internet)

The laptop runs the same mesh core as the app, over the LAN lane.

1. **Install the app** on the phone (USB debugging on):
   ```
   cd C:\Users\vaidz\documents\meshaid
   .\gradlew.bat :android:app:assembleDebug
   C:\Users\vaidz\android-build\android-sdk\platform-tools\adb.exe install android\app\build\outputs\apk\debug\app-debug.apk
   ```
2. **Turn ON the phone's hotspot** and turn OFF its mobile data (proves zero internet).
   Connect this laptop's Wi-Fi to the phone's hotspot.
3. **Run the desktop node** on the laptop:
   ```
   .\gradlew.bat :tools:node:run --console=plain -q
   ```
   (Windows Firewall may ask once — allow on private networks.)
4. **Open the app.** First launch asks you to create an identity: pick a callsign and a
   password (8+ characters) — this is entirely local, there is no account server; see
   [SECURITY.md](SECURITY.md) for exactly what the password protects. Subsequent
   launches ask for that password to unlock the identity instead. After unlocking, grant
   the requested permissions. Within ~10 seconds the laptop prints
   `* Ping-xxxxxx joined the mesh` (or your chosen callsign) and the app's peer count
   goes to 1.
5. **Test everything:**
   - Type in the laptop console → appears in the app's SIGNALS tab; send from the app → prints
     on the laptop. Both directions should show **✓** (signature verified against the
     announced identity key).
   - **Encrypted DM:** open the app's ROSTER tab — the laptop appears under "NEARBY — NOT YET
     ADDED" (the laptop prints its own name at startup). Tap **ADD**, then tap the row to open
     its thread and type a message. On the laptop, reply with `@<your-app-name> hi`. Thread
     messages show verified (✓) once the laptop's key has propagated; the laptop console shows
     `[encrypted DM] ✓`.
   - App **SOS** button → laptop prints `!!! SOS ... !!!` with the GPS fix.
   - Laptop `/loc 26.9124 75.7873` → sends a synthetic GPS beacon (feeds the app's peer map).
   - App **📷** → photo lands in `%USERPROFILE%\.meshaid\received\`.
   - Laptop `/photo C:\path\to\some.jpg` → appears inline in the app chat.
   - **DTN store-carry-forward:** close the app (swipe away so the service dies), type a
     message on the laptop, reopen the app → the message syncs over on contact. Chat history
     survives the restart (persisted locally).

What this validates: the full stack above the radio (protocol, routing, dedup, DTN sync,
media pipeline, crypto identity, UI) on a real phone over a real radio (Wi-Fi). What it
does NOT validate: the BLE GATT lane and multi-hop relaying.

## Option B — two Android emulators with virtual Bluetooth (tests the BLE lane)

Recent Android emulators simulate Bluetooth between instances (netsim/root-canal).

1. Install emulator + a system image (one-time, ~2 GB):
   ```
   set JAVA_HOME=C:\Users\vaidz\android-build\jdk-17.0.19+10
   C:\Users\vaidz\android-build\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat "emulator" "system-images;android-35;google_apis;x86_64"
   C:\Users\vaidz\android-build\android-sdk\cmdline-tools\latest\bin\avdmanager.bat create avd -n mesh1 -k "system-images;android-35;google_apis;x86_64"
   C:\Users\vaidz\android-build\android-sdk\cmdline-tools\latest\bin\avdmanager.bat create avd -n mesh2 -k "system-images;android-35;google_apis;x86_64"
   ```
2. Launch both with Bluetooth simulation:
   ```
   emulator -avd mesh1 -feature BluetoothEmulation
   emulator -avd mesh2 -feature BluetoothEmulation
   ```
3. `adb -s emulator-5554 install …apk`, same for `emulator-5556`, open the app in both.
   The two emulators share one virtual radio environment and should discover each other
   over BLE. (LAN lane also works between emulators via the host network.)

Caveat: virtual BLE is young — if advertising/GATT misbehaves on the emulator it is not
necessarily a bug in the app; real-device confirmation is still Phase 0's exit gate.

## Option C — borrowed phones (the honest field test)

Any Android of a friend/family member with the APK sideloaded. The three-phone relay
test (A—B—C with B in the middle, A and C out of range of each other) and the battery
overnight test are the two that only real hardware can answer.
