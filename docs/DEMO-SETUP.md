# Real phone + demo setup

Everything below was written from the code and the emulator runs. **Nothing here has been run on a real phone yet**: Android 12 vs 13 differences are from Android docs, not from our testing.

## 1. Server (your laptop)

The server only reads real environment variables (there is no `.env` loader; `.env.example` is a template, and). Real names:

| Variable | What it turns on |
|---|---|
| `OPENROUTER_API_KEY` + `LLM_MODEL` | AI-written explanation ("AI-assisted" badge). Without both: rules text only |
| `SIGHTENGINE_USER` + `SIGHTENGINE_SECRET` | AI-photo check. Without: verdict is always `unknown` |
| `FIREBASE_CREDENTIALS` (path to the service-account JSON) + `GOOGLE_CLOUD_PROJECT=finban-61e40` | Token checks, pairing, alerts, push |
| `LOG_SALT` | Any random string for the demo |
| `LINK_FETCH=0`, `DOMAIN_AGE=0`, `LLM_DISABLED=1`, `IMAGE_CHECK=0` | Kill switches if a service is flaky on stage |

```sh
cd server
set -a; source ../.env; set +a        # your private copy of .env.example with the real names above
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

`--host 0.0.0.0` matters: the phone connects over the network.

## 2. How the phones reach the server (any network)

Only the FastAPI server needs to be reachable. Firebase (sign-in, pairing, alerts, push) is already in the cloud, so the two phones can be on different Wi-Fi networks or mobile data as long as the laptop's server has a **public HTTPS address**. Use `scripts/serve-public.sh`, which starts the server and the tunnel and prints the URL after checking `/health` through it:

| Tool | Command | Notes |
|---|---|---|
| **Tailscale Funnel** (installed on this laptop) | `scripts/serve-public.sh funnel` | Stable `https://<machine>.<tailnet>.ts.net`; phones need nothing installed. Needs Tailscale connected and Funnel allowed for this machine in the Tailscale admin console. |
| **ngrok** | `NGROK_DOMAIN=your-name.ngrok-free.app scripts/serve-public.sh ngrok` | `brew install ngrok`, `ngrok config add-authtoken ...`, claim the free static domain so the URL never changes. |
| **cloudflared** | `scripts/serve-public.sh cloudflared` | `brew install cloudflared`, no account, but a **new URL every run**, so re-enter it on both phones each time. |
| none | `scripts/serve-public.sh local` | Laptop only: for `adb reverse tcp:8000 tcp:8000` over USB. |

**Set the server address BEFORE pairing** (a fresh install points at the build default, `127.0.0.1`, which only works on the dev machine). Any of these, no rebuild:

1. **Link:** open `finban://server?url=https://YOUR-URL` on the phone (e.g. from `adb shell am start -a android.intent.action.VIEW -d "finban://server?url=https://YOUR-URL"`, or a QR code / a link in a browser that opens it). The app shows "Server set to ...".
2. **adb:** `adb shell am start -n devr.shellshock.finban/.ui.MainActivity --es server_url https://YOUR-URL`
3. **On the phone:** on the first screen, tap the logo/name row **5 times** to open Developer tools, set the address, go back.
4. **When pairing fails:** the trusted-person step shows a "Cannot reach the server" box with an address field and "Save address and try again".
5. **At build time:** `finban.serverUrl=...` in `android/local.properties` (needs a rebuild; random ngrok URLs change, so prefer the options above).

Then on each phone: **Settings > Developer tools > Server address > paste the https URL > Save > Test connection**. No rebuild. The build default still comes from `android/local.properties` (`finban.serverUrl=...`).

Notes:
- The app sends `ngrok-skip-browser-warning` so ngrok's free-tier warning page cannot get in the way of API calls.
- Mobile data adds latency. The app gives a text check 3.5 s in total (the server's own budget is 2.6 s), so use the Developer tools timings to see the margin before the demo.
- The tunnel makes the API public. Every endpoint except `/health` requires a valid Firebase sign-in token, and `/debug/status` too. Stop the script when you are done (Ctrl-C also switches Funnel off).

## 3. Build and install

- `android/app/google-services.json` is gitignored: copy it from the Firebase console (project `finban-61e40`) before building on any new machine. Same for the service-account JSON.
- `cd android && ./gradlew assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk` for each phone (USB debugging on). Or send the APK file to the phone and install it.
- **Reinstalling switches the accessibility service off.** Re-enable it after every install.

## 4. On each phone (the order matters)

1. Open Finban, finish onboarding, pick **elder** on one phone and **caretaker** on the other.
2. **Android 13 only:** the accessibility toggle is greyed out for sideloaded apps. Settings > Apps > Finban > ⋮ > **Allow restricted settings**, then enable it. Android 12 does not need this.
3. Settings > Accessibility > Finban: on. (The bubble appears.)
4. Settings > Notifications > Device & app notifications (or "Notification access"): Finban on. (Reads the SMS app's notification as the second SMS source.)
5. Allow Notifications (Android 13 asks at runtime), SMS, Contacts, Phone when asked.
6. Battery: Settings > Apps > Finban > Battery > **Unrestricted** (Samsung: also "Never sleeping apps"; Xiaomi/Oppo/Vivo: auto-start on). Otherwise push arrives late or not at all.
7. **Pair:** elder phone > Settings > Trusted contacts > shows a code; caretaker phone enters it.
8. Open **Settings > Developer tools** on each phone and press, in order: *Test connection*, *Check what the server has set up*, *Text check*, *Link check*, *Photo check*, *Sign-in and push token*. The "This phone" list shows every missing permission with a **Fix** button. Everything should be ticks before you demo.

If the AI line says "rules only, no AI", the server has no `OPENROUTER_API_KEY`/`LLM_MODEL` (see *Check what the server has set up*). If the photo check says "could not tell", the SightEngine keys are missing.

## 5. Test push to a phone

`FIREBASE_CREDENTIALS=/path/sa.json server/.venv/bin/python -I scripts/push_smoke.py <uid>` (uid from Firestore > users). Real-phone push has **never been verified**, so do this on the day before.

## 6. Demo script (what works)

- **Simulate a scam text** (Developer tools > Demo) or send a real SMS from another phone: popup sheet appears over whatever app is open. Real SMS from a *saved contact* is skipped.
- **Bubble over Chrome:** open the test pages (see below), tap the bubble. The bubble hides while a sheet is open and inside Finban.
- **Photo:** Home > Check a message or photo.
- **Tell my trusted person** on a high-risk sheet pushes the caretaker phone (needs pairing + push working).
- Home shows the log; tap a row for the full details.

Test pages used so far were served from the laptop (`python3 -m http.server 8080` in a folder with `phish.html`, `giveaway.html`, `chat-scam.html`, `chat-id.html`); they live in the session scratchpad, not in the repo. Copy them into the repo (e.g. `data/demo/`) if you want them for the demo.

## 7. Do not demo (not built or unverified)

- Phone-call and payment-app detection: observe-only, no warning shown (S7 is out of the prototype plan).
- Tamil text everywhere (English only for now).
- Bank-header allowlist: dropped, so a spoofed bank SMS looks like any other. Texts from saved contacts are skipped.
- Server `DEMO_MODE` canned scenarios: the flag exists in `/health`, nothing implements it.
- 1930 shows as `1 (930` in an emulator set to the US region; expect `1930` on an Indian SIM (not yet confirmed).
- Anything on a real phone: FCM delivery, restricted-settings flow, battery savers, notification importance.
