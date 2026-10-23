package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.network.GameInvite
import com.bingo.multiplayer.domain.network.RoomJoinResult
import com.bingo.multiplayer.domain.network.toEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** One admission at a time; notification handling follows the room's admission result. */
internal class InviteJoinAttempt(private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()

    fun canRetry(invite: GameInvite, account: NotificationAccount?, ledger: NotificationLedger): Boolean =
        account != null && NotificationPolicy.eligible(invite.toEvent(), account, now()) &&
            !ledger.handled(account.uid, invite.eventId)

    suspend fun join(
        invite: GameInvite?,
        account: NotificationAccount?,
        ledger: NotificationLedger,
        isCurrentAccount: () -> Boolean,
        finishInvite: (GameInvite) -> Boolean,
        joinRoom: suspend () -> RoomJoinResult
    ): RoomJoinResult? {
        if (!mutex.tryLock()) return null
        try {
            if (account == null || !isCurrentAccount()) return null
            fun available() = invite == null || canRetry(invite, account, ledger)
            fun unavailable() = RoomJoinResult.Error("This invitation has expired or is no longer available.")
            if (!available()) return unavailable()
            var result = joinRoom()
            if (result is RoomJoinResult.NotFound) {
                delay(500L)
                if (!isCurrentAccount()) return null
                if (!available()) return unavailable()
                result = joinRoom()
            }
            if (!isCurrentAccount()) return null
            // A valid attempt may finish after expiry. Admission already succeeded, so do
            // not undo it; failed attempts never consume or extend the invitation.
            if (result is RoomJoinResult.Success && invite != null) finishInvite(invite)
            return result
        } finally {
            mutex.unlock()
        }
    }
}
