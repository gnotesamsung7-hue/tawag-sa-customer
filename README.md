# Tawag — Bluetooth order pager

Turns phones into restaurant pagers. No pager hardware, and customers don't need internet once the page is open.

| Device | What it does |
|---|---|
| **Staff phone** (Tawag APK) | Take orders, show the order QR, tap **Ready** to buzz, track wait times, history, stats, CSV export |
| **Customer phone** | Scans the QR. **No install:** web page + Bluetooth (Android Chrome). **With the Tawag app:** buzzes even when locked |
| **Now Serving board** (Tawag APK on a spare phone/tablet) | Flashes each ready order full screen, plays a chime and says the number out loud |

## Folders
- `www/` – the app (staff, board, customer pager). Version number: `APP_VERSION` in `www/index.html`
- `android/` – native Android project (Bluetooth broadcast, GATT server, background pager service)
- `docs/` – the customer web page opened by the QR codes (served by GitHub Pages)
- `demo/` – laptop demo with three devices side by side
- `.github/workflows/main.yml` – builds the signed APK on every push and publishes it to Releases

## One-time setup
1. **Signing key secrets** – repo → **Settings → Secrets and variables → Actions → New repository secret**. Add the 4 secrets from `github-secrets-tawag.txt` (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). Keep that file and `tawag-release.jks` backed up privately. Never upload them to the repo.
2. **Customer web page** – repo → **Settings → Pages** → Source **Deploy from a branch** → Branch **main**, folder **/docs** → Save. The page appears at `https://gnotesamsung7-hue.github.io/tawag/` after a minute.
3. **Build** – push any change (or **Actions → Build Tawag APK → Run workflow**). The APK appears under **Releases**, and the in-app QR always points to the latest one.

## Bluetooth details
- Staff phone broadcasts BLE manufacturer data (company `0xFFFF`): `'T' 'W' code code n1 n2 …` (up to 18 called numbers; top bit of each = "buzz again").
- Staff phone also runs a GATT server (service `7a1f0001-5a6e-4c1b-9a2d-7461776167aa`) for the no-install web page.
- Range is about 10–30 m indoors. A web page can hold roughly 5–7 connected phones at once (depends on the staff phone's Bluetooth chip). App pagers and the board have no limit.
