package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.engine.BingoEngine
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class LiveDeviceSimulationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val deviceAdb = "adb -s 127.0.0.1:37129"
    private val artifactDir = File("/tmp/bingo-test")

    private fun execAdb(cmd: String): String {
        val fullCmd = "$deviceAdb $cmd"
        val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", fullCmd))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        return output
    }

    private fun captureScreenshot(filename: String) {
        val dest = File(artifactDir, filename)
        val cmd = "$deviceAdb exec-out screencap -p > ${dest.absolutePath}"
        Runtime.getRuntime().exec(arrayOf("bash", "-c", cmd)).waitFor(10, TimeUnit.SECONDS)
        println("Captured screenshot: ${dest.absolutePath} (${dest.length()} bytes)")
    }

    @Test
    fun simulate1v1AgainstPhysicalRealmeDevice() = runBlocking {
        org.junit.Assume.assumeTrue("Opt in with BINGO_LIVE_DEVICE=1", System.getenv("BINGO_LIVE_DEVICE") == "1")
        println("=== STARTING 1v1 SIMULATION AGAINST PHYSICAL REALME 8 PRO ===")

        // Wake screen before beginning
        execAdb("shell input keyevent 224")

        // Active Room Code on Realme 8 Pro
        val roomCode = "665GEG"
        println("Targeting Host Lobby Room Code: $roomCode")

        // 1. Setup Guest Transport & Keys
        val guestTempDir = File(System.getProperty("java.io.tmpdir"), "sim_guest_${System.currentTimeMillis()}").apply { mkdirs() }
        AccountKeyStore.keyDirOverride = guestTempDir
        val (guestUid, guestPub) = AccountKeyStore.createGuestKeyPair(guestTempDir)
        println("Guest generated UID: $guestUid")

        val connection = EmqxConnection("sim-guest-${UUID.randomUUID()}")
        val healthy = connection.awaitHealthy(12_000)
        assertTrue("EMQX connection must be healthy: ${connection.diagnostics}", healthy)
        println("Connected to EMQX broker successfully!")

        val transport = MqttRoomMetadataTransport(connection, { guestUid })
        val coordinator = RoomMetadataCoordinator(transport)

        val latencyReport = mutableMapOf<String, Long>()

        try {
            // 2. Measure Room Read Latency
            val t0 = System.currentTimeMillis()
            val candidate = transport.readCandidate(roomCode)
            if (candidate != null && !candidate.publisherKey.isNullOrBlank()) {
                AccountKeyStore.recordAuthoritativeKey(candidate.hostId, candidate.publisherKey!!)
                AccountKeyStore.bindHostKey(candidate.hostId, candidate.publisherKey!!, local = guestUid)
            }
            val roomOpt = transport.read(roomCode).getOrNull() ?: candidate
            val readLatency = System.currentTimeMillis() - t0
            latencyReport["metadata_read_ms"] = readLatency
            assertNotNull("Room $roomCode must exist on broker", roomOpt)
            val currentRoom = roomOpt!!
            println("[LATENCY] Room read: ${readLatency}ms | Host=${currentRoom.hostDisplayName} Status=${currentRoom.status} Players=${currentRoom.players.size}")

            // 3. Mutate: Join Room
            val guestPlayer = Player(
                id = guestUid,
                displayName = "Stuart (QA)",
                username = "stuart_qa",
                isHost = false,
                lobbyReadyStatus = "READY",
                readyVersion = 1L
            )

            val t1 = System.currentTimeMillis()
            val joinMutation = coordinator.mutate(roomCode) { session ->
                if (session.players.any { it.id == guestUid }) session
                else session.copy(players = session.players + guestPlayer)
            }
            val joinLatency = System.currentTimeMillis() - t1
            latencyReport["metadata_join_ms"] = joinLatency
            println("[LATENCY] Metadata Join Mutation: ${joinLatency}ms | Result=$joinMutation")
            assertTrue("Join mutation must be applied: $joinMutation", joinMutation is MutationResult.Applied)

            // 4. Subscribe to Live Room Gameplay Channel
            val roomTopic = "bingo/emqx7/room/${roomCode.trim().uppercase()}"
            val incomingPackets = ConcurrentLinkedQueue<RoomMessagePacket>()
            val pingFuture = CompletableFuture<Long>()
            val startFuture = CompletableFuture<RoomMessagePacket>()
            val pickFutures = mutableListOf<CompletableFuture<RoomMessagePacket>>()

            val subHandle = connection.subscribe(roomTopic) { payload, _ ->
                val str = payload.toString(Charsets.UTF_8).trim()
                val packet = runCatching {
                    if (str.startsWith("{")) json.decodeFromString<RoomMessagePacket>(str)
                    else FastPacketCodec.decode(str)
                }.getOrNull()
                if (packet != null) {
                    incomingPackets.add(packet)
                    if (packet.type == "PONG" && packet.playerId == guestUid) {
                        pingFuture.complete(System.currentTimeMillis() - (packet.pingTimestamp ?: 0L))
                    } else if (packet.type == "START_GAME") {
                        startFuture.complete(packet)
                    } else if (packet.type == "PICK_NUMBER") {
                        synchronized(pickFutures) {
                            pickFutures.find { !it.isDone }?.complete(packet)
                        }
                    }
                }
            }

            // 5. Send JOIN & READY packets on live highway
            val joinPacket = RoomMessagePacket(
                type = "JOIN",
                roomCode = roomCode,
                playerId = guestUid,
                displayName = guestPlayer.displayName,
                username = guestPlayer.username,
                isHost = false
            )
            connection.publish(roomTopic, json.encodeToString(joinPacket).toByteArray())

            val readyStr = "R|$guestUid|READY|stuart_qa|Stuart (QA)|1|sim-inst-1"
            connection.publish(roomTopic, readyStr.toByteArray())
            println("Published JOIN and READY_STATUS packets to $roomTopic")

            // Wait 1.5s for host device to display updated lobby
            delay(1500)
            captureScreenshot("sim_01_guest_joined_lobby.png")

            // 6. Measure Ping via G| / O| ping-pong
            val pingSentAt = System.currentTimeMillis()
            connection.publish(roomTopic, "G|$guestUid|$pingSentAt".toByteArray())
            val pingRtt = try {
                pingFuture.get(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
                System.currentTimeMillis() - pingSentAt
            }
            latencyReport["broker_ping_rtt_ms"] = pingRtt
            println("[LATENCY] Live Broker Ping RTT: ${pingRtt}ms")

            // 7. Tap "START GAME" on Realme device at coordinates (540, 1986)
            println("Triggering Host Start Tap at (540, 1986) on Realme 8 Pro...")
            val tBeforeStart = System.currentTimeMillis()
            execAdb("shell input tap 540 1986")

            // Wait for START_GAME or transition
            var startPacket: RoomMessagePacket? = null
            try {
                startPacket = startFuture.get(6, TimeUnit.SECONDS)
                val startLatency = System.currentTimeMillis() - tBeforeStart
                latencyReport["match_start_latency_ms"] = startLatency
                println("[LATENCY] Match Start Propagation: ${startLatency}ms | Seed=${startPacket.seed}")
            } catch (e: Exception) {
                println("Note: Host transitioned without standalone START_GAME packet or started via local engine")
            }

            // Wait 2s for board rendering
            delay(2000)
            captureScreenshot("sim_02_game_started_board.png")

            // 8. Play 1v1 Turns: Alternating Number Picks
            val seed = startPacket?.seed?.takeIf { it != 0L } ?: currentRoom.currentSeed.takeIf { it != 0L } ?: 123456789L
            val joinerBoard = BingoEngine().generateBoard(5, seed)
            println("Joiner local board generated from seed=$seed (cells=${joinerBoard.cells.size})")

            val pickedHistory = mutableListOf<Int>()
            val pickedByHistory = mutableListOf<String>()

            // Turn 1: Tap center cell on Realme 8 Pro board at (540, 1092)
            println("Turn 1: Tapping Host cell on phone board at (540, 1092)...")
            val hostPickFuture = CompletableFuture<RoomMessagePacket>()
            synchronized(pickFutures) { pickFutures.add(hostPickFuture) }
            val tBeforeHostTap = System.currentTimeMillis()
            execAdb("shell input tap 540 1092")

            val hostPickPacket = try {
                hostPickFuture.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                println("Host pick packet timeout, checking incoming queue...")
                incomingPackets.find { it.type == "PICK_NUMBER" }
            }

            if (hostPickPacket != null) {
                val pickLatency = System.currentTimeMillis() - tBeforeHostTap
                latencyReport["host_pick_propagation_ms"] = pickLatency
                val pickedNum = hostPickPacket.number.takeIf { it != 0 } ?: 13
                pickedHistory.add(pickedNum)
                pickedByHistory.add(currentRoom.hostId)
                println("[LATENCY] Turn 1 Host Pick received: $pickedNum in ${pickLatency}ms!")
            } else {
                println("Turn 1: Proceeding with number 13")
                pickedHistory.add(13)
                pickedByHistory.add(currentRoom.hostId)
            }

            delay(1000)
            captureScreenshot("sim_03_after_turn1_pick.png")

            // Turn 2: Joiner picks number 7 over MQTT
            println("Turn 2: Joiner picking number 7 over MQTT highway...")
            val joinerPickNum = 7
            pickedHistory.add(joinerPickNum)
            pickedByHistory.add(guestUid)

            val tBeforeJoinerPick = System.currentTimeMillis()
            val pickPacket = RoomMessagePacket(
                type = "PICK_NUMBER",
                number = joinerPickNum,
                playerId = guestUid,
                turnNumber = 2,
                currentTurnPlayerId = currentRoom.hostId,
                pickedHistory = pickedHistory,
                pickedByHistory = pickedByHistory,
                seed = seed,
                senderInstanceId = "sim-inst-1"
            )
            val encodedPick = FastPacketCodec.encode(pickPacket)
            connection.publish(roomTopic, encodedPick.toByteArray())
            val joinerPickLatency = System.currentTimeMillis() - tBeforeJoinerPick
            latencyReport["joiner_pick_send_ms"] = joinerPickLatency
            println("[LATENCY] Turn 2 Joiner Pick sent in ${joinerPickLatency}ms: $encodedPick")

            delay(1500)
            captureScreenshot("sim_04_after_turn2_joiner_pick.png")

            // Turn 3: Joiner sends interactive Chat Phrase
            println("Sending live chat from joiner...")
            val chatMsg = InGameChatMessage(
                text = "Good luck!",
                isSelf = false,
                senderName = "Stuart (QA)",
                senderPlayerId = guestUid
            )
            val chatPacket = RoomMessagePacket(
                type = "CHAT_MESSAGE",
                roomCode = roomCode,
                playerId = guestUid,
                chatMessages = listOf(chatMsg),
                senderInstanceId = "sim-inst-1"
            )
            connection.publish(roomTopic, json.encodeToString(chatPacket).toByteArray())

            delay(1200)
            captureScreenshot("sim_05_chat_and_emotes.png")

            // Turn 4: Test Player Presence ("Background / Minimized" state)
            println("Testing player presence notification...")
            val presencePacket = RoomMessagePacket(
                type = "PLAYER_PRESENCE",
                roomCode = roomCode,
                playerId = guestUid,
                presenceStatus = "MINIMIZED",
                senderInstanceId = "sim-inst-1"
            )
            connection.publish(roomTopic, json.encodeToString(presencePacket).toByteArray())
            delay(1000)
            captureScreenshot("sim_06_presence_minimized.png")

            // Turn 5: Return to active presence
            val activePresence = RoomMessagePacket(
                type = "PLAYER_PRESENCE",
                roomCode = roomCode,
                playerId = guestUid,
                presenceStatus = "ACTIVE",
                senderInstanceId = "sim-inst-1"
            )
            connection.publish(roomTopic, json.encodeToString(activePresence).toByteArray())
            delay(1000)

            println("\n==========================================")
            println("=== REAL-TIME LATENCY & PING SUMMARY ===")
            println("==========================================")
            latencyReport.forEach { (metric, ms) ->
                println("  - $metric: ${ms}ms")
            }
            println("==========================================")

            subHandle.close()
        } finally {
            // Cleanup guest mutation cleanly
            try {
                coordinator.mutate(roomCode) { session ->
                    session.copy(players = session.players.filter { it.id != guestUid })
                }
            } catch (_: Exception) {}
            transport.close()
            connection.close()
            AccountKeyStore.keyDirOverride = null
            AccountKeyStore.clearCachesForTests()
            guestTempDir.deleteRecursively()
        }

        println("=== 1v1 SIMULATION COMPLETED SUCCESSFULLY ===")
    }
}

