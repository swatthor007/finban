"""Turns a rules result into the /analyze response: reason text, titles, actions, audio_url.

Wording rules: never "safe" or "real" about a message; for email say "this address doesn't match",
never "this email is fake" (we cannot see SPF/DKIM). The same rules hold for the Tamil text, which is plain everyday
Tamil: common loan words (மெசேஜ், லிங்க், வெப்சைட், போன்) rather than literary ones. Every English text here has a Tamil twin.
"""
from __future__ import annotations

import os
from pathlib import Path

from . import rules
from .models import Reason

AUDIO_DIR = Path(os.environ.get("AUDIO_DIR") or Path(__file__).resolve().parents[1] / "audio")

TITLES = {
    "high": ("நிறுத்துங்கள். இது மோசடி போல் தெரிகிறது.", "Stop. This looks like a scam."),
    "careful": ("கவனமாக இருங்கள்.", "Be careful."),
    "none": ("ஆபத்து அறிகுறி எதுவும் தெரியவில்லை.", "No warning signs found."),
}
EXPLANATION_EN = {
    "high": "This message has warning signs of a scam. Do not pay, and do not share any OTP or PIN. Ask someone you trust.",
    "careful": "Something here does not look right. Check with someone you trust before you reply or pay.",
    "none": "No warning signs found. If you still feel unsure, ask someone you trust.",
}
EXPLANATION_TA = {
    "high": "இந்த மெசேஜில் மோசடி அறிகுறிகள் உள்ளன. பணம் அனுப்பாதீர்கள். OTP, PIN யாரிடமும் சொல்லாதீர்கள். நம்பிக்கையான ஒருவரிடம் கேளுங்கள்.",
    "careful": "இதில் ஏதோ சரியில்லை. பதில் சொல்லும் முன்போ பணம் அனுப்பும் முன்போ நம்பிக்கையான ஒருவரிடம் கேளுங்கள்.",
    "none": "ஆபத்து அறிகுறி எதுவும் தெரியவில்லை. இன்னும் சந்தேகம் இருந்தால் நம்பிக்கையான ஒருவரிடம் கேளுங்கள்.",
}
ACTIONS = {
    "high": ["alert_caretaker", "report_chakshu", "call_1930", "listen"],
    "careful": ["alert_caretaker", "listen"],
    "none": [],
}
SEV_RANK = {"none": 0, "careful": 1, "high": 2}

SENDER_TEXT = {
    "text.asks-secret": "This asks for your CVV or PIN. Only enter these on a website you are sure about.",
    "sender.promo-header": "This came from a promotional sender but talks about your account. Banks use a different kind of sender for account messages.",
    "sender.near-miss-header": "The sender name is almost, but not exactly, a bank's sender name.",
    "sender.bank-link-mismatch": "This message uses a bank's sender name, but its link does not go to that bank's website.",
    "sender.mobile-bank": "A message about a bank or account came from an ordinary mobile number. Banks send these from a named sender.",
}
LINK_TEXT = {
    "bare_ip": "The link goes to a number address instead of a website name.",
    "user_info": "The link hides its destination: the site that opens is the one after the @ sign.",
    "apk": "The link downloads an app file (.apk) from outside the Play Store.",
    "executable_download": "The link downloads a program or script file. Scam links use these to harm your phone or computer.",
    "archive_download": "The link downloads a compressed file (such as .zip). Scam links often hide harmful files inside.",
    "punycode": "The website name uses unusual characters.",
    "non_ascii": "The website name uses unusual characters.",
    "mixed_script": "The website name mixes alphabets to look like another site.",
    "confusable": "The website name is spelled to look like an official bank or government site, but it is a different site.",
    "lookalike_official": "The website name is very close to an official bank site, but it is not the same.",
    "lookalike": "The website name is very close to a popular site's name, but it is not the same.",
    "shortener": "The link is shortened, so where it leads is hidden.",
    "risky_tld": "This website ending is often used by scam sites.",
    "temporary_host": "This website is on a temporary address that anyone can make in minutes. Banks and government offices do not use these.",
    "many_hyphens": "The website name has many hyphens, common in fake sites.",
    "brand_in_domain": "The website name uses the name of a bank, government office or well-known company, but it is not their official site.",
    "very_new_domain": "This website was created in the last two weeks. Scam sites are often brand new.",
    "new_domain": "This website was created in the last three months.",
    "page_brand_mismatch": "The page says it belongs to a bank, government office or well-known company, but the website address is not theirs.",
    "page_download": "Opening this link downloads a file to your phone.",
    "asks_secret_details": "This page asks for passwords, codes or card details.",
    "form_posts_elsewhere": "What you type on this page is sent to a different website.",
}
EMAIL_TEXT = {
    "brand_mismatch_free_mail": "The sender uses the name of a bank, government office or well-known company, but this address is from a free email service.",
    "brand_mismatch": "The sender uses the name of a bank, government office or well-known company, but this address doesn't match it.",
}
EMAIL_DEFAULT = "This sender address doesn't match the organisation it appears to be from."
LINK_DEFAULT = "This link has a warning sign."
PAGE_UNREACHABLE = ("பக்கத்தைப் பார்க்க முடியவில்லை. அதனால் இது சரியான லிங்க் என்று அர்த்தம் இல்லை.",
                    "We couldn't look at the page. That does not mean the link is fine.")

