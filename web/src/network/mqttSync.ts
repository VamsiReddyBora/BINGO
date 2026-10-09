import mqtt, { MqttClient } from 'mqtt';
import { Player, RoomMessagePacket } from '../types/models';
import { FastPacketCodec } from './codec';
import { NetworkPingMonitor } from './pingMonitor';

export const MQTT_WS_URL = 'wss://p0812f88.ala.asia-southeast1.emqxsl.com:8084/mqtt';
export const MQTT_USERNAME = 'Bora';
export const MQTT_PASSWORD = 'bora7989';

export class MqttRoomManager {
  private client: MqttClient | null = null;
  private currentRoomCode: string | null = null;
  private localPlayer: Player | null = null;
  private playerRegistry: Map<string, Player> = new Map();
  public readonly instanceId: string = Math.random().toString(36).substring(2, 10);
  
  private heartbeatTimer: any = null;
  private pingTimer: any = null;
  private livenessTimer: any = null;
  private globalPresenceTimer: any = null;
  
  public onPacketReceived: ((packet: RoomMessagePacket) => void) | null = null;
  public onPlayersChanged: ((players: Player[]) => void) | null = null;
  public onPingChanged: ((pingMs: number) => void) | null = null;
  public onConnectionChanged: ((connected: boolean) => void) | null = null;
  public onInviteReceived: ((invite: any) => void) | null = null;

  private packetListeners: Set<(packet: RoomMessagePacket) => void> = new Set();

  public addPacketListener(listener: (packet: RoomMessagePacket) => void): () => void {
    this.packetListeners.add(listener);
    return () => {
      this.packetListeners.delete(listener);
    };
  }

  private smoothedPing: number = 24;

  public initGlobalClient(player: Player) {
    if (this.client && this.client.connected) {
      this.subscribeGlobalTopics(player.username);
      return;
    }

    this.localPlayer = { ...player };
    const clientId = `bingo_web_${player.id.substring(0, 8)}_${Math.random().toString(36).substring(2, 6)}`;

    const cleanUname = player.username ? player.username.trim().toLowerCase().replace(/^@/, '') : '';
    const willPayload = JSON.stringify({
      status: 'OFFLINE',
      timestamp: Date.now(),
      appVersion: '1.3.3',
      appVersionCode: 39
    });

    try {
      this.client = mqtt.connect(MQTT_WS_URL, {
        clientId,
        username: MQTT_USERNAME,
        password: MQTT_PASSWORD,
        clean: true,
        reconnectPeriod: 2500,
        connectTimeout: 8000,
        keepalive: 30,
        will: cleanUname ? {
          topic: `bingo/v3/presence/${cleanUname}`,
          payload: willPayload,
          qos: 1,
          retain: true
        } : undefined
      });

      this.client.on('connect', () => {
        this.onConnectionChanged?.(true);
        if (this.localPlayer?.username) {
          this.subscribeGlobalTopics(this.localPlayer.username);
        }
        if (this.currentRoomCode) {
          this.subscribeToRoom(this.currentRoomCode);
          this.sendJoinPacket();
          setTimeout(() => this.sendJoinPacket(), 500);
          setTimeout(() => this.sendJoinPacket(), 1500);
        }
      });

      this.client.on('message', (topic, message) => {
        try {
          const payloadStr = message.toString();
          if (topic.startsWith('bingo/v3/invites/')) {
            const invite = JSON.parse(payloadStr);
            this.onInviteReceived?.(invite);
            return;
          }

          const packet = FastPacketCodec.decode(payloadStr);
          this.handleIncomingPacket(packet);
        } catch (e) {
          console.warn('Failed to parse incoming packet:', e);
        }
      });

      this.client.on('close', () => {
        this.onConnectionChanged?.(false);
      });

      this.startGlobalPresenceLoop();
    } catch (err) {
      console.error('Failed to create MQTT client:', err);
    }
  }

  private subscribeGlobalTopics(username: string) {
    if (!this.client || !this.client.connected) return;
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return;
    this.client.subscribe(`bingo/v3/invites/${clean}`, { qos: 1 });
  }

