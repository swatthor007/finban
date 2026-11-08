package devr.shellshock.finban.ui.share

/**
 * A rough guess at what a picture shows, from the words read off it. Runs on the phone and never changes the warning level.
 * It only sees text, so it cannot tell a genuine card from a fake one: the sheet always says it may be wrong. Numbers are never shown or stored.
 */
enum class PictureKind {
    PAYMENT, AADHAAR, PAN, VOTER, ID_CARD;

    val isId get() = this != PAYMENT

    companion object {
        private val I = RegexOption.IGNORE_CASE
        private val name = Regex("""\bname\b|नाम|பெயர்""", I)
        private val birth = Regex("""\bdob\b|d\.o\.b|date of birth|year of birth|\byob\b|जन्म|பிறந்த""", I)
        private val gender = Regex("""\b(male|female)\b|पुरुष|महिला|ஆண்|பெண்""", I)
        private val aadhaarWord = Regex("""aadh?aa?r|uidai|unique identification|आधार|ஆதார்""", I)
        // Spaced groups as printed on the card. A bare 12-digit run is usually a UPI reference. X = masked Aadhaar.
        private val aadhaarNumber = Regex("""(?<![\dXx])[\dXx]{4} [\dXx]{4} \d{4}(?!\d)""")
        private val panNumber = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
        private val panWord = Regex("""permanent account number|income tax department|father'?s name""", I)
        private val voterWord = Regex("""election commission|\belectors?\b|\bvoter\b|निर्वाचन|தேர்தல்""", I)
        private val epicNumber = Regex("""\b[A-Z]{3}\d{7}\b""")
        private val amount = Regex("""₹|\b(?:rs\.?|inr) ?\d""", I)
        private val paymentWord = Regex("""\bpayment\b|\bpaid\b|transaction|\btxn\b|\butr\b|\bupi\b|debited|credited|\breceived\b|\bsent to\b|successful|completed""", I)

        /** Most specific first: an ID card can mention money, a payment screen does not have a date of birth. */
        fun detect(text: String?): PictureKind? {
            if (text.isNullOrBlank()) return null
            val hasName = name.containsMatchIn(text)
            val hasBirth = birth.containsMatchIn(text)
            val hasPerson = hasBirth || gender.containsMatchIn(text)
            val aadhaarNo = aadhaarNumber.containsMatchIn(text)
            val pan = panNumber.containsMatchIn(text)
            val epic = epicNumber.containsMatchIn(text)
            return when {
                aadhaarNo && hasPerson -> AADHAAR
                aadhaarWord.containsMatchIn(text) && (aadhaarNo || hasPerson) -> AADHAAR
                pan && (hasPerson || hasName || panWord.containsMatchIn(text)) -> PAN
                voterWord.containsMatchIn(text) && (epic || hasName || hasPerson) -> VOTER
                epic && hasName && hasPerson -> VOTER
                hasName && hasBirth -> ID_CARD
                isPayment(text) -> PAYMENT
                else -> null
            }
        }

        private fun isPayment(text: String): Boolean {
            val words = paymentWord.findAll(text).map { it.value.lowercase() }.toSet().size
            return words >= 2 || (words == 1 && amount.containsMatchIn(text))
        }
    }
}
