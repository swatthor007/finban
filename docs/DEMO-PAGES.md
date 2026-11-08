# Demo scam pages

Scam look-alike pages hosted by our own server, so the bubble and the closer link check have something real to find on stage.

## Start them

Restart the server with `scripts/serve-public.sh`, which sets `DEMO_PAGES=1`. If you start `uvicorn` by hand, set `DEMO_PAGES=1` yourself. `DEMO_PAGES=0` turns them off.

The list is at **https://galileo-tiliaceous-causally.ngrok-free.dev/demo**. Every page and sample text is listed there with its expected result.

**Before going on stage:** open `/demo` once in the browser on each demo phone. ngrok shows its own warning page first; tap **Visit Site**.

## The pages

| Page | Bubble result | After "Look at this link more closely" |
|---|---|---|
| `bank-login`, a plain login | Careful | **High risk** |
| `card-verify`, reward points card check | High risk | Button not offered |
| `parcel-fee`, small parcel fee | High risk | Button not offered |
| `kyc-update`, account blocked | High risk | Button not offered |
| `lucky-draw`, lottery win with a fee | High risk | Button not offered |
| `prize-app`, install an app to claim | High risk | Button not offered |
| `pension-bonus`, fake scheme | High risk | Button not offered |
| `police-notice`, digital arrest | High risk | Button not offered |
| `stock-tips`, guaranteed returns | High risk | Button not offered |
| `easy-job`, paid to like videos | High risk | Button not offered |

The app offers the closer look only when the first result is not high risk.

Keep the address bar visible when scanning, because it can stop being on screen after scrolling. Without it, `card-verify` and `parcel-fee` drop to Careful, and `bank-login` shows no warning.

## Suggested demo order

1. **`lucky-draw`:** open it, tap the bubble, and get an instant High risk.
2. **`bank-login`:** the page looks ordinary and the bubble only says Careful. Tap the closer look and it becomes High risk, because the page claims to be a bank but is not on that bank's site.
3. **Sample text:** run `scripts/demo_sms.py kyc` on the emulator, or send it from another phone. The warning appears without anyone tapping.
4. **A real site, such as `wikipedia.org`:** tap the bubble to show it stays quiet.

Use a real site for step 4. Our own address always scores at least Careful, because it is a temporary tunnel address.

## Sample texts

`scripts/demo_sms.py` lists them and `scripts/demo_sms.py <id>` sends one to the emulator. Phones need a text from a real unsaved number, so copy the wording from `/demo`.

Set `DEMO_BASE` to change the address used in the links. Repeating the same text within 2 minutes is ignored, so vary the sample.

## Why they cannot work as real scams

- **The bank pages are only pictures of a form.** They name SBI so the brand check has something real to catch, but they have no form, no script, no logo and read-only boxes, so nothing can be typed. A test enforces this.
- **Every other organisation is fictional.** Their forms point at the reserved `.invalid` domain.
- **Nothing can be sent.** There is no POST route, and the browser policy blocks form submission and network calls.
- **Clearly labelled.** Every page carries a demo banner and is marked noindex.
- **The "app" is a 38-byte text file.** It cannot be installed.

**Known risk:** a page naming SBI can still get reported, and it shares an address with the app's API, so a report could take the API down during the demo. Keep `DEMO_PAGES=0` except when rehearsing or presenting, and do not post the link publicly.

## What has been checked

Automated tests run each page and sample through the real rules, so the table above comes from the code. Results were tested with a tunnel-style address and with domain age switched off.

**Not yet seen end to end:** a bubble scan of these pages on the emulator or a phone, and the closer look through the live tunnel. ngrok's skip header worked with `curl` against `/health`, but the 2-second page fetch budget has not been tried on these pages. Do one full rehearsal after restarting.