  public connect(roomCode: string, player: Player, initialPlayers: Player[] = []) {
    const cleanCode = roomCode.trim().toUpperCase();
    this.currentRoomCode = cleanCode;
    this.localPlayer = { ...player };

    this.playerRegistry.clear();
    this.playerRegistry.set(player.id, { ...player, lastSeenTimestamp: Date.now() });
    if (initialPlayers && initialPlayers.length > 0) {
      initialPlayers.forEach(p => {
        if (p && p.id && p.id !== player.id) {
          this.playerRegistry.set(p.id, { ...p, lastSeenTimestamp: Date.now() });
        }
      });
    }
    this.notifyPlayers();

    if (!this.client || !this.client.connected) {
      this.initGlobalClient(player);
    } else {
      this.subscribeToRoom(cleanCode);
      this.sendJoinPacket();
      setTimeout(() => this.sendJoinPacket(), 500);
      setTimeout(() => this.sendJoinPacket(), 1500);
    }

    this.startHeartbeatLoop();
    this.startPingLoop();
    this.startLivenessLoop();
  }

  private subscribeToRoom(code: string) {
    if (!this.client || !this.client.connected) return;
    const topic = `bingo/v3/room/${code}`;
    this.client.subscribe(topic, { qos: 1 });
  }

  public joinRoom(roomCode: string, player: Player, initialPlayers: Player[] = []) {
    this.connect(roomCode, player, initialPlayers);
  }

  public leaveRoom() {
    this.disconnect();
  }

  public async getRoomMetaMqtt(roomCode: string): Promise<any | null> {
    const cleanCode = roomCode.trim().toUpperCase();
    const client = await this.ensureConnected();
    if (!client || !client.connected) return null;

    return new Promise((resolve) => {
      const topic = `bingo/v3/room_meta/${cleanCode}`;
      let resolved = false;

      const timer = setTimeout(() => {
        if (!resolved) {
          resolved = true;
          try { client.unsubscribe(topic); } catch {}
          resolve(null);
        }
      }, 2000);

      const onMsg = (t: string, message: Buffer) => {
        if (t === topic && !resolved) {
          resolved = true;
          clearTimeout(timer);
          client.removeListener('message', onMsg);
          try { client.unsubscribe(topic); } catch {}
          try {
            const raw = message.toString().trim();
            if (raw && raw.startsWith('{')) {
              const session = JSON.parse(raw);
              if (session.status !== 'CLOSED') {
                return resolve(session);
              }
            }
          } catch {}
          resolve(null);
        }
      };

      client.on('message', onMsg);
      client.subscribe(topic, { qos: 1 }, (err) => {
        if (err && !resolved) {
          resolved = true;
          clearTimeout(timer);
          client.removeListener('message', onMsg);
          resolve(null);
        }
      });
    });
  }

  public sendStartGame(seed: number, starterId: string, players?: Player[]) {
    const participants = (players && players.length > 0) ? players : this.getPlayers();
    this.sendPacket({
      type: 'START_GAME',
      playerId: this.localPlayer?.id || starterId,
      seed,
      currentTurnPlayerId: starterId,
      boardSize: 5,
      players: participants
    });
  }

  public sendTurnTimeout(turnNumber: number, nextTurnId: string, pickedHistory: number[], seed: number = 0) {
    this.sendPacket({
      type: 'TURN_TIMEOUT',
      playerId: this.localPlayer?.id || '',
      turnNumber,
      currentTurnPlayerId: nextTurnId,
      pickedHistory,
      seed
    });
  }

  public sendReadyStatus(status: 'READY' | 'NOT_READY' | 'IN_GAME') {
    this.updateLocalReadyStatus(status);
  }

  public sendPick(number: number, turnNumber: number, nextTurnId: string, pickedHistory: number[], seed: number = 0) {
    this.sendPacket({
      type: 'PICK_NUMBER',
      playerId: this.localPlayer?.id || '',
      number,
      turnNumber,
      currentTurnPlayerId: nextTurnId,
      pickedHistory,
      seed
    });
  }

