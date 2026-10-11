# Heat

Offline Android-to-Android chat and file transfer. Bluetooth LE finds people and carries chat and the handshake; Wi-Fi Direct moves the files. No internet, no accounts.

## Build
Open the `Heat` folder in Android Studio (Ladybug or newer) and run on two phones (Android 12+), or:

    ./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk

## How a transfer works
1. Both phones advertise the Heat BLE service (random id + username). Scanning only runs on the Send screen.
2. Sender taps a name, picks files, sees the size and estimated time, taps Send.
3. The offer goes over BLE. The receiver accepts, creates a Wi-Fi Direct group with a random name and passphrase, and returns those over BLE. No pairing dialogs.
4. The sender joins the group and streams the files over TCP (port 8988). Live speed and time left are shown on both phones.
5. Files land in `Downloads/Heat/` as `YYYY-MM-DD_HH-mm-ss_<name>`. The group, sockets and BLE hold are torn down afterwards. Chat messages live in memory only.

## Burn
Tap "Heat" (top left on Home) to open the flame screen. Pick received files and "burn" them: they are moved to `Downloads/Heat/Recently Burned/`, never deleted, and can be restored from the "Recently burned" tab. Only files saved by this install of Heat are listed (Android scoped storage).

## Groups
Send screen > "Create a group" makes you the leader (Wi-Fi Direct group owner, up to 6 members). Nearby phones list it under Receive > "Groups nearby" and ask to join; the leader taps Allow. Everyone in the group can then "Send files to the group": the leader pushes to all members, a member's files go to the leader who relays them. Received files land in Downloads/Heat. While you are in a group, single transfers are paused.

## Profile and the easter egg
Tap your avatar/name on Home for the profile (name, photo). Your photo is a 96x96 JPEG sent over Bluetooth on request. Secret: on the Burn screen, tap the flame 7 times quickly. That unlocks the Highland cow profile picture and turns your name gold in a Monocraft (Minecraft-style) font for everyone nearby.

## Credits
Font: Monocraft by Idrees Hassan, SIL OFL 1.1 (app/src/main/assets/licenses/Monocraft-OFL.txt).

## Notes
- The first estimate uses 8 MB/s; after each transfer it learns your real speed.
- Android 12 / 12L: Wi-Fi Direct also needs Location switched on. Android 13+ does not.
- A foreground notification keeps messages and offers arriving in the background (tap Stop on it to go invisible).
- Not compiled or run in the authoring environment (no Android SDK there). Expect to fix a small compile error or two on first build and to tune on real devices.
