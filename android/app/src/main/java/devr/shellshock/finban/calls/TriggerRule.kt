package devr.shellshock.finban.calls

import devr.shellshock.finban.BuildConfig
import devr.shellshock.finban.a11y.FinbanAccessibilityService
import devr.shellshock.finban.a11y.ForegroundTracker
import devr.shellshock.finban.data.Session
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType
import devr.shellshock.finban.net.AlertState
import devr.shellshock.finban.net.CaretakerAlert
import devr.shellshock.finban.ui.sheet.PauseSheetContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A payment app comes to the front while a call from someone not in contacts is on (or ended a few minutes ago):
 * show a pause sheet with three Yes/No questions. It only asks. It never blocks, covers or touches the payment app;
 * the sheet can be closed at any time. A Yes tells the trusted person (trigger pause_yes).
 */
class TriggerRule(private val svc: FinbanAccessibilityService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastShownMs = 0L
    private var alertState by mutableStateOf<AlertState>(AlertState.Idle)
    private val listener: (String, String, Boolean) -> Unit = { pkg, _, _ -> onForeground(pkg) }

    fun start() { ForegroundTracker.listeners.add(listener) }
    fun stop() { ForegroundTracker.listeners.remove(listener); scope.cancel() }

    private fun onForeground(pkg: String) {
        if (!isPaymentApp(pkg)) return
        val now = System.currentTimeMillis()
        if (!shouldPause(now, lastShownMs)) return
        if (svc.sheet.isShown || svc.screenCheck.busy) return
        lastShownMs = now
        alertState = AlertState.Idle
        var told = false
        EventLog.log(EventType.SERVICE, "pause-sheet", "shown over $pkg (${CallStateTracker.describe()})")
        svc.sheet.show { dismiss ->
            PauseSheetContent(alert = alertState, onClose = dismiss, onYes = {
                if (!told) {
                    told = true
                    alertState = AlertState.Sending
                    scope.launch { alertState = CaretakerAlert.send("high", null, "pause_yes") }
                }
            })
        }
    }

    companion object {
        /**
         * UPI / wallet apps. Package names from Juspay's and Razorpay's UPI app lists (checked 2026-11-05); not every one tested on a device.
         * WhatsApp (also a UPI app) is left out on purpose: a WhatsApp chat is not a payment.
         */
        val UPI_APPS = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                         // PhonePe
            "net.one97.paytm",                         // Paytm
            "in.org.npci.upiapp",                      // BHIM
            "com.dreamplug.androidapp",                // CRED
            "com.mobikwik_new",                        // MobiKwik
            "com.freecharge.android",                  // Freecharge
            "com.myairtelapp",                         // Airtel Thanks
            "com.samsung.android.spay",                // Samsung Pay
            "com.hdfcbank.payzapp",                    // PayZapp
            "com.icicibank.pockets",                   // ICICI Pockets
            "com.upi.axispay",                         // Axis Pay
            "com.sbi.upi",                             // SBI Pay
            "com.bankofbaroda.upi",                    // Bank of Baroda UPI
            "com.fampay.in", "com.naviapp", "money.super.payments", "com.tatadigital.tcp", // FamPay, Navi, super.money, Tata Neu
            "money.jupiter", "indwin.c3.shareapp", "com.epifi.paisa",                      // Jupiter, Slice, Fi
        )

        /** Mobile banking apps (they move money too). */
        val BANK_APPS = setOf(
            "com.sbi.lotusintouch",                    // YONO SBI
            "com.snapwork.hdfc", "com.snapwork.hdfcbank", // HDFC Bank
            "com.csam.icici.bank.imobile",             // ICICI iMobile
            "com.axis.mobile",                         // Axis Mobile
            "com.msf.kbank.mobile",                    // Kotak
            "com.Version1",                            // PNB One
            "com.canarabank.mobility",                 // Canara ai1
            "com.IndianBank.IndOASIS",                 // Indian Bank
            "com.infrasoft.uboi",                      // Union Bank Vyom
            "com.infrasofttech.CentralBank",           // Central Bank
            "com.euronet.iobupi",                      // BHIM IOB
            "com.idfcfirstbank.optimus",               // IDFC FIRST
            "com.YesBank", "com.indusind.indie", "com.fedmobile", "com.rblbank.mobank", // Yes, IndusInd, Federal, RBL
            "com.mycompany.kvb", "com.SIBMobile",      // Karur Vysya, South Indian Bank
            "com.dbs.in.digitalbank",                  // DBS digibank
            "com.iexceed.appzillon.ippbMB",            // India Post Payments Bank
        )

        val PAYMENT_APPS: Set<String> = UPI_APPS + BANK_APPS

        fun isPaymentApp(pkg: String) = pkg in PAYMENT_APPS || (BuildConfig.DEBUG && pkg in Session.debugPaymentApps)

        /** Call on or recent, caller not a saved contact (or unknown, e.g. WhatsApp), and not within the cooldown of the last sheet. */
        fun shouldPause(now: Long, lastShownMs: Long): Boolean {
            val window = Session.callWindowMin * 60_000L
            if (!CallStateTracker.callActiveOrRecent(now, window)) return false
            if (lastShownMs > 0 && now - lastShownMs < Session.pauseCooldownMin * 60_000L) return false
            return !callerIsSavedContact(now, window)
        }

        /** A "known" answer from the screening service only counts for the call it belongs to: while off hook, or if it was seen within the window. */
        fun callerIsSavedContact(now: Long, windowMs: Long): Boolean =
            CallStateTracker.lastNumberKnown == true && (CallStateTracker.offHook || now - CallStateTracker.lastScreenedAtMs <= windowMs)
    }
}