  public sendPacket(packet: RoomMessagePacket) {
    if (!this.client || !this.client.connected || !this.currentRoomCode) return;
    const topic = `bingo/v3/room/${this.currentRoomCode}`;
    const outgoing: RoomMessagePacket = {
      ...packet,
      senderInstanceId: packet.senderInstanceId || this.instanceId,
      timestamp: packet.timestamp || Date.now()
    };
    const payload = FastPacketCodec.encode(outgoing);
    const qos = (packet.type === 'PICK_NUMBER' || packet.type === 'PING' || packet.type === 'PONG') ? 0 : 1;
    this.client.publish(topic, payload, { qos });
  }

  public async ensureConnected(): Promise<mqtt.MqttClient | null> {
    if (this.client && this.client.connected) return this.client;

    return new Promise((resolve) => {
      if (!this.client) {
        const dummy: Player = this.localPlayer || {
          id: `web_tmp_${Date.now()}`,
          displayName: 'Player',
          username: '',
          isHost: false,
          score: 0,
          completedLinesCount: 0,
          gamesPlayed: 0,
          gamesWon: 0,
          currentStreak: 0,
          level: 1,
          lastSeenTimestamp: Date.now(),
          lobbyReadyStatus: 'NOT_READY',
          readyVersion: 0
        };
        this.initGlobalClient(dummy);
      }

      if (this.client && this.client.connected) {
        return resolve(this.client);
      }

      const timer = setTimeout(() => {
        cleanup();
        resolve(this.client?.connected ? this.client : null);
      }, 3500);

      const onConnect = () => {
        cleanup();
        resolve(this.client);
      };

      const cleanup = () => {
        clearTimeout(timer);
        this.client?.removeListener('connect', onConnect);
      };

      this.client?.once('connect', onConnect);
    });
  }

  // ── Retained MQTT Broadcast Helpers for Android Cross-Discovery ──

  public async sendRetainedMeta(roomCode: string, jsonStr: string): Promise<void> {
    const client = await this.ensureConnected();
    if (!client || !client.connected) return;
    const topic = `bingo/v3/room_meta/${roomCode.trim().toUpperCase()}`;
    client.publish(topic, jsonStr, { qos: 1, retain: true });
  }

  public async sendRetainedUserRegistry(username: string, jsonStr: string): Promise<void> {
    const client = await this.ensureConnected();
    if (!client || !client.connected) return;
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    const topic = `bingo/v3/registry/${clean}`;
    client.publish(topic, jsonStr, { qos: 1, retain: true });
  }

  public async sendRetainedPresence(username: string, jsonStr: string): Promise<void> {
    const client = await this.ensureConnected();
    if (!client || !client.connected) return;
    const clean = username.trim().toLowerCase().replace(/^@/, '');
    const topic = `bingo/v3/presence/${clean}`;
    client.publish(topic, jsonStr, { qos: 1, retain: true });
  }

  public async sendMqttInvite(targetUsername: string, jsonStr: string): Promise<void> {
    const client = await this.ensureConnected();
    if (!client || !client.connected) return;
    const clean = targetUsername.trim().toLowerCase().replace(/^@/, '');
    const topic = `bingo/v3/invites/${clean}`;
    client.publish(topic, jsonStr, { qos: 1, retain: false });
  }

  private sendJoinPacket() {
    if (!this.localPlayer) return;
    const p = this.localPlayer;
    this.sendPacket({
      type: 'JOIN',
      playerId: p.id,
      displayName: p.displayName,
      username: p.username,
      isHost: p.isHost,
      avatarUrl: p.avatarUrl,
      gamesPlayed: p.gamesPlayed,
      gamesWon: p.gamesWon,
      currentStreak: p.currentStreak,
      level: p.level,
      readyStatus: p.lobbyReadyStatus,
      readyVersion: p.readyVersion
    });
  }

