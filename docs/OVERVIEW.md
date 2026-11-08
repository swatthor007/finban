# Finban: product overview

Source material for pitch decks, write-ups and demos. Status as of 2026-11-05.
Section 9 lists what is safe to claim and what is not, so check it before reusing anything here.

## 1. In one line

Finban is an Android assistant that helps older people in Tamil Nadu spot scams and pause before they pay, and brings in someone they trust when it matters.

**Tagline from the app:** "Pause for a minute before you pay."

## 2. The problem

Scams on phones work by creating panic and isolating the person. A caller says the bank account is blocked, the police are involved, or a family member is in trouble, and insists on payment right now and in secret.

Older people are exposed in particular ways:

- Scam messages and fake sites can look official, and the warning signs are technical (a sender format, a look-alike web address).
- Most protection tools are made for English-speaking, confident phone users.
- The family often finds out only after the money has gone.
- Scams cross channels: a text, a WhatsApp message, a phone or WhatsApp call, then a payment app. A tool that watches one channel misses the moment that matters.

> Add sourced figures here before using this in a deck (for example, national cybercrime loss numbers). This repo holds no verified market or loss statistics.

## 3. Who it is for

| Person | What they get |
|---|---|
| **Older person** (primary user) | Warnings in plain language, big text, large buttons, and a pause before paying |
| **Trusted person** (son, daughter, relative) | An alert when the older person asks for help or confirms pressure to pay, with enough detail to phone them and no view of their private messages |

Both use the same app. The role is chosen at first launch, and the two phones are linked with a 6-digit code that lasts 10 minutes and works once.

## 4. Principles

1. **The app warns, the person decides.** Finban never blocks, clicks, types, sends or files anything in another app. Every action is a button the person taps.
2. **It never says "safe" or "real."** The best possible result is "No warning signs found." A clean check is not proof.
3. **Rules decide, AI explains.** Risk level comes from deterministic checks. AI only rewords the explanation and can never change the verdict.
4. **Family gets signals, not private content.** Trusted people see who, when, which scam pattern and what triggered the alert. They never see message text, numbers, screenshots or amounts.
5. **Designed for older eyes and hands.** Body text is at least 18sp, tap targets are 56dp, and there are three severity levels with both colours and icons. It uses bottom sheets rather than pop-ups.

## 5. How a person uses it

### Five ways a check happens

| Way in | What happens | Who starts it |
|---|---|---|
| **Automatic text check** | An SMS or WhatsApp message from someone not saved in contacts is checked in the background. A risky one brings up a warning over whatever is on screen. | Automatic |
| **Floating bubble** | Tap the bubble over any app. Finban reads the screen and shows a result on top of it. | The person |
| **Share to Finban** | Share a message, link or photo from any app. | The person |
| **Photo check** | Pick a screenshot or photo in the app. Text in it is read on the phone (Latin script only for now). | The person |
| **Payment pause** | A payment or banking app opens during an unknown call or within 10 minutes of one. Finban steps in with three questions. | Automatic |

### The result

Every check ends in one of three outcomes:

- **High risk:** "Stop. This looks like a scam."
- **Careful:** something looks wrong and is worth a second look.
- **No warning signs found:** automatic checks stay silent here, so the person is not interrupted.

A warning gives the reasons in plain words and what to do next. From there the person can tell their trusted person, look more closely at a link, or check whether a picture was made by a computer.

### The payment pause

This is the most distinctive feature, because it acts at the moment money is about to move rather than when a message arrives.

It fires when all of these are true:

- A call is active or ended in the last 10 minutes.
- The caller is not a saved contact.
- A payment or banking app comes to the front.

It then asks, one at a time:

1. Is someone on a call telling you to pay?
2. Did they say you are in trouble or must hurry?
3. Did they tell you to keep this secret?

The first "Yes" brings advice not to pay yet and alerts the trusted person. "Skip, I am fine" is always available, and the payment app is never touched.

### If money has already gone

Home has a route for this:

- An **"I already paid"** screen.
- A button that opens the dialler with **1930**, the national cybercrime helpline. The person taps call themselves.
- A link to **Chakshu** on Sanchar Saathi for reporting a suspected fraud attempt.
- An option to tell the trusted person.

### The trusted person's side

1. The older person taps "Tell my trusted person", or says Yes on the pause.
2. The trusted person gets a push notification.
3. Opening it shows the severity, the time and the signs seen, plus a reminder that only warning signs are visible.
4. They can phone straight away and mark "I'm calling now".

## 6. How detection works

