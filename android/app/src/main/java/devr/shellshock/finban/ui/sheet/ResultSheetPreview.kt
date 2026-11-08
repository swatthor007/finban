package devr.shellshock.finban.ui.sheet

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import devr.shellshock.finban.net.AnalyzeResponse
import devr.shellshock.finban.net.Reason
import devr.shellshock.finban.ui.theme.FinbanTheme

private const val quote = "Dear customer your SBI account is blocked. Update KYC now http://sbi-kyc-update.top/login"

private val sample = AnalyzeResponse(
    severity = "high", patternId = "kyc-sms", titleTa = "வங்கி எச்சரிக்கை", titleEn = "This looks like a scam",
    reasons = listOf(
        Reason("sender.mobile-bank", "high", "TODO(hackathon): Tamil", "Sent from a mobile number, not a bank"),
        Reason("link.lookalike_official", "high", "TODO(hackathon): Tamil", "The link pretends to be SBI"),
        Reason("pattern.pressure", "careful", "TODO(hackathon): Tamil", "“Today” pushes you to act before you think"),
    ),
    explanationTa = "TODO(hackathon): Tamil", explanationEn = "Banks do not ask you to update KYC through a link in a text.",
    actions = emptyList(), sourceOfVerdict = "rules+llm", latencyMs = 900,
)

@Preview(showBackground = true) @Composable private fun High() = FinbanTheme(false) { ResultSheetContent(SheetState.Result(sample), onClose = {}, shared = quote) }
@Preview(showBackground = true) @Composable private fun Careful() = FinbanTheme(false) { ResultSheetContent(SheetState.Result(sample.copy(severity = "careful", sourceOfVerdict = "rules")), onClose = {}, shared = quote) }
@Preview(showBackground = true) @Composable private fun None() = FinbanTheme(false) { ResultSheetContent(SheetState.Result(sample.copy(severity = "none", titleEn = "We didn’t find warning signs", reasons = emptyList(), explanationEn = null, sourceOfVerdict = "rules")), onClose = {}, shared = quote) }
@Preview(showBackground = true) @Composable private fun Failed() = FinbanTheme(false) { ResultSheetContent(SheetState.Failed, onClose = {}, shared = quote) }
@Preview(showBackground = true) @Composable private fun Loading() = FinbanTheme(false) { ResultSheetContent(SheetState.Loading, onClose = {}, shared = quote) }
@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C) @Composable private fun HighDark() = FinbanTheme(true) { ResultSheetContent(SheetState.Result(sample), onClose = {}, shared = quote) }

private val checked = devr.shellshock.finban.net.LinkCheckResponse(
    "high", listOf(Reason("link.page_brand_mismatch", "high", "TODO(hackathon): Tamil", "The page says it is a bank or government site, but the website address is not theirs.")),
    devr.shellshock.finban.net.LinkChecked("evil.top", "evil.top", false, 3, true), 1800,
)
private val carefulSample get() = sample.copy(severity = "careful", sourceOfVerdict = "rules")

@Preview(showBackground = true) @Composable private fun LinkIdle() = FinbanTheme(false) { ResultSheetContent(SheetState.Result(carefulSample), onClose = {}, shared = quote, linkCheck = LinkCheckUi("sbi-kyc-update.top", LinkCheckState.Idle) {}) }
@Preview(showBackground = true) @Composable private fun LinkRunning() = FinbanTheme(false) { ResultSheetContent(SheetState.Result(carefulSample), onClose = {}, shared = quote, linkCheck = LinkCheckUi("sbi-kyc-update.top", LinkCheckState.Running) {}) }
@Preview(showBackground = true) @Composable private fun LinkDone() = FinbanTheme(false) { ResultSheetContent(SheetState.Result(carefulSample), onClose = {}, shared = quote, linkCheck = LinkCheckUi("sbi-kyc-update.top", LinkCheckState.Done(checked)) {}) }
@Preview(showBackground = true) @Composable private fun LinkFailed() = FinbanTheme(false) { ResultSheetContent(SheetState.Result(carefulSample), onClose = {}, shared = quote, linkCheck = LinkCheckUi("sbi-kyc-update.top", LinkCheckState.Failed) {}) }