  private handleIncomingPacket(packet: RoomMessagePacket) {
    if (packet.senderInstanceId && packet.senderInstanceId === this.instanceId) return;
    if (this.localPlayer && packet.playerId === this.localPlayer.id && !packet.senderInstanceId) {
      if (this.localPlayer.isHost || (packet.type !== 'START_GAME' && packet.type !== 'PLAY_AGAIN')) {
        return;
      }
    }

    if (packet.type === 'ROOM_STATE' && packet.players && packet.players.length > 0) {
      this.mergePlayers(packet.players);
    }

    if (packet.type === 'JOIN' && this.localPlayer?.isHost) {
      this.sendPacket({
        type: 'ROOM_STATE',
        playerId: this.localPlayer.id,
        displayName: this.localPlayer.displayName,
        username: this.localPlayer.username,
        isHost: true,
        avatarUrl: this.localPlayer.avatarUrl,
        players: this.getPlayers(),
        timestamp: Date.now()
      });
    }

    if (packet.playerId && (packet.type === 'HEARTBEAT' || packet.type === 'JOIN' || packet.type === 'READY_STATUS')) {
      const existing = this.playerRegistry.get(packet.playerId);
      const isLocal = this.localPlayer && (packet.playerId === this.localPlayer.id || (packet.username && this.localPlayer.username && packet.username.toLowerCase() === this.localPlayer.username.toLowerCase()));
      if (!isLocal) {
        const updated: Player = {
          id: packet.playerId,
          displayName: packet.displayName || existing?.displayName || 'Player',
          username: packet.username || existing?.username || '',
          isHost: packet.isHost ?? existing?.isHost ?? false,
          avatarUrl: packet.avatarUrl ?? existing?.avatarUrl,
          score: existing?.score ?? 0,
          completedLinesCount: existing?.completedLinesCount ?? 0,
          gamesPlayed: packet.gamesPlayed ?? existing?.gamesPlayed ?? 0,
          gamesWon: packet.gamesWon ?? existing?.gamesWon ?? 0,
          currentStreak: packet.currentStreak ?? existing?.currentStreak ?? 0,
          level: packet.level ?? existing?.level ?? 1,
          lastSeenTimestamp: Date.now(),
          lobbyReadyStatus: (packet.readyStatus as any) || existing?.lobbyReadyStatus || (packet.isHost ? 'READY' : 'NOT_READY'),
          readyVersion: packet.readyVersion ?? existing?.readyVersion ?? 0
        };
        this.playerRegistry.set(packet.playerId, updated);
        this.notifyPlayers();
      }
    }

    if (packet.type === 'PING') {
      if (this.localPlayer && packet.playerId !== this.localPlayer.id) {
        this.sendPacket({
          type: 'PONG',
          playerId: this.localPlayer.id,
          pingTimestamp: packet.pingTimestamp || Date.now()
        });
      }
    } else if (packet.type === 'PONG') {
      if (packet.pingTimestamp) {
        const rtt = Math.max(1, Date.now() - packet.pingTimestamp);
        this.smoothedPing = Math.round(this.smoothedPing * 0.65 + rtt * 0.35);
        this.onPingChanged?.(this.smoothedPing);
        NetworkPingMonitor.recordExternalPing(this.smoothedPing);
      }
    } else if (packet.type === 'KICK_PLAYER') {
      if (this.localPlayer && packet.targetPlayerId === this.localPlayer.id) {
        alert('You were removed from the room by the host.');
        this.disconnect();
      } else if (packet.targetPlayerId) {
        this.playerRegistry.delete(packet.targetPlayerId);
        this.notifyPlayers();
      }
    }

    this.packetListeners.forEach((listener) => {
      try {
        listener(packet);
      } catch (err) {
        console.warn('Packet listener error:', err);
      }
    });

    this.onPacketReceived?.(packet);
  }

  public updateLocalReadyStatus(status: 'READY' | 'NOT_READY' | 'IN_GAME') {
    if (!this.localPlayer) return;
    const newVersion = (this.localPlayer.readyVersion || 0) + 1;
    this.localPlayer = {
      ...this.localPlayer,
      lobbyReadyStatus: status,
      readyVersion: newVersion,
      lastSeenTimestamp: Date.now()
    };
    this.playerRegistry.set(this.localPlayer.id, this.localPlayer);
    this.notifyPlayers();

    this.sendPacket({
      type: 'READY_STATUS',
      playerId: this.localPlayer.id,
      displayName: this.localPlayer.displayName,
      username: this.localPlayer.username,
      readyStatus: status,
      readyVersion: newVersion
    });
  }

  public getPlayers(): Player[] {
    return Array.from(this.playerRegistry.values()).sort((a, b) => (b.isHost ? 1 : 0) - (a.isHost ? 1 : 0));
  }