```text
 SMS / WhatsApp        Bubble          Share / photo
 (unsaved senders)   (screen text)    (text, links, image)
        \                 |                 /
         v                v                v
             Android app extracts text and links
                          |
                          v
            Server: POST /analyze (rules first)
   +------------------------------------------------+
   |  Sender checks     who does this claim to be?  |
   |  Link checks       does the address add up?    |
   |  Domain age        was this site just created? |
   |  Scam patterns     does it match a known play? |
   +------------------------------------------------+
                          |
              severity: high / careful / none
                          |
                          v
         AI rewrites the explanation (optional)
                          |
                          v
          Bottom sheet over the current app
                          |
                          v
       Person decides: close, inspect, tell family
```

### The layers

**Sender checks.** A bank-style message from an ordinary mobile number is high risk, because banks use named senders. A sender can also be compared against about 23,000 TRAI-registered SMS headers (2020 list). A bank-looking sender with a link outside that bank's known domains is flagged, which catches some spoofed senders.

**Link checks.** These run locally on the server:

- Look-alike and homoglyph addresses, including mixed scripts and punycode.
- Brand names inside the wrong domain, such as `sbi-kyc-update.xyz`.
- Link shorteners, bare IP addresses, risky endings, temporary tunnel addresses and `.apk` downloads.
- Comparison with official bank, government and popular-domain lists.

Banks moving to the `.bank.in` domain are accounted for.

**Domain age.** A live registry (RDAP) lookup flags sites registered very recently, within 14 days as high risk and within 90 days as careful. If the age can't be found, nothing is flagged.

**Scam patterns.** 13 pattern definitions cover these types:

- Digital arrest
- Fake KYC or account blocked
- Family emergency money request
- OTP request
- Refund tricks (three variants)
- Reward or prize fee
- Task or job scam
- Investment group
- Fake government scheme
- Screen-share support
- SIM swap signs

One weak clue alone doesn't trigger a warning. It needs a second signal, such as a suspicious link, phone number or request for money.

**"Look at this link more closely" (on request).** The server opens the page safely and checks whether it claims a brand it doesn't own, asks for secret details, sends form data elsewhere or starts a download. This only runs when the person taps, because fetching tells the site owner that the link was opened. It can only add warnings, never remove them.

**Pictures.** When a screen has little text, or a big picture, a vision model reads and interprets the screenshot. Separately, the person can tap "Check if this picture was made by a computer" to send just that picture to SightEngine, an AI-image detector.

### Where AI is used, and where it isn't

| Job | Done by |
|---|---|
| Deciding the risk level | Rules and domain checks |
| Writing the plain-language explanation | Language model through OpenRouter, using findings only (never the message, sender or links), with template text as fallback |
| Reading text and meaning from screenshots | Vision model through OpenRouter |
| Spotting computer-made images | SightEngine, only on request |

This split is a strong story: the verdict is explainable and repeatable, and an AI failure just means plainer wording.

## 7. Privacy and permissions

**What leaves the phone**

- Automatic text checks send the text and sender of messages from unsaved senders to the Finban server.
- Screen, share and photo checks send what the person chose to check.
- Screenshots may go to the vision provider through OpenRouter when a screen is mostly a picture. This doesn't run in payment apps.
- A picture goes to SightEngine only after a tap.

**What the server keeps**

- Logs hold a hashed user ID, severity, pattern and timing. A test asserts that message text, sender, links and raw user IDs stay out.
- Images are handled in memory only.

**What stays on the phone**

- Recent careful and high-risk checks, up to 20, including the sender and up to 1,500 characters of the message, so the person can look back.

**What caretakers see**

- Only the signals listed in Section 4.

**Permissions deliberately not requested**

- `READ_SMS`, `READ_CALL_LOG` and `SYSTEM_ALERT_WINDOW`. Finban reads incoming texts through the SMS broadcast or a notification, and draws through its accessibility service.

**Known prototype decisions**

- There is no redaction of OTPs or other details in text sent to the server yet.
- Saved contacts are skipped entirely, so a hacked friend's account would not be auto-checked.

## 8. Technology

| Part | Built with |
|---|---|
| Android app | Kotlin, Jetpack Compose, minSdk 29, accessibility overlay for the bubble and sheets, ML Kit on-device text recognition |
| Server | Python, FastAPI, Pydantic |
| Accounts and linking | Firebase anonymous sign-in, Firestore, Cloud Messaging for push |
| AI | OpenRouter (explanations and vision), SightEngine (AI-image detection) |
| Reference data | TRAI SMS headers, Tranco and `.in` popular domains, bank and brand domain lists, 13 scam patterns |