SENDER_TEXT_TA = {
    "text.asks-secret": "இது உங்கள் CVV அல்லது PIN கேட்கிறது. நீங்கள் உறுதியாக அறிந்த வெப்சைட்டில் மட்டும் இவற்றை உள்ளிடுங்கள்.",
    "sender.promo-header": "இது விளம்பர மெசேஜ் அனுப்பும் பெயரில் இருந்து வந்துள்ளது, ஆனால் உங்கள் கணக்கைப் பற்றிப் பேசுகிறது. வங்கிகள் கணக்கு மெசேஜ்களை வேறு பெயரில் அனுப்பும்.",
    "sender.near-miss-header": "அனுப்பியவர் பெயர் வங்கியின் பெயர் போலவே இருக்கிறது, ஆனால் அதே பெயர் இல்லை.",
    "sender.bank-link-mismatch": "இந்த மெசேஜ் வங்கியின் பெயரில் வந்துள்ளது, ஆனால் இதில் உள்ள லிங்க் அந்த வங்கியின் வெப்சைட்டுக்குப் போகவில்லை.",
    "sender.mobile-bank": "வங்கி அல்லது கணக்கு பற்றிய மெசேஜ் சாதாரண மொபைல் எண்ணில் இருந்து வந்துள்ளது. வங்கிகள் இதைத் தங்கள் பெயரில்தான் அனுப்பும்.",
}
LINK_TEXT_TA = {
    "bare_ip": "இந்த லிங்க் வெப்சைட் பெயருக்குப் பதிலாக எண்களால் ஆன முகவரிக்குப் போகிறது.",
    "user_info": "இந்த லிங்க் எங்கே போகிறது என்பதை மறைக்கிறது. @ குறிக்குப் பின் உள்ள வெப்சைட்தான் திறக்கும்.",
    "apk": "இந்த லிங்க் Play Store-க்கு வெளியே இருந்து ஒரு ஆப் கோப்பை (.apk) டவுன்லோடு செய்யும்.",
    "executable_download": "இந்த லிங்க் ஒரு புரோகிராம் கோப்பை டவுன்லோடு செய்யும். மோசடி லிங்குகள் இதன் மூலம் உங்கள் போனையோ கம்ப்யூட்டரையோ கெடுக்கும்.",
    "archive_download": "இந்த லிங்க் ஒரு சுருக்கப்பட்ட கோப்பை (.zip போன்றவை) டவுன்லோடு செய்யும். மோசடி லிங்குகள் இதற்குள் ஆபத்தான கோப்புகளை மறைக்கும்.",
    "punycode": "இந்த வெப்சைட் பெயரில் வழக்கத்துக்கு மாறான எழுத்துகள் உள்ளன.",
    "non_ascii": "இந்த வெப்சைட் பெயரில் வழக்கத்துக்கு மாறான எழுத்துகள் உள்ளன.",
    "mixed_script": "இந்த வெப்சைட் பெயர் வேறு வெப்சைட் போலத் தெரிய வெவ்வேறு மொழி எழுத்துகளைக் கலந்துள்ளது.",
    "confusable": "இந்த வெப்சைட் பெயர் வங்கி அல்லது அரசு வெப்சைட் போல எழுதப்பட்டுள்ளது, ஆனால் இது வேறு வெப்சைட்.",
    "lookalike_official": "இந்த வெப்சைட் பெயர் வங்கியின் அதிகாரப்பூர்வ வெப்சைட் போலவே இருக்கிறது, ஆனால் அது இல்லை.",
    "lookalike": "இந்த வெப்சைட் பெயர் பிரபலமான வெப்சைட் பெயர் போலவே இருக்கிறது, ஆனால் அது இல்லை.",
    "shortener": "இந்த லிங்க் சுருக்கப்பட்டுள்ளது. அதனால் எங்கே போகிறது என்று தெரியாது.",
    "risky_tld": "இந்த வகை வெப்சைட் முகவரிகளை மோசடி வெப்சைட்கள் அதிகம் பயன்படுத்துகின்றன.",
    "temporary_host": "இந்த வெப்சைட் யார் வேண்டுமானாலும் சில நிமிடங்களில் உருவாக்கக்கூடிய தற்காலிக முகவரியில் உள்ளது. வங்கிகளும் அரசு அலுவலகங்களும் இவற்றைப் பயன்படுத்துவதில்லை.",
    "many_hyphens": "இந்த வெப்சைட் பெயரில் பல கோடுகள் (-) உள்ளன. போலி வெப்சைட்களில் இது அதிகம்.",
    "brand_in_domain": "இந்த வெப்சைட் பெயரில் வங்கி, அரசு அலுவலகம் அல்லது பெரிய நிறுவனத்தின் பெயர் உள்ளது, ஆனால் இது அவர்களின் அதிகாரப்பூர்வ வெப்சைட் இல்லை.",
    "very_new_domain": "இந்த வெப்சைட் கடந்த இரண்டு வாரங்களுக்குள் உருவாக்கப்பட்டது. மோசடி வெப்சைட்கள் பெரும்பாலும் புதியவை.",
    "new_domain": "இந்த வெப்சைட் கடந்த மூன்று மாதங்களுக்குள் உருவாக்கப்பட்டது.",
    "page_brand_mismatch": "இந்தப் பக்கம் வங்கி, அரசு அலுவலகம் அல்லது பெரிய நிறுவனத்தினுடையது என்று சொல்கிறது, ஆனால் இந்த வெப்சைட் முகவரி அவர்களுடையது இல்லை.",
    "page_download": "இந்த லிங்கைத் திறந்தால் உங்கள் போனில் ஒரு கோப்பு டவுன்லோடு ஆகும்.",
    "asks_secret_details": "இந்தப் பக்கம் பாஸ்வேர்டு, கோடு அல்லது கார்டு விவரங்களைக் கேட்கிறது.",
    "form_posts_elsewhere": "இந்தப் பக்கத்தில் நீங்கள் டைப் செய்வது வேறு வெப்சைட்டுக்குப் போகும்.",
}
EMAIL_TEXT_TA = {
    "brand_mismatch_free_mail": "அனுப்பியவர் வங்கி, அரசு அலுவலகம் அல்லது பெரிய நிறுவனத்தின் பெயரைப் பயன்படுத்துகிறார், ஆனால் இந்த முகவரி இலவச ஈமெயில் சேவையில் இருந்து வந்துள்ளது.",
    "brand_mismatch": "அனுப்பியவர் வங்கி, அரசு அலுவலகம் அல்லது பெரிய நிறுவனத்தின் பெயரைப் பயன்படுத்துகிறார், ஆனால் இந்த முகவரி அவர்களுடையதோடு பொருந்தவில்லை.",
}
EMAIL_DEFAULT_TA = "இந்த அனுப்புநர் முகவரி, அது சொல்லும் நிறுவனத்தோடு பொருந்தவில்லை."
LINK_DEFAULT_TA = "இந்த லிங்கில் ஒரு எச்சரிக்கை அறிகுறி உள்ளது."


