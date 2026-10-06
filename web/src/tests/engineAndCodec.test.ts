import { describe, it } from 'node:test';
import assert from 'node:assert';
import { FastPacketCodec } from '../network/codec';
import { BingoEngine } from '../engine/bingoEngine';
import { RoomMessagePacket } from '../types/models';

describe('Web Bingo Engine & FastPacketCodec Tests', () => {
  it('FastPacketCodec correctly encodes and decodes PICK_NUMBER micro-string', () => {
    const packet: RoomMessagePacket = {
      type: 'PICK_NUMBER',
      number: 17,
      playerId: 'player_web_123',
      turnNumber: 3,
      currentTurnPlayerId: 'player_android_456',
      pickedHistory: [4, 12, 17],
      seed: 987654321,
      senderInstanceId: 'inst_abc'
    };

    const encoded = FastPacketCodec.encode(packet);
    assert.strictEqual(encoded, 'P|17|player_web_123|3|player_android_456|4,12,17|987654321|inst_abc');

    const decoded = FastPacketCodec.decode(encoded);
    assert.strictEqual(decoded.type, 'PICK_NUMBER');
    assert.strictEqual(decoded.number, 17);
    assert.strictEqual(decoded.playerId, 'player_web_123');
    assert.strictEqual(decoded.turnNumber, 3);
    assert.strictEqual(decoded.currentTurnPlayerId, 'player_android_456');
    assert.deepStrictEqual(decoded.pickedHistory, [4, 12, 17]);
    assert.strictEqual(decoded.seed, 987654321);
    assert.strictEqual(decoded.senderInstanceId, 'inst_abc');
  });

  it('FastPacketCodec correctly encodes and decodes scaled EMOTE for big emojis', () => {
    const giantEmote: RoomMessagePacket = {
      type: 'EMOTE',
      playerId: 'player_1',
      displayName: '🔥',
      number: 285 // scale 2.85x
    };

    const encoded = FastPacketCodec.encode(giantEmote);
    const decoded = FastPacketCodec.decode(encoded);

    assert.strictEqual(decoded.type, 'EMOTE');
    assert.strictEqual(decoded.displayName, '🔥');
    assert.strictEqual(decoded.number, 285);
    const scale = (decoded.number || 0) / 100;
    assert.strictEqual(scale, 2.85);
  });

  it('BingoEngine generates valid 5x5 board with numbers 1..25 and no duplicates', () => {
    const board = BingoEngine.generateBoard(5);
    assert.strictEqual(board.size, 5);
    assert.strictEqual(board.cells.length, 25);

    const numbers = board.cells.map(c => c.number);
    const uniqueNumbers = new Set(numbers);
    assert.strictEqual(uniqueNumbers.size, 25);

    for (let i = 1; i <= 25; i++) {
      assert.ok(uniqueNumbers.has(i), `Board must contain number ${i}`);
    }
  });

  it('BingoEngine marks cells and correctly detects completed rows, cols, and diagonals', () => {
    let board = BingoEngine.generateBoard(5, 12345);

    // Get all numbers in the first row
    const row0Numbers = board.cells.filter(c => c.row === 0).map(c => c.number);
    assert.strictEqual(row0Numbers.length, 5);

    // Pick 4 of them
    for (let i = 0; i < 4; i++) {
      const res = BingoEngine.markCell(board, row0Numbers[i], 'player_1', true, i + 1);
      board = res.board;
      assert.strictEqual(board.completedLines.length, 0);
    }

    // Pick 5th to complete row 0
    const res5 = BingoEngine.markCell(board, row0Numbers[4], 'player_1', true, 5);
    board = res5.board;
    assert.strictEqual(board.completedLines.length, 1);
    assert.strictEqual(res5.newLinesCompleted, 1);
    assert.strictEqual(board.completedLines[0].type, 'ROW');
    assert.strictEqual(board.completedLines[0].index, 0);
  });

  it('Cross-platform URL-safe Base64 and GZ decode handles Android format correctly', async () => {
    const session = {
      roomCode: '654321',
      hostId: 'android_host_1',
      hostDisplayName: 'AndroidHost',
      status: 'WAITING',
      boardSize: 5
    };
    const jsonStr = JSON.stringify(session);

    // Simulate URL-safe base64 encoding from web
    const b64 = Buffer.from(jsonStr, 'utf8').toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    assert.ok(!b64.includes('+'));
    assert.ok(!b64.includes('/'));
    assert.ok(!b64.includes('='));

    // Verify decoding
    const unpadded = b64.replace(/-/g, '+').replace(/_/g, '/');
    const decoded = Buffer.from(unpadded, 'base64').toString('utf8');
    assert.strictEqual(decoded, jsonStr);
  });

  it('BingoEngine.resolvePlayerBoardSeed matches Android LobbyLifecycleEngine bit-for-bit', () => {
    const baseSeed = 123456789;
    const vampireSeed = BingoEngine.resolvePlayerBoardSeed(baseSeed, { username: 'vampire', id: 'google_123', isHost: true }, 0);
    const bobSeed = BingoEngine.resolvePlayerBoardSeed(baseSeed, { username: 'bob', id: 'google_456', isHost: false }, 1);

    assert.strictEqual(vampireSeed.toString(), '-5513532478955952321');
    assert.strictEqual(bobSeed.toString(), '-3351804022793641929');
    assert.notStrictEqual(vampireSeed, bobSeed);
  });
});