Daily cost caps on AI and vision calls protect the budget. Past a cap, the app falls back to template text.

## 9. Current status

This is a hackathon prototype. It runs end to end on an Android emulator against a live server.

| Area | Status |
|---|---|
| Automatic SMS check | Works on the emulator; real-phone traffic has been seen reaching the server |
| Bubble, share and photo checks | Work on the emulator |
| Domain and sender checks | Built, with 403 server tests passing |
| AI explanation | Live: 20 of the last 56 logged text checks used it |
| Vision and AI-image check | Live calls succeeding |
| Payment pause | Works on the emulator with a debug payment app; not seen with real GPay or a WhatsApp call |
| Pairing | Code pairing has succeeded between two devices |
| Trusted-person alert | Requests succeed; delivery to a real phone has not been confirmed in these notes |
| "I'm calling now" | Saved, but the older person's screen does **not** yet update live |
| WhatsApp check | Built; confirmed only through its notification path in code, not on a real phone |
| Tamil | **In progress.** Server warnings and patterns are in Tamil (committed). App screens have a Tamil translation and an instant language switch (committed). Confirm a Tamil speaker has reviewed it and see it on a phone |
| Voice read-aloud | Not connected to the result sheet |
| Guided report packs | Not built; 1930 and Chakshu are simple links |
| Offline checks | Not built; there is no on-device rules engine |
| Demo scam pages | 10 scam look-alike pages at `/demo` with automated checks; see `docs/DEMO-PAGES.md` |
| Hosting | Runs on a laptop through an ngrok tunnel |

### Safe to claim

- Checks SMS, WhatsApp, screens, links and photos through one pipeline.
- Rules decide the verdict; AI only explains it.
- Trusted people never see message content.
- Interrupts at the moment of payment during or after an unknown call.
- Never takes action on the person's behalf.

### Do not claim yet

- **"Fully available in Tamil."** Hold this until the wording has been reviewed and seen on a real phone. It is central to the pitch.
- **"100% detection" or "zero false alarms."** The 17/17 and 0/25 results come from 42 synthetic messages. They are a regression check, not a real-world accuracy figure.
- **"Tested on real elderly users."** No user testing is recorded.
- **"Live update when family calls back."** The older person's side isn't wired up.
- **"Reads Tamil text in photos."** On-device recognition is Latin script only.
- **"Verified bank list."** Domain lists are marked unverified until confirmed by hand.
- **"Catches every scam text."** A plain request for money from an unknown number, with no link, currently gets no warning.

## 10. Why this could win

- **It acts at the moment of payment,** not only when a message arrives.
- **It connects channels:** a call plus a payment app is a stronger signal than either alone.
- **It involves family without surveillance:** help comes with no access to private content.
- **Its verdicts are explainable:** every warning has concrete reasons, not a black-box score.
- **It is built around one specific audience:** older users in Tamil Nadu, in Tamil.
- **It uses Indian data:** TRAI headers, Indian bank domains, and 1930 and Chakshu.

## 11. Example demo story (illustrative)

1. **The text.** Mrs. Lakshmi (fictional) gets a text from an unknown number: her bank account is blocked and she must update KYC at a link. A high-risk warning slides up over her home screen and explains why.
2. **The call.** A "bank officer" phones from an unknown number and tells her to pay a fee.
3. **The pause.** She opens her payment app. Finban asks: is someone on a call telling you to pay? She taps Yes.
4. **The family.** Her daughter's phone gets an alert showing the warning signs, but not the message. She taps "I'm calling now" and phones.
5. **The outcome.** Lakshmi closes the app. Finban never stopped her; it gave her a minute to think and someone to talk to.

Steps 1 to 3 are demonstrated on the emulator. Step 4 needs delivery confirmed on two real phones. See `docs/DEMO-SETUP.md` for setup.

## 12. Glossary

| Term | Meaning |
|---|---|
| **1930** | India's national cybercrime helpline |
| **Chakshu** | Government facility on Sanchar Saathi for reporting suspected fraud communications |
| **Digital arrest** | A scam in which callers pose as police and hold a victim on a call under threat |
| **KYC** | "Know your customer," bank identity verification and a common scam pretext |
| **TRAI header** | The registered sender name on Indian business SMS, such as `VM-SBIBNK` |
| **RDAP** | The public registry lookup that shows when a domain was registered |
| **Trusted person** | The user-facing name for the caretaker |