def reason_text_en(reason_id: str, pattern: dict | None = None) -> str:
    kind, _, rest = reason_id.partition(".")
    if kind == "pattern" and pattern:
        return pattern["explanation_en"]
    if kind == "pattern-weak" and pattern:
        return f"Part of this message looks like a known scam ({pattern['name_en']}). Check carefully."
    if reason_id in SENDER_TEXT:
        return SENDER_TEXT[reason_id]
    if kind == "link":
        return LINK_TEXT.get(rest, LINK_DEFAULT)
    if kind == "email":
        return EMAIL_TEXT.get(rest, EMAIL_DEFAULT)
    return reason_id


def reason_text_ta(reason_id: str, pattern: dict | None = None) -> str:
    kind, _, rest = reason_id.partition(".")
    if kind == "pattern" and pattern:
        return pattern["explanation_ta"]
    if kind == "pattern-weak" and pattern:
        return f"இந்த மெசேஜின் ஒரு பகுதி தெரிந்த மோசடி ({pattern['name_ta']}) போல் உள்ளது. கவனமாகப் பாருங்கள்."
    if reason_id in SENDER_TEXT_TA:
        return SENDER_TEXT_TA[reason_id]
    if kind == "link":
        return LINK_TEXT_TA.get(rest, LINK_DEFAULT_TA)
    if kind == "email":
        return EMAIL_TEXT_TA.get(rest, EMAIL_DEFAULT_TA)
    return "இதில் ஒரு எச்சரிக்கை அறிகுறி உள்ளது."


