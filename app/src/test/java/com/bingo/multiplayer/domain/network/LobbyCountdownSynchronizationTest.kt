package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LobbyCountdownSynchronizationTest {
    @Test fun delayedMetadataMustNotGiveHostAnExtraCountdown() = runTest {
        val epoch = 1_800_000_000_000L
        val packet = RoomMessagePacket("START_GAME", startCommitDeadline = epoch + 3300L)
        delay(1200L) // Existing metadata persistence finishes before packet publication.
        val host = async {
            assertTrue(LobbyStartSchedule.awaitHostCountdown(packet, "host", { true }, {},
                { epoch + testScheduler.currentTime }, { testScheduler.currentTime * 1_000_000L }))
            testScheduler.currentTime
        }
        val guest = async {
            delay(120L)
            assertTrue(LobbyStartSchedule.awaitGuestCountdown(packet, "guest", { true }, {},
                { epoch + testScheduler.currentTime }, { testScheduler.currentTime * 1_000_000L }))
            testScheduler.currentTime
        }
        assertTrue("Host and guest must enter at the same deadline", kotlin.math.abs(host.await() - guest.await()) <= 40L)
    }
    @Test fun guestDisplayUsesSameCeilingAndDeadlineAsHost() = runTest {
        val epoch = 1_800_000_000_000L
        val packet = RoomMessagePacket("START_GAME", startCommitDeadline = epoch + 3300L)
        val hostTicks = mutableListOf<Int>(); val guestTicks = mutableListOf<Int>()
        val host = async { LobbyStartSchedule.awaitHostCountdown(packet, "host", { true }, { if (hostTicks.lastOrNull() != it) hostTicks.add(it) },
            { epoch + testScheduler.currentTime }, { testScheduler.currentTime * 1_000_000L }) }
        val guest = async {
            delay(100L)
            LobbyStartSchedule.awaitGuestCountdown(packet, "guest", { true }, { if (guestTicks.lastOrNull() != it) guestTicks.add(it) },
                { epoch + testScheduler.currentTime }, { testScheduler.currentTime * 1_000_000L })
        }
        assertTrue(host.await());assertTrue(guest.await())
        assertEquals(listOf(3,2,1),hostTicks);assertEquals(hostTicks,guestTicks)
    }
    @Test fun calibratedPhoneClockDoesNotChangeEntryInstant() = runTest {
        val epoch = 1_800_000_000_000L
        val packet = RoomMessagePacket("START_GAME", startCommitDeadline = epoch + 3300L,
            startClockOffsets = mapOf("host" to 0L,"guest" to 60000L))
        val host = async {
            assertTrue(LobbyStartSchedule.awaitHostCountdown(packet,"host",{true},{},
                {epoch+testScheduler.currentTime},{testScheduler.currentTime*1_000_000L}));testScheduler.currentTime
        }
        val guest = async {
            delay(100L)
            assertTrue(LobbyStartSchedule.awaitGuestCountdown(packet,"guest",{true},{},
                {epoch+60000L+testScheduler.currentTime},{testScheduler.currentTime*1_000_000L}));testScheduler.currentTime
        }
        assertTrue(kotlin.math.abs(host.await()-guest.await())<=40L)
    }
    @Test fun preparationRoundTripCalibratesTheActualGuestClock() {
        val clock=LobbyClockCalibration();clock.begin(123L,"host",setOf("host","guest"))
        val sent=clock.prepare(RoomMessagePacket("PREPARE_START",seed=123L),now=100000L,monotonic=0L)
        clock.acknowledge(RoomMessagePacket("START_GAME_ACK",seed=123L,playerId="guest",pingTimestamp=sent.timestamp,
            timestamp=160040L),nowMonotonic=80_000_000L)
        assertEquals(mapOf("host" to 0L,"guest" to 60000L),clock.offsets())
        clock.acknowledge(RoomMessagePacket("START_GAME_ACK",seed=122L,playerId="guest",pingTimestamp=sent.timestamp,
            timestamp=999999L),nowMonotonic=100_000_000L)
        assertEquals(60000L,clock.offsets()["guest"])
    }
    @Test fun lowerLatencyClockSampleReplacesAnOlderNoisierOne() {
        val clock=LobbyClockCalibration();clock.begin(5L,"host",setOf("host","guest"))
        clock.prepare(RoomMessagePacket("PREPARE_START",seed=5L),100000L,0L)
        clock.acknowledge(RoomMessagePacket("START_GAME_ACK",seed=5L,playerId="guest",pingTimestamp=100000L,timestamp=160090L),200_000_000L)
        clock.prepare(RoomMessagePacket("PREPARE_START",seed=5L),101000L,1_000_000_000L)
        clock.acknowledge(RoomMessagePacket("START_GAME_ACK",seed=5L,playerId="guest",pingTimestamp=101000L,timestamp=161040L),1_080_000_000L)
        assertEquals(60000L,clock.offsets()["guest"])
    }
    @Test fun latePersistenceDoesNotRestartAThreeSecondHostTimer() = runTest {
        val packet=RoomMessagePacket("START_GAME",startCommitDeadline=1000L)
        assertTrue(LobbyStartSchedule.awaitHostCountdown(packet,"host",{true},{},{2000L},{0L}))
        assertEquals(0L,testScheduler.currentTime)
    }
    @Test fun changingPhoneWallClockDuringCountdownDoesNotMoveItsDeadline() = runTest {
        val epoch=1_800_000_000_000L
        val packet=RoomMessagePacket("START_GAME",startCommitDeadline=epoch+3300L)
        val ticks=mutableListOf<Int>()
        assertTrue(LobbyStartSchedule.awaitGuestCountdown(packet,"guest",{true},{ticks.add(it)},
            {epoch+testScheduler.currentTime+(if(testScheduler.currentTime>500L)60000L else 0L)},
            {testScheduler.currentTime*1_000_000L}))
        assertEquals(3300L,testScheduler.currentTime);assertEquals(listOf(3,2,1),ticks)
    }

}
