import { Player, CloudUserDataBackup, MatchRecord } from '../types/models';
import { roomSync } from './mqttSync';

export const KEYVALUE_API_URL = 'https://keyvalue.immanuel.co/api/KeyVal';
export const KEYVALUE_APP_KEY = '2464j24f';

export interface OnlineRoomSession {
  roomCode: string;
  hostId: string;
  hostUsername: string;
  hostDisplayName: string;
  hostAvatarUrl: string | null;
  status: 'WAITING' | 'PLAYING' | 'CLOSED';
  boardSize: number;
  createdAt: number;
  lastHeartbeat: number;
  players: Player[];
  currentSeed?: number;
  isManualBoard?: boolean;
}

export interface PlayerRegistryEntry {
  username: string;
  uid: string;
  displayName: string;
  avatarUrl: string | null;
  gamesPlayed: number;
  gamesWon: number;
  currentStreak: number;
  bestStreak?: number;
  level: number;
  lastSeenTimestamp: number;
  appVersion?: string;
  appVersionCode?: number;
}

export interface GameInvite {
  fromUsername: string;
  fromDisplayName: string;
  fromAvatarUrl: string | null;
  roomCode: string;
  timestamp: number;
}

function safeBase64Encode(raw: string): string {
  let b64: string;
  try {
    b64 = btoa(unescape(encodeURIComponent(raw)));
  } catch {
    b64 = btoa(raw);
  }
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export async function safeBase64EncodeAsync(raw: string): Promise<string> {
  try {
    if (typeof CompressionStream !== 'undefined') {
      const stream = new Blob([new TextEncoder().encode(raw)]).stream().pipeThrough(new CompressionStream('gzip'));
      const buffer = await new Response(stream).arrayBuffer();
      const bytes = new Uint8Array(buffer);
      let binary = '';
      for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
      const b64 = btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
      return 'GZ:' + b64;
    }
  } catch (e) {
    console.warn('Failed to compress with gzip:', e);
  }
  return safeBase64Encode(raw);
}

async function safeBase64DecodeAsync(rawStr: string): Promise<string> {
  const clean = rawStr.trim().replace(/^"|"$/g, '');
  if (clean.startsWith('GZ:')) {
    try {
      const rawB64 = clean.slice(3).replace(/-/g, '+').replace(/_/g, '/');
      const binStr = atob(rawB64);
      const bytes = new Uint8Array(binStr.length);
      for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
      const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
      return await new Response(stream).text();
    } catch (e) {
      console.warn('Failed to decompress GZ:', e);
    }
  }

  try {
    let unpadded = clean.replace(/-/g, '+').replace(/_/g, '/');
    while (unpadded.length % 4 !== 0) {
      unpadded += '=';
    }
    return decodeURIComponent(escape(atob(unpadded)));
  } catch {
    try {
      let unpadded = clean.replace(/-/g, '+').replace(/_/g, '/');
      while (unpadded.length % 4 !== 0) {
        unpadded += '=';
      }
      return atob(unpadded);
    } catch {
      return clean;
    }
  }
}

export class CloudRegistry {
  // ── KEYVALUE HELPER METHODS ──

  public static async setKeyValue(key: string, value: string): Promise<boolean> {
    try {
      const encKey = encodeURIComponent(key.trim());
      const encVal = encodeURIComponent(value.trim());
      const url = `${KEYVALUE_API_URL}/UpdateValue/${KEYVALUE_APP_KEY}/${encKey}?value=${encVal}`;
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 3500);
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: '',
        signal: controller.signal
      });
      clearTimeout(timeoutId);
      return res.ok;
    } catch (e) {
      console.warn('setKeyValue error:', e);
      return false;
    }
  }

  public static async getKeyValue(key: string): Promise<string | null> {
    try {
      const encKey = encodeURIComponent(key.trim());
      const url = `${KEYVALUE_API_URL}/GetValue/${KEYVALUE_APP_KEY}/${encKey}`;
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 3500);
      const res = await fetch(url, { signal: controller.signal });
      clearTimeout(timeoutId);
      if (!res.ok) return null;
      const text = await res.text();
      const clean = text.trim().replace(/^"|"$/g, '');
      if (!clean || clean === 'null' || clean.toLowerCase().includes('error')) {
        return null;
      }
      return clean;
    } catch {
      return null;
    }
  }

  // ── ROOM MANAGEMENT ──

  /**
   * Registers a 6-character room code in KeyValue cloud storage AND publishes retained MQTT metadata.
   */
  public static async createRoom(roomCode: string, hostPlayer: Player, boardSize: number = 5): Promise<boolean> {
    const cleanCode = roomCode.trim().toUpperCase();
    if (cleanCode.length !== 6) return false;

    const safeHost: Player = {
      ...hostPlayer,
      avatarUrl: null,
      score: 0,
      completedLinesCount: 0,
      gamesPlayed: 0,
      gamesWon: 0,
      currentStreak: 0,
      isHost: true,
      lobbyReadyStatus: 'READY'
    };

    const session: OnlineRoomSession = {
      roomCode: cleanCode,
      hostId: safeHost.id,
      hostUsername: safeHost.username,
      hostDisplayName: safeHost.displayName,
      hostAvatarUrl: null,
      status: 'WAITING',
      boardSize,
      createdAt: Date.now(),
      lastHeartbeat: Date.now(),
      players: [safeHost]
    };

    const jsonStr = JSON.stringify(session);
    const b64 = await safeBase64EncodeAsync(jsonStr);

    // 1. Write to KeyValue store (so Android's getRoom sees it)
    await this.setKeyValue(`room_${cleanCode}`, b64);

    // 2. Publish to MQTT retained room_meta topic (so Android's getRoomMqtt sees it)
    roomSync.sendRetainedMeta(cleanCode, jsonStr).catch(() => {});

    return true;
  }

  /**
   * Validates room code on KeyValue store & MQTT retained topic.
   */
  public static async validateAndJoinRoom(
    roomCode: string,
    joiner: Player
  ): Promise<{ success: boolean; session?: OnlineRoomSession; message?: string }> {
    const cleanCode = roomCode.trim().toUpperCase();
    if (cleanCode.length !== 6) {
      return { success: false, message: 'Room code must be exactly 6 characters.' };
    }

    try {
      // 1. Check MQTT retained topic first (instant fallback)
      let session: OnlineRoomSession | null = null;
      try {
        session = await roomSync.getRoomMetaMqtt(cleanCode);
      } catch {}

      // 2. Check KeyValue store
      if (!session) {
        const raw = await this.getKeyValue(`room_${cleanCode}`);
        if (raw) {
          try {
            const jsonStr = await safeBase64DecodeAsync(raw);
            if (jsonStr.startsWith('{')) {
              session = JSON.parse(jsonStr);
            }
          } catch {}
        }
      }

      if (!session) {
        return { success: false, message: `No active room found for code ${cleanCode}.` };
      }

      if (session.status === 'CLOSED') {
        return { success: false, message: `Room ${cleanCode} has been closed by the host.` };
      }

      if (session.status === 'PLAYING') {
        return { success: false, message: `Match in room ${cleanCode} has already started.` };
      }

      const now = Date.now();
      if (now - session.lastHeartbeat > 600_000) {
        return { success: false, message: `Room ${cleanCode} has expired or the host has left.` };
      }

      // Add joiner to session
      const safeJoiner: Player = {
        ...joiner,
        avatarUrl: null,
        isHost: false,
        lobbyReadyStatus: 'NOT_READY',
        lastSeenTimestamp: now
      };

      const existingIdx = session.players.findIndex(
        p => p.id === safeJoiner.id || (p.username && p.username.toLowerCase() === safeJoiner.username.toLowerCase())
      );

      const updatedPlayers = [...session.players];
      if (existingIdx >= 0) {
        updatedPlayers[existingIdx] = safeJoiner;
      } else {
        if (updatedPlayers.length >= 8) {
          return { success: false, message: `Room ${cleanCode} is full (max 8 players).` };
        }
        updatedPlayers.push(safeJoiner);
      }

      const updatedSession: OnlineRoomSession = {
        ...session,
        players: updatedPlayers,
        lastHeartbeat: now
      };

      // Write updated session back
      const jsonStr = JSON.stringify(updatedSession);
      const b64 = await safeBase64EncodeAsync(jsonStr);
      this.setKeyValue(`room_${cleanCode}`, b64).catch(() => {});
      roomSync.sendRetainedMeta(cleanCode, jsonStr).catch(() => {});

      return { success: true, session: updatedSession };
    } catch (e: any) {
      return { success: false, message: e.message || 'Error validating room code.' };
    }
  }

  /**
   * Fetches latest room session from KeyValue cloud registry & MQTT retained topic.
   */
  public static async getRoom(roomCode: string): Promise<OnlineRoomSession | null> {
    const cleanCode = roomCode.trim().toUpperCase();
    if (cleanCode.length !== 6) return null;

    try {
      // 1. Try MQTT retained topic first (ultra-fast, < 100ms)
      const mqttSession = await roomSync.getRoomMetaMqtt(cleanCode);
      if (mqttSession && mqttSession.status !== 'CLOSED') {
        return mqttSession;
      }
    } catch {}

    try {
      // 2. Try KeyValue store
      const raw = await this.getKeyValue(`room_${cleanCode}`);
      if (raw) {
        const jsonStr = await safeBase64DecodeAsync(raw);
        if (jsonStr.startsWith('{')) {
          return JSON.parse(jsonStr) as OnlineRoomSession;
        }
      }
    } catch {}
    return null;
  }

  /**
   * Updates player ready status in KeyValue and MQTT retained room metadata.
   */
  public static async updateRoomReadyStatus(
    roomCode: string,
    playerId: string,
    readyStatus: 'READY' | 'NOT_READY'
  ): Promise<boolean> {
    const cleanCode = roomCode.trim().toUpperCase();
    if (cleanCode.length !== 6) return false;
    try {
      const session = await this.getRoom(cleanCode);
      if (!session) return false;
      let playerFound = false;
      const updatedPlayers = session.players.map(p => {
        if (p.id === playerId) {
          playerFound = true;
          return { ...p, lobbyReadyStatus: readyStatus, lastSeenTimestamp: Date.now() };
        }
        return p;
      });
      if (!playerFound) {
        updatedPlayers.push({
          id: playerId,
          displayName: 'Player',
          username: playerId,
          isHost: false,
          avatarUrl: null,
          score: 0,
          completedLinesCount: 0,
          gamesPlayed: 0,
          gamesWon: 0,
          currentStreak: 0,
          level: 1,
          lastSeenTimestamp: Date.now(),
          lobbyReadyStatus: readyStatus,
          readyVersion: 0
        });
      }
      const updatedSession: OnlineRoomSession = {
        ...session,
        players: updatedPlayers,
        lastHeartbeat: Date.now()
      };
      const jsonStr = JSON.stringify(updatedSession);
      const b64 = await safeBase64EncodeAsync(jsonStr);
      await this.setKeyValue(`room_${cleanCode}`, b64);
      roomSync.sendRetainedMeta(cleanCode, jsonStr).catch(() => {});
      return true;
    } catch {
      return false;
    }
  }

  // ── USERNAME REGISTRATION & GLOBAL DISCOVERY ──

  /**
   * Checks if a username is available globally.
   */
  public static async checkUsernameAvailable(username: string, currentUid: string): Promise<boolean> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (clean.length < 3 || clean.length > 20 || !/^[a-z0-9_]+$/.test(clean)) {
      return false;
    }

    try {
      const raw = await this.getKeyValue(`reg_${clean}`);
      if (!raw) return true;

      const jsonStr = await safeBase64DecodeAsync(raw);
      if (jsonStr.startsWith('{')) {
        const entry: PlayerRegistryEntry = JSON.parse(jsonStr);
        return entry.uid === currentUid;
      }
      return true;
    } catch {
      return true;
    }
  }

  /**
   * Claims and registers username in KeyValue, user_directory index, ExtendsClass, and MQTT retained topic
   * so Android users, peer web clients, and the Admin dashboard can find, inspect, and track it in real-time!
   */
  public static async claimAndRegisterUser(player: Player): Promise<boolean> {
    const clean = player.username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return false;

    const now = Date.now();
    const entry: PlayerRegistryEntry = {
      username: clean,
      uid: player.id,
      displayName: player.displayName || clean,
      avatarUrl: player.avatarUrl || null,
      gamesPlayed: player.gamesPlayed || 0,
      gamesWon: player.gamesWon || 0,
      currentStreak: player.currentStreak || 0,
      bestStreak: player.currentStreak || 0,
      level: player.level || 1,
      lastSeenTimestamp: now,
      appVersion: '1.3.2',
      appVersionCode: 38
    };

    const jsonStr = JSON.stringify(entry);
    // Standard URL-safe Base64 matching Android AccountSessionManager verbatim
    const b64 = safeBase64Encode(jsonStr);

    try {
      // 1. Write to reg_{username} in KeyValue
      await this.setKeyValue(`reg_${clean}`, b64);

      // 2. Maintain authoritative user_directory index in KeyValue
      try {
        const rawDir = await this.getKeyValue('user_directory');
        const dirUsers = new Set<string>();
        if (rawDir) {
          rawDir.split(',').forEach(u => {
            const c = u.trim().toLowerCase().replace(/^@/, '');
            if (c) dirUsers.add(c);
          });
        }
        if (!dirUsers.has(clean)) {
          dirUsers.add(clean);
          const sorted = Array.from(dirUsers).sort().join(',');
          await this.setKeyValue('user_directory', sorted);
        }
      } catch (dirErr) {
        console.warn('Error updating user_directory in KeyValue:', dirErr);
      }

      // 3. Maintain ExtendsClass JSON Bin and link user_{clean} & gid_{gid}
      try {
        const targetGid = player.googleId || (player.id.startsWith('google_') ? player.id.replace(/^google_/, '') : null);
        let binId = await this.getKeyValue(`user_${clean}`);
        if (!binId && targetGid) {
          binId = await this.getKeyValue(`gid_${targetGid}`);
        }

        const backupData: CloudUserDataBackup = {
          profile: {
            uid: player.id,
            username: clean,
            displayName: player.displayName || clean,
            email: player.email || null,
            avatarUrl: player.avatarUrl || null,
            provider: player.authProvider || 'GOOGLE',
            gamesPlayed: player.gamesPlayed || 0,
            gamesWon: player.gamesWon || 0,
            currentStreak: player.currentStreak || 0,
            bestStreak: player.currentStreak || 0,
            level: player.level || 1,
            xp: 0
          },
          matchHistory: player.matchHistory || [],
          lastBackupTimestamp: now
        };
        const backupJson = JSON.stringify(backupData);

        if (!binId || binId.startsWith('{') || binId.length >= 50) {
          const postRes = await fetch('https://extendsclass.com/api/json-storage/bin', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: backupJson
          });
          if (postRes.ok) {
            const resJson = await postRes.json();
            if (resJson && resJson.id) {
              binId = resJson.id;
            }
          }
        }

        if (binId && !binId.startsWith('{')) {
          await this.setKeyValue(`user_${clean}`, binId);
          if (targetGid) {
            await this.setKeyValue(`gid_${targetGid}`, binId);
          }
          if (player.email && player.email.includes('@')) {
            await this.setKeyValue(`gid_${player.email.toLowerCase().trim()}`, binId);
          }
        }
      } catch (binErr) {
        console.warn('Error syncing ExtendsClass bin for user:', binErr);
      }

      // 4. Publish to MQTT retained topic bingo/v3/registry/{username}
      await roomSync.sendRetainedUserRegistry(clean, jsonStr);

      // 5. Publish online presence (KeyValue pres_{clean} + MQTT retained presence)
      await this.publishPresence(clean, 'ONLINE');

      return true;
    } catch (e) {
      console.warn('claimAndRegisterUser error:', e);
      return false;
    }
  }

  /**
   * Searches for a player globally by username (matches Android AccountSessionManager.searchPlayerByUsername).
   */
  public static async searchPlayerByUsername(username: string): Promise<PlayerRegistryEntry | null> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return null;

    try {
      const raw = await this.getKeyValue(`reg_${clean}`);
      if (!raw) return null;

      const jsonStr = await safeBase64DecodeAsync(raw);
      if (jsonStr.startsWith('{')) {
        const entry: PlayerRegistryEntry = JSON.parse(jsonStr);

        // Fetch fresh presence
        const presence = await this.fetchPresence(clean);
        if (presence) {
          entry.lastSeenTimestamp = presence.timestamp;
        }

        return entry;
      }
    } catch (e) {
      console.warn('searchPlayerByUsername error:', e);
    }
    return null;
  }

  // ── PRESENCE MANAGEMENT ──

  public static async publishPresence(username: string, status: 'ONLINE' | 'OFFLINE' = 'ONLINE'): Promise<void> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return;

    const now = Date.now();
    // 1. KeyValue presence: pres_{clean} = "ONLINE:{timestamp}:1.3.2:38" (matching Android so admin page shows version)
    this.setKeyValue(`pres_${clean}`, `${status}:${now}:1.3.2:38`).catch(() => {});

    // 2. MQTT retained presence: bingo/v3/presence/{clean}
    const payload = JSON.stringify({
      username: clean,
      status,
      timestamp: now,
      appVersion: '1.3.2',
      appVersionCode: 38
    });
    await roomSync.sendRetainedPresence(clean, payload);
  }

  public static async fetchPresence(username: string): Promise<{ status: string; timestamp: number; isOnline: boolean } | null> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return null;

    try {
      const raw = await this.getKeyValue(`pres_${clean}`);
      if (!raw || !raw.includes(':')) return null;

      const parts = raw.split(':');
      const status = parts[0] || 'OFFLINE';
      const timestamp = parseInt(parts[1], 10) || 0;
      const isOnline = status.toUpperCase() === 'ONLINE' && Date.now() - timestamp < 35_000;
      return { status, timestamp, isOnline };
    } catch {
      return null;
    }
  }

  // ── GAME INVITATIONS ──

  public static async sendInvite(targetUsername: string, invite: GameInvite): Promise<boolean> {
    const clean = targetUsername.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return false;

    try {
      // 1. MQTT real-time invite dispatch: bingo/v3/invites/{clean}
      await roomSync.sendMqttInvite(clean, JSON.stringify(invite));

      // 2. KeyValue fallback storage: inv_{clean}
      const existing = await this.fetchInvites(clean);
      const filtered = existing.filter(i => Date.now() - i.timestamp < 900_000);
      filtered.unshift(invite);

      const jsonStr = JSON.stringify(filtered);
      const b64 = await safeBase64EncodeAsync(jsonStr);
      await this.setKeyValue(`inv_${clean}`, b64);
      return true;
    } catch {
      return false;
    }
  }

  public static async fetchInvites(username: string): Promise<GameInvite[]> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return [];

    try {
      const raw = await this.getKeyValue(`inv_${clean}`);
      if (!raw) return [];
      const jsonStr = await safeBase64DecodeAsync(raw);
      if (jsonStr.startsWith('[')) {
        const list: GameInvite[] = JSON.parse(jsonStr);
        return list.filter(i => Date.now() - i.timestamp < 900_000 && i.roomCode);
      }
    } catch {}
    return [];
  }

  // ── GOOGLE USER BACKUP & ACCOUNT RESTORE (MATCHING ANDROID AccountSessionManager) ──

  /**
   * Fetches user profile, match history, and stats from persistent cloud storage (ExtendsClass + KeyVal).
   */
  public static async fetchUserDataBackup(googleId: string): Promise<CloudUserDataBackup | null> {
    const gid = googleId.trim();
    if (!gid) return null;

    try {
      let binId = await this.getKeyValue(`gid_${gid}`);
      if (!binId) return null;

      // If binId contains raw JSON
      if (binId.startsWith('{')) {
        return JSON.parse(binId);
      }

      // Fetch from ExtendsClass JSON storage
      const res = await fetch(`https://extendsclass.com/api/json-storage/bin/${binId}`);
      if (!res.ok) return null;
      const data = await res.json();
      if (data && data.profile) {
        return data as CloudUserDataBackup;
      }
    } catch (e) {
      console.warn('fetchUserDataBackup error for ' + gid, e);
    }
    return null;
  }

  /**
   * Fetches user profile backup by unique username from persistent cloud storage.
   */
  public static async fetchUserBackupByUsername(username: string): Promise<CloudUserDataBackup | null> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return null;

    try {
      const binId = await this.getKeyValue(`user_${clean}`);
      if (!binId) return null;

      if (binId.startsWith('{')) {
        return JSON.parse(binId);
      }

      const res = await fetch(`https://extendsclass.com/api/json-storage/bin/${binId}`);
      if (!res.ok) return null;
      const data = await res.json();
      if (data && data.profile) {
        return data as CloudUserDataBackup;
      }
    } catch (e) {
      console.warn('fetchUserBackupByUsername error for ' + clean, e);
    }
    return null;
  }

  /**
   * Saves or updates full player profile, match history, and stats to ExtendsClass + KeyVal.
   */
  public static async saveUserDataBackup(googleId: string, backup: CloudUserDataBackup): Promise<boolean> {
    const gid = googleId.trim();
    if (!gid) return false;

    try {
      const cleanUser = backup.profile.username.trim().toLowerCase().replace(/^@/, '');
      const jsonStr = JSON.stringify(backup);

      let binId = await this.getKeyValue(`gid_${gid}`);
      if (!binId && cleanUser) {
        binId = await this.getKeyValue(`user_${cleanUser}`);
      }
      let success = false;

      if (binId && !binId.startsWith('{') && binId.length < 50) {
        // Update existing bin
        const updateRes = await fetch(`https://extendsclass.com/api/json-storage/bin/${binId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: jsonStr
        });
        success = updateRes.ok;
      }

      if (!success) {
        // Create new bin
        const createRes = await fetch('https://extendsclass.com/api/json-storage/bin', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: jsonStr
        });
        if (createRes.ok) {
          const respData = await createRes.json();
          binId = respData.id;
          if (binId) {
            await this.setKeyValue(`gid_${gid}`, binId);
            success = true;
          }
        }
      }

      if (cleanUser && binId) {
        await this.setKeyValue(`user_${cleanUser}`, binId);
      }

      // Also register universally in reg_{username}
      const player: Player = {
        id: backup.profile.uid,
        username: backup.profile.username,
        displayName: backup.profile.displayName,
        avatarUrl: backup.profile.avatarUrl || '🧑',
        score: 0,
        completedLinesCount: 0,
        gamesPlayed: backup.profile.gamesPlayed,
        gamesWon: backup.profile.gamesWon,
        currentStreak: backup.profile.currentStreak,
        level: backup.profile.level,
        lastSeenTimestamp: Date.now(),
        lobbyReadyStatus: 'NOT_READY',
        readyVersion: 0,
        isHost: false,
        email: backup.profile.email,
        googleId: gid,
        matchHistory: backup.matchHistory || []
      };
      await this.claimAndRegisterUser(player);

      return success;
    } catch (e) {
      console.warn('saveUserDataBackup error for ' + gid, e);
      return false;
    }
  }

  // ── FRIENDS LIST CLOUD SYNC (MATCHING ANDROID FriendRequestManager) ──

  /**
   * Fetches real friends list from KeyVal storage (friends_{cleanUsername}) matching Android.
   */
  public static async fetchCloudFriends(username: string): Promise<PlayerRegistryEntry[]> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return [];

    try {
      const raw = await this.getKeyValue(`friends_${clean}`);
      if (!raw) return [];

      let jsonStr = raw;
      try {
        jsonStr = await safeBase64DecodeAsync(raw);
      } catch {}

      if (jsonStr.startsWith('[')) {
        const rawList = JSON.parse(jsonStr) as any[];
        return rawList.map(item => ({
          username: (item.username || '').toLowerCase().replace(/^@/, ''),
          uid: item.uid || item.username || '',
          displayName: item.displayName || item.username || 'Friend',
          avatarUrl: item.avatarUrl || '🧑',
          gamesPlayed: item.gamesPlayed || 0,
          gamesWon: item.gamesWon || 0,
          currentStreak: item.currentStreak || 0,
          level: item.level || 1,
          lastSeenTimestamp: item.lastSeenTimestamp || Date.now()
        }));
      }
    } catch (e) {
      console.warn('fetchCloudFriends error for ' + clean, e);
    }
    return [];
  }

  /**
   * Saves real friends list to KeyVal storage (friends_{cleanUsername}) matching Android format verbatim.
   */
  public static async saveCloudFriends(username: string, friends: PlayerRegistryEntry[]): Promise<boolean> {
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return false;

    try {
      const androidFormat = friends.map(f => ({
        uid: f.uid,
        username: f.username,
        displayName: f.displayName,
        avatarUrl: f.avatarUrl,
        isOnline: f.lastSeenTimestamp ? Date.now() - f.lastSeenTimestamp < 120_000 : false,
        lastSeenTimestamp: f.lastSeenTimestamp || Date.now()
      }));

      const jsonStr = JSON.stringify(androidFormat);
      // Plain URL-safe Base64 matching Android FriendRequestManager.encodeBase64Url
      const b64 = safeBase64Encode(jsonStr);
      return await this.setKeyValue(`friends_${clean}`, b64);
    } catch (e) {
      console.warn('saveCloudFriends error for ' + clean, e);
      return false;
    }
  }

  /**
   * Adds friend both to local and to cloud list (friends_{cleanUsername}).
   */
  public static async addFriendToCloudList(myUsername: string, friend: PlayerRegistryEntry): Promise<PlayerRegistryEntry[]> {
    const clean = myUsername.trim().toLowerCase().replace(/^@/, '');
    const current = await this.fetchCloudFriends(clean);
    const filtered = current.filter(f => f.username !== friend.username && f.uid !== friend.uid);
    const updated = [friend, ...filtered];
    await this.saveCloudFriends(clean, updated);
    return updated;
  }

  /**
   * Removes friend from cloud list (friends_{cleanUsername}) matching Android FriendsRepository.removeFriend.
   */
  public static async removeFriendFromCloudList(myUsername: string, targetUsernameOrUid: string): Promise<PlayerRegistryEntry[]> {
    const clean = myUsername.trim().toLowerCase().replace(/^@/, '');
    const cleanTarget = targetUsernameOrUid.trim().toLowerCase().replace(/^@/, '');
    const current = await this.fetchCloudFriends(clean);
    const updated = current.filter(f => f.username !== cleanTarget && f.uid !== targetUsernameOrUid);
    await this.saveCloudFriends(clean, updated);
    return updated;
  }
}