def audio_url_for(pattern_id: str | None) -> str | None:
    """Relative URL, only when the file exists (generated in S6). A URL that 404s would break Listen."""
    if pattern_id and (AUDIO_DIR / f"{pattern_id}.mp3").is_file():
        return f"/audio/{pattern_id}.mp3"
    return None  # TODO(hackathon): S6 generates /audio/{pattern_id}.mp3 for every pattern


def build(result: dict, latency_ms: int) -> dict:
    """rules.analyze() result -> AnalyzeResponse fields (as a dict)."""
    sev = result["severity"]
    by_id = {p["id"]: p for p in rules.patterns()}
    pat = by_id.get(result["pattern_id"])
    ranked = sorted(result["reasons"], key=lambda r: -SEV_RANK[r["level"]])  # stable: keeps insertion order within a level
    ranked = list({r["id"]: r for r in ranked}.values())    # two links with the same problem are one reason, not two rows
    reasons = []
    for r in ranked[:3]:
        pid = r["id"].partition(".")[2] if r["id"].startswith("pattern") else None
        reasons.append(Reason(id=r["id"], level=r["level"], text_ta=reason_text_ta(r["id"], by_id.get(pid)),
                              text_en=reason_text_en(r["id"], by_id.get(pid))))
    title_ta, title_en = TITLES[sev]
    # The pattern's own Tamil title names the scam, so it only fits a high verdict; a lower one keeps the plain title.
    return dict(severity=sev, pattern_id=result["pattern_id"], title_ta=pat["title_ta"] if pat and sev == "high" else title_ta,
                title_en=title_en, reasons=reasons,
                explanation_ta=pat["explanation_ta"] if pat else EXPLANATION_TA[sev],
                explanation_en=pat["explanation_en"] if pat else EXPLANATION_EN[sev],
                audio_url=audio_url_for(result["pattern_id"]), actions=ACTIONS[sev],
                source_of_verdict="rules", latency_ms=latency_ms)
