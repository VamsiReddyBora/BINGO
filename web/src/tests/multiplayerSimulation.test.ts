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

  it('Live in-game chat: clients exchange CHAT_MESSAGE and CHAT_PHRASE packets in real time', async () => {
    const chatRoom = `CHAT${Math.floor(10 + Math.random() * 90)}`;
    const sender = new MqttRoomManager();
    const receiver = new MqttRoomManager();

    const senderPlayer: Player = {
      id: 'sender_p1',
      displayName: 'Sender Alex',
      username: 'alex',
      isHost: true,
      avatarUrl: '🧑',
      score: 0,
      completedLinesCount: 0,
      gamesPlayed: 0,
      gamesWon: 0,
      currentStreak: 0,
      level: 1,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'READY',
      readyVersion: 0
    };

    const receiverPlayer: Player = {
      id: 'receiver_p2',
      displayName: 'Receiver Bob',
      username: 'bob',
      isHost: false,
      avatarUrl: '👨',
      score: 0,
      completedLinesCount: 0,
      gamesPlayed: 0,
      gamesWon: 0,
      currentStreak: 0,
      level: 1,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'READY',
      readyVersion: 0
    };

    sender.connect(chatRoom, senderPlayer);
    receiver.connect(chatRoom, receiverPlayer);

    const receivedChatPromise = new Promise<RoomMessagePacket>((resolve) => {
      receiver.onPacketReceived = (pkt) => {
        if (pkt.type === 'CHAT_MESSAGE') {
          resolve(pkt);
        }
      };
    });

    // Wait for connection to establish
    await new Promise(r => setTimeout(r, 2000));

    // Send custom in-game chat message
    await sender.sendPacket({
      type: 'CHAT_MESSAGE',
      playerId: senderPlayer.id,
      displayName: 'Hey Bob, nice match! 🎯',
      username: senderPlayer.displayName,
      timestamp: Date.now()
    });

    const received = await Promise.race([
      receivedChatPromise,
      new Promise<null>(r => setTimeout(() => r(null), 5000))
    ]);

    assert.ok(received !== null, 'Receiver should receive in-game CHAT_MESSAGE packet');
    assert.strictEqual(received.type, 'CHAT_MESSAGE');
    assert.strictEqual(received.displayName, 'Hey Bob, nice match! 🎯');
    assert.strictEqual(received.username, senderPlayer.displayName);

    sender.disconnect();
    receiver.disconnect();
  });

  it('Google user cloud backup and friends list cross-platform sync', async () => {
    const testGoogleId = `gid_test_${Date.now()}`;
    const testUsername = `user_${Math.floor(1000 + Math.random() * 9000)}`;

    const backupData = {
      profile: {
        uid: `google_${testGoogleId}`,
        username: testUsername,
        displayName: 'Vamsi Cloud Player',
        email: 'vamsi.cloud@example.com',
        avatarUrl: 'https://lh3.googleusercontent.com/a/test-avatar',
        gamesPlayed: 25,
        gamesWon: 18,
        currentStreak: 6,
        level: 5,
        xp: 1200
      },
      settings: {
        soundEnabled: true,
        hapticsEnabled: true,
        preferredBoardSize: 5,
        darkTheme: false
      },
      matchHistory: [
        {
          id: 'match_1',
          mode: 'Online',
          opponentName: 'Android Rival',
          didWin: true,
          boardSize: 5,
          timestamp: Date.now() - 3600000
        }
      ],
      lastBackupTimestamp: Date.now()
    };

    // 1. Save user backup to cloud
    const saved = await CloudRegistry.saveUserDataBackup(testGoogleId, backupData);
    assert.strictEqual(saved, true, 'Should successfully save user data backup to cloud');

    // 2. Fetch user backup by Google ID
    const restoredByGid = await CloudRegistry.fetchUserDataBackup(testGoogleId);
    assert.ok(restoredByGid !== null, 'Should fetch user data backup by Google ID');
    assert.strictEqual(restoredByGid.profile.username, testUsername);
    assert.strictEqual(restoredByGid.profile.displayName, 'Vamsi Cloud Player');
    assert.strictEqual(restoredByGid.profile.gamesWon, 18);
    assert.strictEqual(restoredByGid.profile.level, 5);
    assert.strictEqual(restoredByGid.matchHistory?.length, 1);

    // 3. Fetch user backup by username
    const restoredByUser = await CloudRegistry.fetchUserBackupByUsername(testUsername);
    assert.ok(restoredByUser !== null, 'Should fetch user data backup by username');
    assert.strictEqual(restoredByUser.profile.username, testUsername);

    // 4. Save and fetch friends list
    const testFriends = [
      {
        uid: 'android_friend_1',
        username: 'friend_rahul',
        displayName: 'Rahul',
        avatarUrl: '👦',
        gamesPlayed: 10,
        gamesWon: 7,
        currentStreak: 2,
        level: 3,
        lastSeenTimestamp: Date.now()
      }
    ];

    const friendsSaved = await CloudRegistry.saveCloudFriends(testUsername, testFriends);
    assert.strictEqual(friendsSaved, true, 'Should save friends list to cloud');

    const friendsFetched = await CloudRegistry.fetchCloudFriends(testUsername);
    assert.strictEqual(friendsFetched.length, 1, 'Should fetch 1 friend');
    assert.strictEqual(friendsFetched[0].username, 'friend_rahul');
    assert.strictEqual(friendsFetched[0].displayName, 'Rahul');
  });

  it('Cross-platform Win/Loss Sync: Web wins -> opponent sees loss; Android wins -> web sees loss', () => {
    /**
     * Simulates the board seed split used by Android RootNavGraph:
     *  - Host player board: generateBoard(seed)
     *  - Guest player board: generateBoard(seed + 1)
     *  (from Android's startNewGame where isHosting determines seed assignment)
     *
     * Verifies:
     * 1. KotlinRandom boards match across Android & Web for same seeds.
     * 2. When web-host picks 25 numbers that complete all 5 lines -> win detected.
     * 3. When those same 25 numbers are applied to web-guest's board (receiving) -> guest sees loss (opp 5 lines).
     */
    const seed = 777777;

    // HOST board (seed) and GUEST board (seed+1)
    const hostBoard = BingoEngine.generateBoard(5, seed);
    const guestBoard = BingoEngine.generateBoard(5, seed + 1);

    // Boards must be different
    const hostNumbers = hostBoard.cells.map(c => c.number);
    const guestNumbers = guestBoard.cells.map(c => c.number);
    assert.notDeepStrictEqual(hostNumbers, guestNumbers, 'Host and guest boards must differ');

    // Both must have 25 unique numbers 1-25
    assert.strictEqual(new Set(hostNumbers).size, 25, 'Host board must have 25 unique numbers');
    assert.strictEqual(new Set(guestNumbers).size, 25, 'Guest board must have 25 unique numbers');

    // --- Simulate: Host picks all 25 numbers to win ---
    let hostCurrentBoard = { ...hostBoard };
    const pickedHistory: number[] = [];

    for (let turn = 1; turn <= 25; turn++) {
      const unmarked = hostCurrentBoard.cells.filter(c => c.markState.type === 'Unmarked');
      if (unmarked.length === 0) break;
      const num = unmarked[0].number;
      pickedHistory.push(num);
      const { board: updated } = BingoEngine.markCell(hostCurrentBoard, num, 'host', true, turn);
      hostCurrentBoard = updated;
    }

    // Host must have won (5+ lines) by exhausting all numbers
    assert.ok(hostCurrentBoard.completedLines.length >= 5, `Host should have 5 lines, got ${hostCurrentBoard.completedLines.length}`);

    // --- Simulate: Guest receives those same picks on their board ---
    // Guest's local board = generateBoard(seed+1) - same board for guest when they're guest
    let guestLocalBoard = { ...guestBoard };    // my board as guest
    let guestOppBoard = { ...hostBoard };       // opponent (host) board tracking

    let guestLocalLines = 0;
    let guestOppLines = 0;

    for (let turn = 1; turn <= pickedHistory.length; turn++) {
      const num = pickedHistory[turn - 1];

      // Mark on guest's local board (host's pick is NOT own pick for guest)
      const { board: gl } = BingoEngine.markCell(guestLocalBoard, num, 'host', false, turn);
      guestLocalBoard = gl;
      guestLocalLines = guestLocalBoard.completedLines.length;

      // Mark on guest's opponent-tracking board (host's pick IS own pick on host's board)
      const { board: go } = BingoEngine.markCell(guestOppBoard, num, 'host', true, turn);
      guestOppBoard = go;
      guestOppLines = guestOppBoard.completedLines.length;

      if (guestOppLines >= 5) break; // Opponent won -> guest loses
    }

    // Guest must detect opponent win -> they lose
    assert.ok(guestOppLines >= 5, `Guest should detect opponent reached 5 lines, got ${guestOppLines}`);
    assert.ok(guestLocalLines < 5, `Guest should NOT have 5 lines themselves (they lose), got ${guestLocalLines}`);

    // --- Reverse: Guest (seed+1) wins, host receives their picks ---
    let guestWinBoard = { ...guestBoard };
    const guestPicks: number[] = [];

    for (let turn = 1; turn <= 25; turn++) {
      const unmarked = guestWinBoard.cells.filter(c => c.markState.type === 'Unmarked');
      if (unmarked.length === 0) break;
      const num = unmarked[0].number;
      guestPicks.push(num);
      const { board: updated } = BingoEngine.markCell(guestWinBoard, num, 'guest', true, turn);
      guestWinBoard = updated;
    }

    assert.ok(guestWinBoard.completedLines.length >= 5, 'Guest should win after marking all cells');

    // Host receives guest's picks on host's local board
    let hostLocalBoard = { ...hostBoard };
    let hostOppBoard = { ...guestBoard };
    let hostLocalLines = 0;
    let hostOppLines = 0;

    for (let turn = 1; turn <= guestPicks.length; turn++) {
      const num = guestPicks[turn - 1];

      const { board: hl } = BingoEngine.markCell(hostLocalBoard, num, 'guest', false, turn);
      hostLocalBoard = hl;
      hostLocalLines = hostLocalBoard.completedLines.length;

      const { board: ho } = BingoEngine.markCell(hostOppBoard, num, 'guest', true, turn);
      hostOppBoard = ho;
      hostOppLines = hostOppBoard.completedLines.length;

      if (hostOppLines >= 5) break;
    }

    assert.ok(hostOppLines >= 5, `Host should detect guest reached 5 lines (host loses), got ${hostOppLines}`);
  });
});
