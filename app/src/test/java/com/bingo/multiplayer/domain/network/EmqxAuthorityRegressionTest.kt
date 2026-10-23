package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Authority regression test suite covering metadata writer authority, untrusted injections,
 * replay protection, legitimate host writes, guest proposals, succession, and cold recovery. */
class EmqxAuthorityRegressionTest {

    // Friendly-lobby admission is explicitly code/invitation based (23 October 2026).
    // These peers deliberately have no shared account keys or key-discovery service.
    private suspend fun withUnpinnedPeers(code: String,
        block: suspend (EmqxWireFixture, Player, Player, MqttRoomMetadataTransport, MqttRoomMetadataTransport) -> Unit) {
        val wire = EmqxWireFixture()
        val host = Player("google_host_$code", "Kevin", "kevin_$code", isHost = true)
        val guest = Player("guest_installation_$code", "Stuart", "stuart_$code")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        try {
            val initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))
            assertTrue(hs.commit(code, null, initial) is RoomMetadataCommit.Applied)
            block(wire, host, guest, hs, gs)
        } finally { hs.close(); gs.close() }
    }


    @Test fun untrustedRetainedPublisherCannotCloseAnOwnedLobby() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("owned-authority-host", "Owned host", "owned_host", isHost = true)
        val transport = MqttRoomMetadataTransport(wire, { host.id })
        val code = "AU0123"
        try {
            val created = RoomMetadataCoordinator(transport).mutate(code, initial =
                OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it }
            assertTrue(created is MutationResult.Applied)
            val owned = transport.read(code).getOrThrow()!!
            // Another client with the app's shared broker credentials can publish a payload
            // claiming hostId. Topic/payload claims do not attest its application account.
            val forged = owned.copy(status = "CLOSED", isTerminalClosed = true, revision = owned.revision + 1L)
            wire.publish("bingo/emqx7/metadata/$code/state",
                Json { encodeDefaults = true }.encodeToString(forged).toByteArray(), retained = true)
            val observed = transport.read(code).getOrThrow()!!
            assertEquals("An untrusted publisher must not replace the actual host's authoritative room state", "WAITING", observed.status)
        } finally { transport.close() }
    }

    @Test fun untrustedRetainedPublisherCannotForgeRosterOrStartStateInOwnedLobby() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("owned-authority-host", "Owned host", "owned_host", isHost = true)
        val transport = MqttRoomMetadataTransport(wire, { host.id })
        val code = "AU0124"
        try {
            val created = RoomMetadataCoordinator(transport).mutate(code, initial =
                OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it }
            assertTrue(created is MutationResult.Applied)
            val owned = transport.read(code).getOrThrow()!!
            val attacker = Player("attacker-uid", "Attacker", "attacker")
            val forged = owned.copy(status = "PLAYING", currentSeed = 12345L, players = listOf(host, attacker), revision = owned.revision + 1L)
            wire.publish("bingo/emqx7/metadata/$code/state",
                Json { encodeDefaults = true }.encodeToString(forged).toByteArray(), retained = true)
            val observed = transport.read(code).getOrThrow()!!
            assertEquals("An untrusted publisher must not forge start/playing status", "WAITING", observed.status)
            assertEquals("An untrusted publisher must not inject players into owned lobby", listOf(host), observed.players)
        } finally { transport.close() }
    }

    @Test fun replayedSessionCannotOverwriteNewerSession() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("owned-authority-host", "Owned host", "owned_host", isHost = true)
        val transport = MqttRoomMetadataTransport(wire, { host.id })
        val code = "AU0125"
        try {
            val oldSession = OnlineRoomSession(code, host.id, host.username, host.displayName, createdAt = 1000L, revision = 10L,
                metadataProtocolVersion = 7, sessionId = "old-session-id", players = listOf(host))
            val newSession = OnlineRoomSession(code, host.id, host.username, host.displayName, createdAt = 2000L, revision = 1L,
                metadataProtocolVersion = 7, sessionId = "new-session-id", players = listOf(host))
            val created = RoomMetadataCoordinator(transport).mutate(code, initial = newSession) { it }
            assertTrue(created is MutationResult.Applied)
            // Replay older session with earlier createdAt
            wire.publish("bingo/emqx7/metadata/$code/state",
                Json { encodeDefaults = true }.encodeToString(oldSession).toByteArray(), retained = true)
            val observed = transport.read(code).getOrThrow()!!
            assertEquals("Replayed older session must be rejected", 2000L, observed.createdAt)
        } finally { transport.close() }
    }

    @Test fun legitimateHostWritesAreAcceptedAndObserved() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("owned-authority-host", "Owned host", "owned_host", isHost = true)
        val transport = MqttRoomMetadataTransport(wire, { host.id })
        val code = "AU0126"
        try {
            val h = RoomMetadataCoordinator(transport)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            val updated = h.mutate(code) { it.copy(boardSize = 4) }
            assertTrue(updated is MutationResult.Applied)
            val observed = transport.read(code).getOrThrow()!!
            assertEquals(4, observed.boardSize)
        } finally { transport.close() }
    }

    @Test fun guestProposalsAreSerializedAndAppliedByHost() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("owned-authority-host", "Owned host", "owned_host", isHost = true)
        val guest = Player("owned-guest", "Owned guest", "owned_guest")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val code = "AU0127"
        try {
            val h = RoomMetadataCoordinator(hs)
            val g = RoomMetadataCoordinator(gs)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            val joinResult = g.mutate(code) { it.copy(players = it.players + guest) }
            assertTrue("Guest proposal must be applied by host writer", joinResult is MutationResult.Applied)
            assertEquals(2, hs.read(code).getOrThrow()!!.players.size)
        } finally { hs.close(); gs.close() }
    }

    @Test fun authorizedPermanentExitSuccessionTransfersAuthority() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("host-1", "Host", "host_1", isHost = true)
        val guest = Player("guest-1", "Guest", "guest_1")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val code = "AU0128"
        try {
            val h = RoomMetadataCoordinator(hs)
            val g = RoomMetadataCoordinator(gs)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            assertTrue(g.mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)
            assertTrue(h.mutate(code, expectedSeed = 0L) { it.copy(status = "PLAYING", currentSeed = 42L) } is MutationResult.Applied)

            // Host intentionally exits and retires
            hs.retireLocalWriter(code, 42L, host.id)
            // Former host cannot mutate anymore
            assertTrue("Retired former writer cannot mutate", h.mutate(code, expectedSeed = 42L) { it.copy(status = "CLOSED") } !is MutationResult.Applied)
        } finally { hs.close(); gs.close() }
    }

    @Test fun coldRecoveryReadsValidRetainedRoom() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("host-1", "Host", "host_1", isHost = true)
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val code = "AU0129"
        try {
            assertTrue(RoomMetadataCoordinator(hs).mutate(code, initial =
                OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
        } finally { hs.close() }

        // Cold transport without in-memory cache
        val cold = MqttRoomMetadataTransport(wire, { "cold-reader" })
        try {
            val recovered = cold.read(code).getOrThrow()!!
            assertEquals(code, recovered.roomCode)
            assertEquals(host.id, recovered.hostId)
            assertEquals("WAITING", recovered.status)
        } finally { cold.close() }
    }

    @Test fun warmGuestRejectsMatchingForgedReplicasAcrossStateAndAuth() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("auth-host", "Host", "auth_host", isHost = true)
        val guest = Player("auth-guest", "Guest", "auth_guest")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val code = "AU0130"
        try {
            val h = RoomMetadataCoordinator(hs)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            val legitimate = gs.read(code).getOrThrow()!!
            assertEquals("WAITING", legitimate.status)

            // Attacker publishes matching forged replicas claiming CLOSED with higher revision to both /state and /auth
            val forged = legitimate.copy(status = "CLOSED", isTerminalClosed = true, revision = legitimate.revision + 10L,
                publisherSignature = null, publisherKey = null)
            val raw = Json { encodeDefaults = true }.encodeToString(forged).toByteArray()
            wire.publish("bingo/emqx7/metadata/$code/state", raw, retained = true)
            wire.publish("bingo/emqx7/metadata/$code/auth", raw, retained = true)

            val observed = gs.read(code).getOrThrow()!!
            assertEquals("Matching unsigned forged replicas must be rejected by warm guest", "WAITING", observed.status)
            assertFalse(observed.isTerminalClosed)
        } finally { hs.close(); gs.close() }
    }

    @Test fun coldReaderRejectsRecordsForAnotherRoomCode() = runBlocking {
        val wire = EmqxWireFixture()
        val unrelated = OnlineRoomSession("OTHER1", "google_host", "kevin", "Kevin", sessionId = "another-session", metadataProtocolVersion = 7)
        val bytes = Json { encodeDefaults = true }.encodeToString(unrelated).toByteArray()
        wire.publish("bingo/emqx7/metadata/AU0131/state", bytes, retained = true)
        wire.publish("bingo/emqx7/metadata/AU0131/auth", bytes, retained = true)
        val cold = MqttRoomMetadataTransport(wire, { "guest_reader" }, discoveryTimeoutMs = 20L)
        try { assertNull(cold.read("AU0131").getOrThrow()) } finally { cold.close() }
    }

    @Test fun legitimateHostAuthenticatedClosureAndRematchAreAccepted() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("legit-host", "Host", "legit_host", isHost = true)
        val guest = Player("legit-guest", "Guest", "legit_guest")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val code = "AU0132"
        try {
            val h = RoomMetadataCoordinator(hs)
            val g = RoomMetadataCoordinator(gs)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            assertTrue(g.mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)

            // Start game
            assertTrue(h.mutate(code, expectedSeed = 0L) { it.copy(status = "PLAYING", currentSeed = 777L) } is MutationResult.Applied)
            assertEquals("PLAYING", gs.read(code).getOrThrow()!!.status)

            // Legitimate rematch reset to WAITING
            assertTrue(h.mutate(code, allowReset = true, expectedSeed = 777L) { it.copy(status = "WAITING", currentSeed = 0L) } is MutationResult.Applied)
            val rematch = gs.read(code).getOrThrow()!!
            assertEquals("WAITING", rematch.status)
            assertEquals(0L, rematch.currentSeed)

            // Legitimate host closure
            assertTrue(h.mutate(code) { it.copy(status = "CLOSED") } is MutationResult.Applied)
            val closed = gs.read(code).getOrThrow()!!
            assertEquals("CLOSED", closed.status)
            assertTrue(closed.isTerminalClosed)
        } finally { hs.close(); gs.close() }
    }

    @Test fun dualReplicaPartialWriteFailsAndRestartRecoversOrReportsConsistentState() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("replica-host", "Host", "replica_host", isHost = true)
        val guest = Player("replica-guest", "Guest", "replica_guest")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val code = "AU0133"
        try {
            val h = RoomMetadataCoordinator(hs)
            val g = RoomMetadataCoordinator(gs)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            assertTrue(g.mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)

            // Inject partial write failure: before publishing to /auth, fail the write!
            wire.beforePublish = { topic, _ ->
                if (topic.endsWith("/auth")) {
                    throw java.io.IOException("Controlled /auth replica write outage")
                }
            }

            // A mutation attempt while /auth is failing must return Unavailable (not Applied)
            val partialWriteResult = h.mutate(code, expectedSeed = 0L) { it.copy(status = "PLAYING", currentSeed = 999L) }
            assertTrue("Partial replica write failure must be reported as Unavailable", partialWriteResult is MutationResult.Unavailable)

            // Clear the failure injection
            wire.beforePublish = { _, _ -> }

            // Restart reader / cold reader: cold reader must not see a corrupt or half-committed terminal state
            val cold = MqttRoomMetadataTransport(wire, { "cold-inspector" })
            try {
                val coldState = cold.read(code).getOrThrow()
                assertNotNull(coldState)
                assertTrue(coldState!!.status in setOf("WAITING", "PLAYING"))
            } finally {
                cold.close()
            }

            // Now retry the mutation after replica recovery: must succeed and apply cleanly
            val retryResult = h.mutate(code, expectedSeed = 0L) { it.copy(status = "PLAYING", currentSeed = 999L) }
            assertTrue("Retry after replica recovery must apply successfully", retryResult is MutationResult.Applied)
            assertEquals("PLAYING", gs.read(code).getOrThrow()!!.status)
            assertEquals(999L, gs.read(code).getOrThrow()!!.currentSeed)
        } finally {
            hs.close()
            gs.close()
        }
    }

    @Test
    fun hostRestartAcrossSeparateKeyStoreMaintainsKeyAndSignature() = runBlocking {
        val hostUid = "restart-test-host"
        val initialKey = AccountKeyStore.getPublicKeyBase64(hostUid)
        val data = "canonical-payload-to-sign"
        val initialSig = AccountKeyStore.sign(hostUid, data)
        assertTrue(AccountKeyStore.verify(initialKey, data, initialSig))

        // Reset in-memory cache to simulate fresh process restart
        val keyPairsField = AccountKeyStore::class.java.getDeclaredField("keyPairs").apply { isAccessible = true }
        (keyPairsField.get(null) as java.util.concurrent.ConcurrentHashMap<*, *>).clear()
        val hostBindingsField = AccountKeyStore::class.java.getDeclaredField("hostBindings").apply { isAccessible = true }
        (hostBindingsField.get(null) as java.util.concurrent.ConcurrentHashMap<*, *>).clear()

        // After restart, key and signature verification must still match perfectly
        val restartedKey = AccountKeyStore.getPublicKeyBase64(hostUid)
        assertEquals(initialKey, restartedKey)
        assertTrue(AccountKeyStore.verify(restartedKey, data, initialSig))

        val newSig = AccountKeyStore.sign(hostUid, data)
        assertTrue(AccountKeyStore.verify(restartedKey, data, newSig))
    }

    @Test fun coldRoomLookupDoesNotRequireAccountKeyEvidence() = runBlocking {
        withUnpinnedPeers("AU0135") { _, host, _, _, gs ->
            val room = gs.read("AU0135").getOrThrow()!!
            assertEquals(host.id, room.hostId)
            assertEquals("WAITING", room.status)
            assertNull("A room lookup must not generate a publisher key", room.publisherKey)
        }
    }

    @Test
    fun unauthorizedSuccessionClaimingHostDepartedIsRejected() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("legit-host-succ", "Host", "legit_host_succ", isHost = true)
        val guest = Player("legit-guest-succ", "Guest", "legit_guest_succ")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val code = "AU0136"
        try {
            val h = RoomMetadataCoordinator(hs)
            val g = RoomMetadataCoordinator(gs)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            assertTrue(g.mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)

            // Foreign record claims guest became host and old host departed, but local engine has not promoted guest
            val current = hs.read(code).getOrThrow()!!
            val fraudulentSuccession = current.copy(
                hostId = guest.id,
                players = listOf(guest.copy(isHost = true)),
                revision = current.revision + 1L
            )
            val authPayload = AccountKeyStore.computeAuthPayload(code, fraudulentSuccession)
            val guestSig = AccountKeyStore.sign(guest.id, authPayload)
            val guestPub = AccountKeyStore.getPublicKeyBase64(guest.id)
            val signed = fraudulentSuccession.copy(publisherKey = guestPub, publisherSignature = guestSig)
            val bytes = Json { encodeDefaults = true }.encodeToString(signed).toByteArray()
            wire.publish("bingo/emqx7/metadata/$code/state", bytes, retained = true)
            wire.publish("bingo/emqx7/metadata/$code/auth", bytes, retained = true)

            // Warm reader must reject succession because gameplay engine never promoted guest
            val observed = hs.read(code).getOrThrow()!!
            assertEquals("Host authority must remain with original host", host.id, observed.hostId)
        } finally {
            hs.close()
            gs.close()
        }
    }

    @Test fun matchingUnsignedHostAcknowledgementAdmitsTheGuest() = runBlocking {
        withUnpinnedPeers("AU0137") { _, _, guest, hs, gs ->
            val result = RoomMetadataCoordinator(gs).mutate("AU0137") { it.copy(players = it.players + guest) }
            assertTrue(result is MutationResult.Applied)
            val room = (result as MutationResult.Applied).session
            assertNull(room.publisherSignature)
            assertEquals(1, hs.read("AU0137").getOrThrow()!!.players.count { it.id == guest.id })
        }
    }

    @Test
    fun guestProposalCannotAddAnotherParticipant() = runBlocking {
        val wire = EmqxWireFixture()
        val host = Player("auth-prop-host", "Host", "auth_prop_host", isHost = true)
        val guest = Player("admitted-guest", "Guest", "admitted_guest")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val code = "AU0138"
        try {
            val h = RoomMetadataCoordinator(hs)
            val g = RoomMetadataCoordinator(gs)
            assertTrue(h.mutate(code, initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            assertTrue(g.mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)
            val current = hs.read(code).getOrThrow()!!

            // Establish legitimate guest key
            val legitGuestKey = AccountKeyStore.getPublicKeyBase64(guest.id)
            AccountKeyStore.bindHostKey(guest.id, legitGuestKey)

            // Attacker generates separate keypair and attempts to propose as 'admitted-guest'
            val attackerKp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
            val attackerPub = okio.ByteString.of(*attackerKp.public.encoded).base64()
            val propId = "prop-impersonate-1"
            val responseTopic = "bingo/emqx7/metadata/reply/attacker"
            val sentAt = System.currentTimeMillis()
            val next = current.copy(players = current.players + Player("extra", "Extra"))
            val basePayload = AccountKeyStore.computeAuthPayload(code, current)
            val nextPayload = AccountKeyStore.computeAuthPayload(code, next)
            val initialPayload = "$code|$propId|${guest.id}||$responseTopic|$sentAt|$basePayload|$nextPayload"
            val attackerSig = java.security.Signature.getInstance("SHA256withRSA").apply {
                initSign(attackerKp.private)
                update(initialPayload.toByteArray(Charsets.UTF_8))
            }.sign()
            val attackerSigBase64 = okio.ByteString.of(*attackerSig).base64()

            var interceptedReply: MqttRoomMetadataTransport.Reply? = null
            wire.subscribe(responseTopic) { bytes, _ ->
                interceptedReply = Json { ignoreUnknownKeys = true }.decodeFromString<MqttRoomMetadataTransport.Reply>(String(bytes))
            }

            val fraudulentProposal = MqttRoomMetadataTransport.Proposal(
                id = propId,
                actor = guest.id,
                reply = responseTopic,
                sentAt = sentAt,
                base = current,
                next = next,
                actorKey = attackerPub,
                actorSig = attackerSigBase64
            )
            wire.publish("bingo/emqx7/metadata/$code/command", Json { encodeDefaults = true }.encodeToString(fraudulentProposal).toByteArray())

            // Host should reject the proposal because actorKey does not match the established key for admitted-guest
            kotlinx.coroutines.delay(200)
            assertNotNull("Reply must be received", interceptedReply)
            assertEquals("rejected", interceptedReply?.status)
            assertEquals("Guest cannot change other participants", interceptedReply?.reason)
        } finally {
            hs.close()
            gs.close()
        }
    }

    @Test
    fun coldRecoveryReadsValidRetainedRoomUsingPersistedRemoteHostBinding() = runBlocking {
        val wire = EmqxWireFixture()
        val hostUid = "persisted-host-uid"
        val code = "AU0139"

        val tempKeyDir = java.io.File(System.getProperty("java.io.tmpdir"), "bingo_keys_${System.currentTimeMillis()}").apply { mkdirs() }
        AccountKeyStore.keyDirOverride = tempKeyDir
        AccountKeyStore.clearCachesForTests()
        try {
            // Lifecycle 1: Host creates and signs room, guest establishes host key binding into remote_hosts.dat
            val hostKey = AccountKeyStore.getPublicKeyBase64(hostUid)
            AccountKeyStore.bindHostKey(hostUid, hostKey)

            val room = OnlineRoomSession(code, hostUid, "host_u", "Host D", status = "WAITING", createdAt = 1000L,
                revision = 1L, metadataProtocolVersion = 7, sessionId = "sess-persisted", players = listOf(Player(hostUid, "Host D", isHost = true)))
            val authPayload = AccountKeyStore.computeAuthPayload(code, room)
            val sig = AccountKeyStore.sign(hostUid, authPayload)
            val signed = room.copy(publisherKey = hostKey, publisherSignature = sig)
            val bytes = Json { encodeDefaults = true }.encodeToString(signed).toByteArray()
            wire.publish("bingo/emqx7/metadata/$code/state", bytes, retained = true)
            wire.publish("bingo/emqx7/metadata/$code/auth", bytes, retained = true)

            // Lifecycle 2: Clear memory caches, simulate process restart reading from same keyDir
            AccountKeyStore.clearCachesForTests()

            val coldTransport = MqttRoomMetadataTransport(wire, { "independent-guest-reader" })
            try {
                val recovered = coldTransport.read(code).getOrNull()
                assertNotNull("Cold recovery must succeed using persisted remote host key binding", recovered)
                assertEquals(hostUid, recovered?.hostId)
            } finally {
                coldTransport.close()
            }
        } finally {
            AccountKeyStore.keyDirOverride = null
            tempKeyDir.deleteRecursively()
        }
    }

    @Test
    fun replicaDisagreementResolvesByRevisionAndRejectsObsoleteOpenOverridingClosed() = runBlocking {
        val wire = EmqxWireFixture()
        val hostUid = "replica-disagree-host"
        val code = "AU0140"
        AccountKeyStore.clearCachesForTests()
        val hostKey = AccountKeyStore.getPublicKeyBase64(hostUid)
        AccountKeyStore.bindHostKey(hostUid, hostKey)

        try {
            val commonCreatedAt = 1000L
            // A closed room with revision 10
            val closedRoom = OnlineRoomSession(code, hostUid, "host_u", "Host D", status = "CLOSED", isTerminalClosed = true, createdAt = commonCreatedAt,
                revision = 10L, metadataProtocolVersion = 7, sessionId = "sess-replica-1", players = listOf(Player(hostUid, "Host D", isHost = true)))
            val closedPayload = AccountKeyStore.computeAuthPayload(code, closedRoom)
            val closedSig = AccountKeyStore.sign(hostUid, closedPayload)
            val signedClosed = closedRoom.copy(publisherKey = hostKey, publisherSignature = closedSig)
            val closedBytes = Json { encodeDefaults = true }.encodeToString(signedClosed).toByteArray()

            // An older open room with revision 5
            val openRoom = OnlineRoomSession(code, hostUid, "host_u", "Host D", status = "WAITING", isTerminalClosed = false, createdAt = commonCreatedAt,
                revision = 5L, metadataProtocolVersion = 7, sessionId = "sess-replica-1", players = listOf(Player(hostUid, "Host D", isHost = true)))
            val openPayload = AccountKeyStore.computeAuthPayload(code, openRoom)
            val openSig = AccountKeyStore.sign(hostUid, openPayload)
            val signedOpen = openRoom.copy(publisherKey = hostKey, publisherSignature = openSig)
            val openBytes = Json { encodeDefaults = true }.encodeToString(signedOpen).toByteArray()

            // Case A: State has older open (rev 5), Auth has newer closed (rev 10)
            wire.publish("bingo/emqx7/metadata/$code/state", openBytes, retained = true)
            wire.publish("bingo/emqx7/metadata/$code/auth", closedBytes, retained = true)

            val transportA = MqttRoomMetadataTransport(wire, { "reader-a" })
            try {
                val resolvedA = transportA.read(code).getOrNull()
                assertNotNull(resolvedA)
                assertEquals("CLOSED", resolvedA?.status)
                assertEquals(10L, resolvedA?.revision)
            } finally { transportA.close() }

            // Case B: State has older closed (rev 5), Auth has newer open (rev 15) in same session -> Inconsistent closure
            val newerOpenRoom = openRoom.copy(revision = 15L, createdAt = commonCreatedAt)
            val newerOpenPayload = AccountKeyStore.computeAuthPayload(code, newerOpenRoom)
            val newerOpenSig = AccountKeyStore.sign(hostUid, newerOpenPayload)
            val signedNewerOpen = newerOpenRoom.copy(publisherKey = hostKey, publisherSignature = newerOpenSig)
            val newerOpenBytes = Json { encodeDefaults = true }.encodeToString(signedNewerOpen).toByteArray()

            val olderClosed = closedRoom.copy(revision = 5L, createdAt = commonCreatedAt)
            val olderClosedPayload = AccountKeyStore.computeAuthPayload(code, olderClosed)
            val olderClosedSig = AccountKeyStore.sign(hostUid, olderClosedPayload)
            val signedOlderClosed = olderClosed.copy(publisherKey = hostKey, publisherSignature = olderClosedSig)
            val olderClosedBytes = Json { encodeDefaults = true }.encodeToString(signedOlderClosed).toByteArray()

            wire.publish("bingo/emqx7/metadata/$code/state", olderClosedBytes, retained = true)
            wire.publish("bingo/emqx7/metadata/$code/auth", newerOpenBytes, retained = true)

            val transportB = MqttRoomMetadataTransport(wire, { "reader-b" })
            try {
                val resultB = transportB.read(code)
                assertTrue("Inconsistent replica closure must fail closed", resultB.isFailure)
            } finally { transportB.close() }
        } finally {}
    }

    @Test fun freshGuestCanDiscoverAndJoinWithoutStoredAccountKeys() = runBlocking {
        withUnpinnedPeers("AU0141") { _, host, guest, hs, gs ->
            assertEquals(host.id, gs.read("AU0141").getOrThrow()!!.hostId)
            val joined = RoomMetadataCoordinator(gs).mutate("AU0141") { it.copy(players = it.players + guest) }
            assertTrue(joined is MutationResult.Applied)
            assertEquals(2, hs.read("AU0141").getOrThrow()!!.players.size)
        }
    }

    @Test
    fun unsolicitedProposalCannotOverwriteExistingHostKeyBinding() = runBlocking {
        val hostUid = "trusted-host-uid"
        val attackerUid = "unsolicited-attacker-uid"
        AccountKeyStore.clearCachesForTests()
        val originalHostKey = AccountKeyStore.getPublicKeyBase64(hostUid)
        val attackerKey = AccountKeyStore.getPublicKeyBase64(attackerUid)

        // Bind legitimate host key
        assertTrue(AccountKeyStore.bindHostKey(hostUid, originalHostKey))

        // Attacker attempts to overwrite host key binding with attacker key
        val overwriteResult = AccountKeyStore.bindHostKey(hostUid, attackerKey)
        assertFalse("Unsolicited attempt to rebind existing host key must be rejected", overwriteResult)
        assertTrue("Legitimate host key must remain bound", AccountKeyStore.isHostKeyValid(hostUid, originalHostKey))
        assertFalse("Attacker key must not be valid for host", AccountKeyStore.isHostKeyValid(hostUid, attackerKey))
    }

    @Test
    fun reverseReplicaDisagreementWhereStateIsNewerClosedAndAuthIsOlderOpenResolvesToClosed() = runBlocking {
        val wire = EmqxWireFixture()
        val hostUid = "reverse-replica-host"
        val code = "AU0142"
        AccountKeyStore.clearCachesForTests()
        val hostKey = AccountKeyStore.getPublicKeyBase64(hostUid)
        AccountKeyStore.bindHostKey(hostUid, hostKey)

        try {
            val commonCreatedAt = 2000L
            val closedRoom = OnlineRoomSession(code, hostUid, "host_u", "Host D", status = "CLOSED", isTerminalClosed = true, createdAt = commonCreatedAt,
                revision = 10L, metadataProtocolVersion = 7, sessionId = "sess-rev-1", players = listOf(Player(hostUid, "Host D", isHost = true)))
            val closedPayload = AccountKeyStore.computeAuthPayload(code, closedRoom)
            val closedSig = AccountKeyStore.sign(hostUid, closedPayload)
            val signedClosed = closedRoom.copy(publisherKey = hostKey, publisherSignature = closedSig)
            val closedBytes = Json { encodeDefaults = true }.encodeToString(signedClosed).toByteArray()

            val openRoom = OnlineRoomSession(code, hostUid, "host_u", "Host D", status = "WAITING", isTerminalClosed = false, createdAt = commonCreatedAt,
                revision = 5L, metadataProtocolVersion = 7, sessionId = "sess-rev-1", players = listOf(Player(hostUid, "Host D", isHost = true)))
            val openPayload = AccountKeyStore.computeAuthPayload(code, openRoom)
            val openSig = AccountKeyStore.sign(hostUid, openPayload)
            val signedOpen = openRoom.copy(publisherKey = hostKey, publisherSignature = openSig)
            val openBytes = Json { encodeDefaults = true }.encodeToString(signedOpen).toByteArray()

            // State has newer closed (rev 10), Auth has older open (rev 5)
            wire.publish("bingo/emqx7/metadata/$code/state", closedBytes, retained = true)
            wire.publish("bingo/emqx7/metadata/$code/auth", openBytes, retained = true)

            val transport = MqttRoomMetadataTransport(wire, { "reverse-reader" })
            try {
                val resolved = transport.read(code).getOrNull()
                assertNotNull("Reverse replica disagreement must successfully resolve to newer closed replica", resolved)
                assertEquals("CLOSED", resolved?.status)
                assertEquals(10L, resolved?.revision)
            } finally {
                transport.close()
            }
        } finally {}
    }

    @Test fun applicationInvitationUsesRoomSessionWithoutHostKeyBinding() = runBlocking {
        withUnpinnedPeers("AJ0101") { _, host, guest, hs, gs ->
            val original = RoomMetadataApi.transportOverride
            RoomMetadataApi.transportOverride = gs
            try {
                val session = hs.read("AJ0101").getOrThrow()!!
                val joined = OnlineRoomRegistry.validateAndJoinRoom("AJ0101", guest, session.sessionId, host.id)
                assertTrue("The actual invitation admission path must work without key pre-sharing: $joined", joined is RoomJoinResult.Success)
                assertEquals(1, hs.read("AJ0101").getOrThrow()!!.players.count { it.id == guest.id })
                assertTrue(OnlineRoomRegistry.validateAndJoinRoom("AJ0101", guest, "old-session", host.id) is RoomJoinResult.NotFound)
            } finally {
                RoomMetadataApi.transportOverride = original
                OnlineRoomRegistry.recentRoomCache.remove("AJ0101")
            }
        }
    }

    @Test fun acknowledgementForAnotherRoomCannotConfirmThisJoin() = runBlocking {
        withUnpinnedPeers("AU0147") { wire, _, guest, hs, gs ->
            val current = hs.read("AU0147").getOrThrow()!!
            wire.beforePublish = { topic, bytes ->
                if (topic.endsWith("/command")) {
                    val proposal = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<MqttRoomMetadataTransport.Proposal>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    if (proposal != null) {
                        val wrong = proposal.next.copy(roomCode = "OTHER1", revision = current.revision + 1L)
                        val reply = MqttRoomMetadataTransport.Reply(proposal.id, "applied", wrong)
                        wire.publish(proposal.reply, Json { encodeDefaults = true }.encodeToString(reply).toByteArray())
                    }
                }
            }
            val result = gs.commit("AU0147", current, current.copy(players = current.players + guest))
            assertTrue("A response must match the requested room/session", result is RoomMetadataCommit.Unavailable)
        }
    }

    @Test fun legitimateSuccessionAcrossThreeClientsUsesTheAcceptedRoster() = runBlocking {
        withUnpinnedPeers("AU0148") { wire, host, guest, hs, gs ->
            val alice = Player("google_alice_AU0148", "Alice", "alice_AU0148")
            val asTransport = MqttRoomMetadataTransport(wire, { alice.id })
            val previousSync = OnlineRoomSyncManager.activeInstance
            val sync = OnlineRoomSyncManager()
            try {
                assertTrue(RoomMetadataCoordinator(gs).mutate("AU0148") { it.copy(players = it.players + guest) } is MutationResult.Applied)
                assertTrue(RoomMetadataCoordinator(asTransport).mutate("AU0148") { it.copy(players = it.players + alice) } is MutationResult.Applied)
                assertTrue(RoomMetadataCoordinator(hs).mutate("AU0148") { it.copy(status = "PLAYING", currentSeed = 1001L) } is MutationResult.Applied)
                val playing = asTransport.read("AU0148").getOrThrow()!!
                sync.localPlayer = alice
                sync.currentRoomCode = "AU0148"
                sync.currentLobbySessionId = playing.sessionId
                sync.bindPeerMatch(1001L)
                sync.updateMatchRoster(listOf(host, guest, alice))
                assertFalse("A guest cannot take over while the host remains", sync.applyRoomMetadata(playing.copy(hostId = guest.id, revision = playing.revision + 1L), "AU0148"))
                sync.updateMatchRoster(listOf(guest.copy(isHost = true), alice))
                hs.retireLocalWriter("AU0148", 1001L, host.id)
                val promoted = RoomMetadataCoordinator(gs).mutate("AU0148") {
                    it.copy(hostId = guest.id, players = listOf(guest.copy(isHost = true), alice))
                }
                assertTrue("The accepted roster must permit legitimate host succession: $promoted", promoted is MutationResult.Applied)
                val observed = asTransport.read("AU0148").getOrThrow()!!
                assertEquals(guest.id, observed.hostId)
                assertFalse(observed.players.any { it.id == host.id })
                assertTrue(sync.applyRoomMetadata(observed, "AU0148"))
                assertEquals(1001L, observed.currentSeed)
            } finally {
                asTransport.close()
                sync.disconnect()
                sync.drainForTests()
                OnlineRoomSyncManager.activeInstance = previousSync
            }
        }
    }

    @Test fun absentAccountKeyEvidenceDoesNotPreventRoomAdmission() = runBlocking {
        withUnpinnedPeers("AU0149") { _, _, guest, hs, gs ->
            val previous = AccountKeyStore.accountKeyResolver
            val lookups = java.util.concurrent.atomic.AtomicInteger()
            AccountKeyStore.accountKeyResolver = {
                lookups.incrementAndGet()
                AccountKeyStore.AccountKeyEvidence.Absent
            }
            try {
                assertTrue(RoomMetadataCoordinator(gs).mutate("AU0149") { it.copy(players = it.players + guest) } is MutationResult.Applied)
                assertEquals(0, lookups.get())
                assertTrue(hs.read("AU0149").getOrThrow()!!.players.any { it.id == guest.id })
            } finally { AccountKeyStore.accountKeyResolver = previous }
        }
    }

}
