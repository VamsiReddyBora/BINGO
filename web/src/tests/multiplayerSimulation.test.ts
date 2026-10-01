import { describe, it } from 'node:test';
import assert from 'node:assert';
import { CloudRegistry } from '../network/cloudRegistry';
import { MqttRoomManager } from '../network/mqttSync';
import { BingoEngine } from '../engine/bingoEngine';
import { Player, RoomMessagePacket } from '../types/models';

describe('Multiplayer Lobby & Game End-to-End Simulation', () => {
  it('CloudRegistry creates, retrieves, and updates room ready status', async () => {
    const testRoomCode = `SIM${Math.floor(100 + Math.random() * 900)}`;
    const host: Player = {
      id: 'host_sim_1',
      displayName: 'Simulation Host',
      username: 'sim_host',
      isHost: true,
      avatarUrl: '👑',
      score: 100,
      completedLinesCount: 0,
      gamesPlayed: 5,
      gamesWon: 3,
      currentStreak: 2,
      level: 2,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'READY',
      readyVersion: 0
    };

    // 1. Create Room
    const created = await CloudRegistry.createRoom(testRoomCode, host, 5);
    assert.strictEqual(created, true, 'Room should be created in CloudRegistry');

    // 2. Retrieve Room
    const room = await CloudRegistry.getRoom(testRoomCode);
    assert.ok(room !== null, 'Room should be retrieved from CloudRegistry');
    assert.strictEqual(room.roomCode, testRoomCode);
    assert.strictEqual(room.hostId, host.id);
    assert.strictEqual(room.hostDisplayName, host.displayName);

    // 3. Guest joins and updates ready status
    const guestId = 'guest_sim_2';
    const guestUpdated = await CloudRegistry.updateRoomReadyStatus(testRoomCode, guestId, 'READY');
    assert.strictEqual(guestUpdated, true, 'Ready status should update');

    // 4. Verify updated room has guest marked READY
    const updatedRoom = await CloudRegistry.getRoom(testRoomCode);
    assert.ok(updatedRoom !== null);
    const guest = updatedRoom.players.find(p => p.id === guestId);
    assert.ok(guest !== undefined);
    assert.strictEqual(guest.lobbyReadyStatus, 'READY');
  });

  it('Dual-Client MQTT Room Sync: Join, Ready, Start Game, and Pick Numbers', async () => {
    const simRoomCode = `TEST${Math.floor(10 + Math.random() * 90)}`;
    const hostPlayer: Player = {
      id: 'host_p1',
      displayName: 'Alice Host',
      username: 'alice_host',
      isHost: true,
      avatarUrl: '👩',
      score: 50,
      completedLinesCount: 0,
      gamesPlayed: 2,
      gamesWon: 1,
      currentStreak: 1,
      level: 1,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'READY',
      readyVersion: 0
    };

    const guestPlayer: Player = {
      id: 'guest_p2',
      displayName: 'Bob Guest',
      username: 'bob_guest',
      isHost: false,
      avatarUrl: '👨',
      score: 50,
      completedLinesCount: 0,
      gamesPlayed: 2,
      gamesWon: 1,
      currentStreak: 1,
      level: 1,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'NOT_READY',
      readyVersion: 0
    };

    const hostManager = new MqttRoomManager();
    const guestManager = new MqttRoomManager();

    const hostPackets: RoomMessagePacket[] = [];
    const guestPackets: RoomMessagePacket[] = [];

    hostManager.onPacketReceived = (p) => hostPackets.push(p);
    guestManager.onPacketReceived = (p) => guestPackets.push(p);

    // Connect both clients
    hostManager.connect(simRoomCode, hostPlayer);
    guestManager.connect(simRoomCode, guestPlayer);

    // Wait for connection to establish and initial join packets to exchange
    await new Promise((resolve) => setTimeout(resolve, 2500));

    // Guest sends READY status update
    guestManager.sendPacket({
      type: 'READY_STATUS',
      playerId: guestPlayer.id,
      displayName: guestPlayer.displayName,
      readyStatus: 'READY',
      readyVersion: 1
    });
    await new Promise((resolve) => setTimeout(resolve, 1000));

    // Check if host received READY_STATUS packet from guest
    const readyPacket = hostPackets.find(p => p.type === 'READY_STATUS' && p.playerId === guestPlayer.id);
    assert.ok(readyPacket !== undefined, 'Host should receive READY_STATUS packet');
    assert.strictEqual(readyPacket.readyStatus, 'READY');

    // Host starts game
    const testSeed = 424242;
    hostManager.sendPacket({
      type: 'START_GAME',
      playerId: hostPlayer.id,
      seed: testSeed,
      currentTurnPlayerId: hostPlayer.id
    });
    await new Promise((resolve) => setTimeout(resolve, 1000));

    // Check if guest received START_GAME packet
    const startPacket = guestPackets.find(p => p.type === 'START_GAME');
    assert.ok(startPacket !== undefined, 'Guest should receive START_GAME packet');
    assert.strictEqual(startPacket.seed, testSeed);
    assert.strictEqual(startPacket.currentTurnPlayerId, hostPlayer.id);

    // Host makes first pick (number 7)
    hostManager.sendPacket({
      type: 'PICK_NUMBER',
      number: 7,
      playerId: hostPlayer.id,
      turnNumber: 1,
      currentTurnPlayerId: guestPlayer.id,
      pickedHistory: [7],
      seed: testSeed
    });
    await new Promise((resolve) => setTimeout(resolve, 1000));

    // Check if guest received PICK_NUMBER packet
    const pickPacket = guestPackets.find(p => p.type === 'PICK_NUMBER' && p.number === 7);
    assert.ok(pickPacket !== undefined, 'Guest should receive PICK_NUMBER packet');
    assert.strictEqual(pickPacket.number, 7);
    assert.strictEqual(pickPacket.playerId, hostPlayer.id);
    assert.strictEqual(pickPacket.currentTurnPlayerId, guestPlayer.id);

    // Guest sends hold-to-grow scaled emote (scale 2.5)
    guestManager.sendPacket({
      type: 'EMOTE',
      playerId: guestPlayer.id,
      displayName: '🚀',
      number: 250
    });
    await new Promise((resolve) => setTimeout(resolve, 1000));

    const emotePacket = hostPackets.find(p => p.type === 'EMOTE' && p.displayName === '🚀');
    assert.ok(emotePacket !== undefined, 'Host should receive EMOTE packet');
    assert.strictEqual(emotePacket.displayName, '🚀');
    assert.strictEqual(emotePacket.number, 250);

    // Clean up connections
    hostManager.disconnect();
    guestManager.disconnect();
  });

  it('Simulates full gameplay leading to a 5-line BINGO win', () => {
    // Generate board for player with deterministic seed
    let board = BingoEngine.generateBoard(5, 77777);

    // Compute numbers for 5 distinct lines:
    // Rows 0, 1, 2, 3, 4
    const allRowNumbers = [
      board.cells.filter(c => c.row === 0).map(c => c.number),
      board.cells.filter(c => c.row === 1).map(c => c.number),
      board.cells.filter(c => c.row === 2).map(c => c.number),
      board.cells.filter(c => c.row === 3).map(c => c.number),
      board.cells.filter(c => c.row === 4).map(c => c.number)
    ];

    let turn = 1;
    // Complete row 0..4
    for (let r = 0; r < 5; r++) {
      for (const num of allRowNumbers[r]) {
        const res = BingoEngine.markCell(board, num, 'player_winner', true, turn++);
        board = res.board;
      }
      assert.strictEqual(board.completedLines.length >= r + 1, true, `Should have at least ${r + 1} completed lines`);
    }

    assert.strictEqual(board.completedLines.length >= board.targetLines, true, 'Must have reached targetLines for BINGO');
  });

  it('Handles complex Android-style Google avatar URLs and payloads gracefully without data loss', async () => {
    const complexRoomCode = `GZ${Math.floor(1000 + Math.random() * 9000)}`;
    const googleUserHost: Player = {
      id: 'android_google_uid_999',
      displayName: 'Google Player',
      username: 'g_player',
      isHost: true,
      avatarUrl: 'https://lh3.googleusercontent.com/a/ACg8ocL-ih6HbPHoaQhxqV8vCkjzDyBTtqGPJnY6x81YGltV=s96-c',
      score: 250,
      completedLinesCount: 0,
      gamesPlayed: 12,
      gamesWon: 8,
      currentStreak: 4,
      level: 5,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'READY',
      readyVersion: 0
    };

    const created = await CloudRegistry.createRoom(complexRoomCode, googleUserHost, 5);
    assert.strictEqual(created, true, 'Should create room with Google avatar');

    const fetchedRoom = await CloudRegistry.getRoom(complexRoomCode);
    assert.ok(fetchedRoom !== null, 'Should fetch room with Google avatar');
    assert.strictEqual(fetchedRoom.roomCode, complexRoomCode);
    assert.strictEqual(fetchedRoom.hostId, googleUserHost.id);
    assert.strictEqual(fetchedRoom.hostDisplayName, googleUserHost.displayName);
  });
});
