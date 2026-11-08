"""Pydantic models. Must match docs/API.md exactly."""
from typing import Literal, Optional
from pydantic import BaseModel, Field

Severity = Literal["high", "careful", "none"]
Source = Literal["screen", "share", "sms", "call_context"]
CallType = Literal["cellular", "voip"]
VerdictSource = Literal["rules", "rules+llm", "cache"]
ActionId = Literal["alert_caretaker", "report_chakshu", "call_1930", "listen"]


class AnalyzeContext(BaseModel):
    in_call: Optional[bool] = None
    caller_unknown: Optional[bool] = None
    call_type: Optional[CallType] = None


class AnalyzeRequest(BaseModel):
    source: Source
    app_package: Optional[str] = None
    sender: Optional[str] = None
    text: str
    links: list[str] = []
    image_id: Optional[str] = None
    context: Optional[AnalyzeContext] = None
    locale: str = "ta-IN"


class Reason(BaseModel):
    id: str
    level: Severity
    text_ta: str
    text_en: str


class AnalyzeResponse(BaseModel):
    severity: Severity
    pattern_id: Optional[str] = None
    title_ta: str
    title_en: str
    reasons: list[Reason] = Field(default_factory=list, max_length=3)
    explanation_ta: str
    explanation_en: Optional[str] = None
    audio_url: Optional[str] = None
    actions: list[ActionId] = []
    source_of_verdict: VerdictSource = "rules"
    latency_ms: int


class LinkVerdict(BaseModel):
    url: str
    registrable_domain: str
    flags: list[str] = []
    age_days: Optional[int] = None
    suspected_malicious: Optional[bool] = None


class LinkCheckRequest(BaseModel):
    url: str = Field(max_length=2048)


class LinkChecked(BaseModel):
    registrable_domain: str
    final_domain: str
    redirected: bool = False
    age_days: Optional[int] = None
    page_reached: bool = False


class LinkCheckResponse(BaseModel):
    severity: Severity
    reasons: list[Reason] = Field(default_factory=list, max_length=3)
    checked: LinkChecked
    latency_ms: int


class PairCode(BaseModel):
    code: str
    expires_at: str


class PairClaimRequest(BaseModel):
    code: str


class PairClaimResponse(BaseModel):
    link_id: str
    elder_name: str


class AlertRequest(BaseModel):
    alert_id: str


class AlertResult(BaseModel):
    sent: int
    pruned: int


class ImageAnalyzeResponse(AnalyzeResponse):
    """/image/analyze: the normal verdict (from the text read in the image) plus what the picture shows."""
    description: Optional[str] = None            # one or two plain sentences, no judgement
    text_found: bool = False                     # did the image contain readable text
    text: Optional[str] = None                   # the text read from the image (sent back to the phone that sent the image, for the quote card)
    vision_available: bool = False               # False = no vision model answered; the app falls back to on-device OCR
    ai_image: Literal["likely_ai", "unlikely_ai", "unknown"] = "unknown"


class ImageCheckResponse(BaseModel):
    verdict: Literal["likely_ai", "unlikely_ai", "unknown"]