  public mergePlayers(players: Player[]) {
    let changed = false;
    const now = Date.now();
    players.forEach(p => {
      if (!p.id) return;
      const isLocal = this.localPlayer && (p.id === this.localPlayer.id || (p.username && this.localPlayer.username && p.username.toLowerCase() === this.localPlayer.username.toLowerCase()));
      const existing = this.playerRegistry.get(p.id);
      if (!existing) {
        this.playerRegistry.set(p.id, {
          ...p,
          lastSeenTimestamp: now
        });
        changed = true;
      } else {
        const nextStatus = isLocal ? this.localPlayer!.lobbyReadyStatus : (p.lobbyReadyStatus || existing.lobbyReadyStatus);
        if (existing.lobbyReadyStatus !== nextStatus || existing.displayName !== p.displayName) {
          this.playerRegistry.set(p.id, {
            ...existing,
            displayName: p.displayName || existing.displayName,
            avatarUrl: p.avatarUrl || existing.avatarUrl,
            lobbyReadyStatus: nextStatus,
            lastSeenTimestamp: now
          });
          changed = true;
        }
      }
    });
    if (changed) {
      this.notifyPlayers();
    }
  }

  private notifyPlayers() {
    const list = this.getPlayers();
    this.onPlayersChanged?.(list);
  }

  private startHeartbeatLoop() {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = setInterval(() => {
      if (!this.client || !this.client.connected || !this.localPlayer || !this.currentRoomCode) return;
      const p = this.localPlayer;
      this.sendPacket({
        type: 'HEARTBEAT',
        playerId: p.id,
        displayName: p.displayName,
        username: p.username,
        isHost: p.isHost,
        avatarUrl: p.avatarUrl,
        gamesPlayed: p.gamesPlayed,
        gamesWon: p.gamesWon,
        currentStreak: p.currentStreak,
        level: p.level,
        readyStatus: p.lobbyReadyStatus,
        readyVersion: p.readyVersion,
        timestamp: Date.now()
      });
    }, 2000);
  }

  private startPingLoop() {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = setInterval(() => {
      if (!this.client || !this.client.connected || !this.localPlayer || !this.currentRoomCode) return;
      this.sendPacket({
        type: 'PING',
        playerId: this.localPlayer.id,
        pingTimestamp: Date.now()
      });
    }, 1000);
  }

  private startLivenessLoop() {
    if (this.livenessTimer) clearInterval(this.livenessTimer);
    this.livenessTimer = setInterval(() => {
      const now = Date.now();
      let changed = false;
      for (const [id, player] of this.playerRegistry.entries()) {
        if (this.localPlayer && id === this.localPlayer.id) continue;
        if (now - player.lastSeenTimestamp > 30000) {
          this.playerRegistry.delete(id);
          changed = true;
        }
      }
      if (changed) {
        this.notifyPlayers();
      }
    }, 3000);
  }

  private startGlobalPresenceLoop() {
    if (this.globalPresenceTimer) clearInterval(this.globalPresenceTimer);
    this.globalPresenceTimer = setInterval(() => {
      if (!this.client || !this.client.connected || !this.localPlayer?.username) return;
      const clean = this.localPlayer.username.trim().toLowerCase().replace(/^@/, '');
      const payload = JSON.stringify({
        username: clean,
        status: 'ONLINE',
        timestamp: Date.now(),
        appVersion: '1.3.3',
        appVersionCode: 39
      });
      this.sendRetainedPresence(clean, payload);
    }, 6000);
  }

  public disconnect() {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.pingTimer) clearInterval(this.pingTimer);
    if (this.livenessTimer) clearInterval(this.livenessTimer);
    
    if (this.client && this.currentRoomCode && this.localPlayer) {
      this.sendPacket({
        type: 'LEAVE',
        playerId: this.localPlayer.id,
        displayName: this.localPlayer.displayName
      });
    }

    this.currentRoomCode = null;
    this.playerRegistry.clear();
  }

  public destroy() {
    this.disconnect();
    if (this.globalPresenceTimer) clearInterval(this.globalPresenceTimer);
    if (this.client) {
      try { this.client.end(true); } catch {}
      this.client = null;
    }
  }
}

export const roomSync = new MqttRoomManager();
