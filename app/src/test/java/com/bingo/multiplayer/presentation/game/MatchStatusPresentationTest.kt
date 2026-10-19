package com.bingo.multiplayer.presentation.game

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchStatusPresentationTest {

    @Test
    fun testMinimizedAppNoticeAndTone() {
        val notice = MatchStatusPresentation.notice("Bob minimized the app")
        assertEquals("Bob minimized the app", notice.text)
        assertEquals(MatchNoticeTone.WARNING, notice.tone)
    }

    @Test
    fun testIsBackNoticeAndTone() {
        val notice = MatchStatusPresentation.notice("Bob is back")
        assertEquals("Bob is back", notice.text)
        assertEquals(MatchNoticeTone.SUCCESS, notice.tone)
    }

    @Test
    fun testDisconnectedNoticeAndTone() {
        val notice = MatchStatusPresentation.notice("Bob disconnected")
        assertEquals("Bob disconnected", notice.text)
        assertEquals(MatchNoticeTone.DISCONNECTED, notice.tone)
    }

    @Test
    fun testNoDoubleDotsOrEmojisBeforeName() {
        val doubleYellow = MatchStatusPresentation.notice("🟡 🟡 Bob minimized the app")
        assertEquals("Bob minimized the app", doubleYellow.text)
        assertEquals(MatchNoticeTone.WARNING, doubleYellow.tone)

        val singleYellow = MatchStatusPresentation.notice("🟡 Bob minimized the game")
        assertEquals("Bob minimized the app", singleYellow.text)
        assertEquals(MatchNoticeTone.WARNING, singleYellow.tone)

        val doubleGreen = MatchStatusPresentation.notice("🟢 🟢 Bob is back")
        assertEquals("Bob is back", doubleGreen.text)
        assertEquals(MatchNoticeTone.SUCCESS, doubleGreen.tone)

        val greenReturned = MatchStatusPresentation.notice("🟢 Bob returned")
        assertEquals("Bob is back", greenReturned.text)
        assertEquals(MatchNoticeTone.SUCCESS, greenReturned.tone)

        val doubleRed = MatchStatusPresentation.notice("🔴 🔴 Bob disconnected")
        assertEquals("Bob disconnected", doubleRed.text)
        assertEquals(MatchNoticeTone.DISCONNECTED, doubleRed.tone)

        val singleRed = MatchStatusPresentation.notice("🔴 Bob disconnected")
        assertEquals("Bob disconnected", singleRed.text)
        assertEquals(MatchNoticeTone.DISCONNECTED, singleRed.tone)

        val hostLobbyDisconnect = MatchStatusPresentation.notice("👑 Bob (Host) disconnected (in lobby)")
        assertEquals("Bob disconnected", hostLobbyDisconnect.text)
        assertEquals(MatchNoticeTone.DISCONNECTED, hostLobbyDisconnect.tone)

        val reconnectingDisconnect = MatchStatusPresentation.notice("Bob disconnected (reconnecting...)")
        assertEquals("Bob disconnected", reconnectingDisconnect.text)
        assertEquals(MatchNoticeTone.DISCONNECTED, reconnectingDisconnect.tone)
    }
}
