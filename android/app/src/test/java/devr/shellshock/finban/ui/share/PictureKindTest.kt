package devr.shellshock.finban.ui.share

import devr.shellshock.finban.ui.share.PictureKind.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureKindTest {
    private fun kind(t: String?) = PictureKind.detect(t)

    @Test fun aadhaar() {
        assertEquals(AADHAAR, kind("Government of India\nRavi Kumar\nDOB: 01/01/1950\nMale\n1234 5678 9012\nAadhaar"))
        assertEquals(AADHAAR, kind("Ravi Kumar\nYear of Birth 1950\nFemale\n1234 5678 9012"))
        assertEquals(AADHAAR, kind("Aadhar card\nXXXX XXXX 9012"))
        assertEquals(AADHAAR, kind("ஆதார்\nபிறந்த தேதி 01/01/1950"))
    }

    @Test fun pan() {
        assertEquals(PAN, kind("INCOME TAX DEPARTMENT\nPermanent Account Number\nABCDE1234F"))
        assertEquals(PAN, kind("Name RAVI KUMAR\nDate of Birth 01/01/1950\nABCDE1234F"))
    }

    @Test fun voter() {
        assertEquals(VOTER, kind("Election Commission of India\nElector's Name: Ravi\nABC1234567"))
        assertEquals(VOTER, kind("Name Ravi\nDate of Birth 01/01/1950\nABC1234567"))
    }

    @Test fun anyOtherId() = assertEquals(ID_CARD, kind("Name: Ravi Kumar\nDOB: 01/01/1950"))

    @Test fun payment() {
        assertEquals(PAYMENT, kind("₹5,000\nPayment successful"))
        assertEquals(PAYMENT, kind("Paid to Ravi\nRs 500\nUPI transaction ID 412345678901"))
        assertEquals(PAYMENT, kind("Transaction completed\nUTR 412345678901"))
    }

    @Test fun aMoneyAskIsNotAPayment() {
        assertEquals(null, kind("Send Rs 500 to claim your prize"))
        assertEquals(null, kind("Pay Rs 999 today"))
    }

    @Test fun aReferenceNumberIsNotAnAadhaar() = assertEquals(PAYMENT, kind("Paid ₹100\nUPI Ref 412345678901"))

    @Test fun ordinaryTextIsNothing() {
        assertEquals(null, kind(null))
        assertEquals(null, kind(""))
        assertEquals(null, kind("Happy birthday! See you at 8 tonight"))
        assertEquals(null, kind("Vote for the best name for our new puppy"))
    }
}
