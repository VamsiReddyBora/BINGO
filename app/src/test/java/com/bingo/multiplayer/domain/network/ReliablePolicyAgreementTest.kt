package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement
import org.junit.Assert.*
import org.junit.Test

class ReliablePolicyAgreementTest {
    @Test fun missingOrLegacyAdvertisementsDoNotProveVersionTwoSupport() {
        val agreement=ReliablePolicyAgreement()
        assertFalse(agreement.canStart(listOf("A","B"),"A"))
        agreement.observe("B",0);assertFalse(agreement.canStart(listOf("A","B"),"A"))
        agreement.observe("B",2);assertTrue(agreement.canStart(listOf("A","B"),"A"))
        agreement.clear();assertFalse(agreement.canStart(listOf("A","B"),"A"))
    }
    @Test fun capabilitySurvivesTheActualCompactHeartbeatWireFormat() {
        val packet=RoomMessagePacket(type="HEARTBEAT",playerId="B",displayName="Stuart",reliablePolicyVersion=2)
        val decoded=FastPacketCodec.decode(FastPacketCodec.encode(packet))
        assertEquals(2,decoded.reliablePolicyVersion)
        assertEquals(0,FastPacketCodec.decode("H|B|Stuart|0|100").reliablePolicyVersion)
    }
}
