import { RoomMessagePacket } from '../types/models';

/**
 * High-speed Micro-Payload Codec for multiplayer Bingo moves:
 * Matches Android FastPacketCodec.kt byte-for-byte to ensure seamless cross-play.
 */
export const FastPacketCodec = {
  encode(packet: RoomMessagePacket): string {
    switch (packet.type) {
      case 'PICK_NUMBER': {
        const historyStr = (packet.pickedHistory || []).join(',');
        if (packet.senderInstanceId) {
          return `P|${packet.number ?? 0}|${packet.playerId}|${packet.turnNumber ?? 0}|${packet.currentTurnPlayerId ?? ''}|${historyStr}|${packet.seed ?? 0}|${packet.senderInstanceId}`;
        } else if (packet.seed && packet.seed !== 0) {
          return `P|${packet.number ?? 0}|${packet.playerId}|${packet.turnNumber ?? 0}|${packet.currentTurnPlayerId ?? ''}|${historyStr}|${packet.seed}`;
        } else {
          return `P|${packet.number ?? 0}|${packet.playerId}|${packet.turnNumber ?? 0}|${packet.currentTurnPlayerId ?? ''}|${historyStr}`;
        }
      }

      case 'TURN_TIMEOUT': {
        const historyStr = (packet.pickedHistory || []).join(',');
        if (packet.senderInstanceId) {
          return `T|${packet.playerId}|${packet.turnNumber ?? 0}|${packet.currentTurnPlayerId ?? ''}|${historyStr}|${packet.seed ?? 0}|${packet.senderInstanceId}`;
        } else if (packet.seed && packet.seed !== 0) {
          return `T|${packet.playerId}|${packet.turnNumber ?? 0}|${packet.currentTurnPlayerId ?? ''}|${historyStr}|${packet.seed}`;
        } else {
          return `T|${packet.playerId}|${packet.turnNumber ?? 0}|${packet.currentTurnPlayerId ?? ''}|${historyStr}`;
        }
      }

      case 'BOARD_READY': {
        const boardStr = (packet.pickedHistory || []).join(',');
        if (packet.senderInstanceId) {
          return `B|${packet.playerId}|${packet.seed ?? 0}|${boardStr}|${packet.senderInstanceId}`;
        } else {
          return `B|${packet.playerId}|${packet.seed ?? 0}|${boardStr}`;
        }
      }

      case 'PING':
        return `G|${packet.playerId}|${packet.pingTimestamp ?? Date.now()}`;

      case 'PONG':
        return `O|${packet.playerId}|${packet.pingTimestamp ?? Date.now()}`;

      case 'READY_STATUS': {
        if (packet.senderInstanceId) {
          return `R|${packet.playerId}|${packet.readyStatus ?? 'NOT_READY'}|${packet.username ?? ''}|${packet.displayName ?? ''}|${packet.readyVersion ?? 0}|${packet.senderInstanceId}`;
        } else if (packet.readyVersion && packet.readyVersion > 0) {
          return `R|${packet.playerId}|${packet.readyStatus ?? 'NOT_READY'}|${packet.username ?? ''}|${packet.displayName ?? ''}|${packet.readyVersion}`;
        } else {
          return `R|${packet.playerId}|${packet.readyStatus ?? 'NOT_READY'}|${packet.username ?? ''}|${packet.displayName ?? ''}`;
        }
      }

      case 'KICK_PLAYER':
        return `K|${packet.targetPlayerId ?? ''}|${packet.playerId}`;

      case 'HEARTBEAT': {
        if (packet.players && packet.players.length > 0) {
          return JSON.stringify(packet);
        } else {
          return `H|${packet.playerId}|${packet.displayName ?? ''}|${packet.isHost ? '1' : '0'}|${packet.timestamp ?? Date.now()}|${packet.username ?? ''}|${packet.avatarUrl ?? ''}|${packet.readyStatus ?? 'READY'}|${packet.readyVersion ?? 0}`;
        }
      }

      default:
        return JSON.stringify(packet);
    }
  },

  decode(payload: string): RoomMessagePacket {
    const trimmed = payload.trim();
    try {
      if (trimmed.startsWith('P|')) {
        const parts = trimmed.split('|');
        const number = parseInt(parts[1] || '0', 10);
        const playerId = parts[2] || '';
        const turnNumber = parseInt(parts[3] || '0', 10);
        const currentTurnId = parts[4] || '';
        const historyRaw = parts[5] || '';
        const seed = parseInt(parts[6] || '0', 10);
        const senderInstanceId = parts[7] || '';
        const history = historyRaw ? historyRaw.split(',').map(n => parseInt(n, 10)).filter(n => !isNaN(n)) : [];

        return {
          type: 'PICK_NUMBER',
          number,
          playerId,
          turnNumber,
          currentTurnPlayerId: currentTurnId,
          pickedHistory: history,
          seed,
          senderInstanceId
        };
      }

      if (trimmed.startsWith('T|')) {
        const parts = trimmed.split('|');
        const playerId = parts[1] || '';
        const turnNumber = parseInt(parts[2] || '0', 10);
        const currentTurnId = parts[3] || '';
        const historyRaw = parts[4] || '';
        const seed = parseInt(parts[5] || '0', 10);
        const senderInstanceId = parts[6] || '';
        const history = historyRaw ? historyRaw.split(',').map(n => parseInt(n, 10)).filter(n => !isNaN(n)) : [];

        return {
          type: 'TURN_TIMEOUT',
          number: -1,
          playerId,
          turnNumber,
          currentTurnPlayerId: currentTurnId,
          pickedHistory: history,
          seed,
          senderInstanceId
        };
      }

      if (trimmed.startsWith('B|')) {
        const parts = trimmed.split('|');
        const playerId = parts[1] || '';
        const seed = parseInt(parts[2] || '0', 10);
        const boardRaw = parts[3] || '';
        const senderInstanceId = parts[4] || '';
        const boardNumbers = boardRaw ? boardRaw.split(',').map(n => parseInt(n, 10)).filter(n => !isNaN(n)) : [];

        return {
          type: 'BOARD_READY',
          playerId,
          seed,
          pickedHistory: boardNumbers,
          senderInstanceId
        };
      }

      if (trimmed.startsWith('G|')) {
        const parts = trimmed.split('|');
        return {
          type: 'PING',
          playerId: parts[1] || '',
          pingTimestamp: parseInt(parts[2] || '0', 10)
        };
      }

      if (trimmed.startsWith('O|')) {
        const parts = trimmed.split('|');
        return {
          type: 'PONG',
          playerId: parts[1] || '',
          pingTimestamp: parseInt(parts[2] || '0', 10)
        };
      }

      if (trimmed.startsWith('R|')) {
        const parts = trimmed.split('|');
        return {
          type: 'READY_STATUS',
          playerId: parts[1] || '',
          readyStatus: parts[2] || 'NOT_READY',
          username: parts[3] || '',
          displayName: parts[4] || '',
          readyVersion: parseInt(parts[5] || '0', 10),
          senderInstanceId: parts[6] || ''
        };
      }

      if (trimmed.startsWith('K|')) {
        const parts = trimmed.split('|');
        return {
          type: 'KICK_PLAYER',
          targetPlayerId: parts[1] || '',
          playerId: parts[2] || ''
        };
      }

      if (trimmed.startsWith('S|')) {
        const parts = trimmed.split('|');
        return {
          type: 'START_GAME',
          playerId: parts[1] || '',
          boardSize: parseInt(parts[2] || '5', 10),
          seed: parseInt(parts[3] || '0', 10),
          isManualBoard: parts[4] === '1'
        };
      }

      if (trimmed.startsWith('A|')) {
        const parts = trimmed.split('|');
        return {
          type: 'PLAY_AGAIN',
          playerId: parts[1] || '',
          boardSize: parseInt(parts[2] || '5', 10),
          seed: parseInt(parts[3] || '0', 10),
          isManualBoard: parts[4] === '1'
        };
      }

      if (trimmed.startsWith('H|')) {
        const parts = trimmed.split('|');
        const isHost = parts[3] === '1';
        return {
          type: 'HEARTBEAT',
          playerId: parts[1] || '',
          displayName: parts[2] || '',
          isHost,
          timestamp: parseInt(parts[4] || '0', 10),
          username: parts[5] || parts[2] || '',
          avatarUrl: parts[6] || null,
          readyStatus: parts[7] || (isHost ? 'READY' : 'NOT_READY'),
          readyVersion: parseInt(parts[8] || '0', 10)
        };
      }

      return JSON.parse(trimmed);
    } catch {
      return JSON.parse(trimmed);
    }
  }
};
