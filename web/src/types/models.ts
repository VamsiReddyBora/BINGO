export interface Player {
  id: string;
  displayName: string;
  username: string;
  isHost: boolean;
  isAi?: boolean;
  avatarUrl?: string | null;
  score: number;
  completedLinesCount: number;
  gamesPlayed: number;
  gamesWon: number;
  currentStreak: number;
  level: number;
  lastSeenTimestamp: number;
  lobbyReadyStatus: 'READY' | 'NOT_READY' | 'IN_GAME';
  readyVersion: number;
}

export interface RoomMessagePacket {
  type: string; // "JOIN", "HEARTBEAT", "START_GAME", "PICK_NUMBER", "TURN_TIMEOUT", "BOARD_READY", "PING", "PONG", "READY_STATUS", "KICK_PLAYER", "EMOTE", "CHAT_PHRASE", "CHAT_MESSAGE", "BINGO_CLAIMED", "PLAY_AGAIN", "LEAVE", "SURRENDER"
  playerId: string;
  displayName?: string;
  username?: string;
  isHost?: boolean;
  avatarUrl?: string | null;
  gamesPlayed?: number;
  gamesWon?: number;
  currentStreak?: number;
  level?: number;
  boardSize?: number;
  number?: number;
  turnNumber?: number;
  seed?: number;
  pickedHistory?: number[];
  currentTurnPlayerId?: string;
  pingTimestamp?: number;
  players?: Player[];
  timestamp?: number;
  readyStatus?: string;
  targetPlayerId?: string;
  readyVersion?: number;
  isManualBoard?: boolean;
  senderInstanceId?: string;
  payload?: string;
}

export type CellMarkState =
  | { type: 'Unmarked' }
  | { type: 'Marked'; pickedByPlayerId: string; isOwnPick: boolean; turnNumber: number };

export interface Cell {
  row: number;
  col: number;
  number: number;
  markState: CellMarkState;
  isPartOfCompletedLine: boolean;
  isRecentPick: boolean;
}

export interface LineCoordinate {
  type: 'ROW' | 'COLUMN' | 'MAIN_DIAGONAL' | 'ANTI_DIAGONAL';
  index: number;
}

export interface Board {
  size: number;
  cells: Cell[];
  completedLines: LineCoordinate[];
  targetLines: number;
}

export interface RecentPick {
  number: number;
  pickedByPlayerId: string;
  turnNumber: number;
  timestamp: number;
}

export interface InGameChatMessage {
  id: number;
  text: string;
  isSelf: boolean;
  senderName?: string;
  timestamp: number;
}

export interface FloatingEmoteItem {
  id: string;
  emoji: string;
  startXRatio: number; // 0.08 to 0.86
  isSelf: boolean;
  senderName?: string;
  scaleMultiplier: number; // 1.0f up to 2.85f
  createdAt: number;
}

export type ScreenType = 'MAIN_MENU' | 'LOBBY' | 'GAME';
