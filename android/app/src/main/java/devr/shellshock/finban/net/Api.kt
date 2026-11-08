package devr.shellshock.finban.net

import devr.shellshock.finban.data.Session
import devr.shellshock.finban.log.EventLog
import devr.shellshock.finban.log.EventType

/** One shared client. The token provider signs in anonymously on demand. */
object Api {
    val client: ApiClient by lazy {
        ApiClient(token = { force ->
            val t0 = System.currentTimeMillis()
            try { Session.idToken(force) } finally { EventLog.log(EventType.SERVICE, "token", "force=$force took ${System.currentTimeMillis() - t0} ms") }
        })
    }
}
