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

## Heat Web (iPhone, browsers, and Android <-> iPhone)
The `web/` folder is a no-install web app (a PWA) with the same ideas: rooms, chat, file transfer with ETA, Burn, profile and the easter egg. Phones pair by swapping two QR codes (a WebRTC handshake) and then talk directly, with no server. The phone that creates the room is the leader: it shows the first code, approves who joins, and relays group traffic.

1. Leader taps Create a room and shows the code. The other person taps Join a room and scans it, then shows their reply code.
2. The leader taps Next, scans the reply, and the phones connect. Add more people with + Add (up to 6 per room).
3. Everyone must be on the same Wi-Fi, or on one phone's hotspot. Keep Heat open on both phones during a transfer.

Hosting for iPhones: push the repo to GitHub, then Settings > Pages > Source: GitHub Actions. The `Deploy Heat Web` workflow publishes `web/` to `https://<you>.github.io/<repo>/`. Open that on an iPhone, tap Share > Add to Home Screen, and it works offline afterwards. If your URL differs from `https://blankbook101.github.io/Heat/`, change `PUBLIC_URL` at the top of `web/app.js`.

Android: Home > "Connect with iPhone or web (QR)" opens the same web app inside the Android app (a WebView with the files bundled as assets, so no internet is needed). Received files are written to Downloads/Heat through a small bridge. Android's name and gold-name state are passed in.

Notes: iPhones save received files through the share sheet (Save to Files). Files received on the web live in the browser's memory until saved, so very large files may fail on iPhone. Run locally with `cd web && python3 -m http.server 8123` and open `http://localhost:8123`.

## Credits
Font: Monocraft by Idrees Hassan, SIL OFL 1.1 (app/src/main/assets/licenses/Monocraft-OFL.txt).
QR libraries in web/vendor: qrcode-generator (MIT), jsQR (Apache-2.0). See web/LICENSES.md.

## Notes
- The first estimate uses 8 MB/s; after each transfer it learns your real speed.
- Android 12 / 12L: Wi-Fi Direct also needs Location switched on. Android 13+ does not.
- A foreground notification keeps messages and offers arriving in the background (tap Stop on it to go invisible).
- The Android app was first built on a real machine. The Heat Web screen (WebHeatActivity) was added later and has not been compiled. Heat Web itself was tested in headless Chromium only, not in Safari.
