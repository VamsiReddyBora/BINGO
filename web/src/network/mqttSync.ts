import mqtt, { MqttClient } from 'mqtt';
import { Player, RoomMessagePacket } from '../types/models';
import { FastPacketCodec } from './codec';

export const MQTT_WS_URL = 'wss://broker.emqx.io:8084/mqtt';

export class MqttRoomManager {
  private client: MqttClient | null = null;
  private currentRoomCode: string | null = null;
  private localPlayer: Player | null = null;
  private playerRegistry: Map<string, Player> = new Map();
  public readonly instanceId: string = Math.random().toString(36).substring(2, 10);
  
  private heartbeatTimer: any = null;
  private pingTimer: any = null;
  private livenessTimer: any = null;
  
  public onPacketReceived: ((packet: RoomMessagePacket) => void) | null = null;
  public onPlayersChanged: ((players: Player[]) => void) | null = null;
  public onPingChanged: ((pingMs: number) => void) | null = null;
  public onConnectionChanged: ((connected: boolean) => void) | null = null;

  private smoothedPing: number = 24;

  public connect(roomCode: string, player: Player) {
    this.disconnect();
    
    const cleanCode = roomCode.trim().toUpperCase();
    this.currentRoomCode = cleanCode;
    this.localPlayer = { ...player };

    this.playerRegistry.clear();
    this.playerRegistry.set(player.id, { ...player, lastSeenTimestamp: Date.now() });
    this.notifyPlayers();

    const clientId = `bingo_web_${player.id.substring(0, 8)}_${Math.random().toString(36).substring(2, 6)}`;
    
    try {
      this.client = mqtt.connect(MQTT_WS_URL, {
        clientId,
        clean: true,
        reconnectPeriod: 2000,
        connectTimeout: 8000,
        keepalive: 30
      });

      this.client.on('connect', () => {
        this.onConnectionChanged?.(true);
        this.subscribeToRoom(cleanCode);
        this.sendJoinPacket();
      });

      this.client.on('message', (_topic, message) => {
        try {
          const payloadStr = message.toString();
          const packet = FastPacketCodec.decode(payloadStr);
          this.handleIncomingPacket(packet);
        } catch (e) {
          console.warn('Failed to parse incoming packet:', e);
        }
      });

      this.client.on('reconnect', () => {
        if (this.currentRoomCode) {
          this.subscribeToRoom(this.currentRoomCode);
        }
      });

      this.client.on('close', () => {
        this.onConnectionChanged?.(false);
      });

      this.client.on('error', (err) => {
        console.warn('MQTT connection error:', err);
      });

      this.startHeartbeatLoop();
      this.startPingLoop();
      this.startLivenessLoop();
    } catch (err) {
      console.error('Failed to create MQTT client:', err);
    }
  }

  private subscribeToRoom(code: string) {
    if (!this.client || !this.client.connected) return;
    const topic = `bingo/v3/room/${code}`;
    this.client.subscribe(topic, { qos: 1 }, (err) => {
      if (err) console.error('Subscription error on', topic, err);
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
    // Drop self-echoed packets
    if (packet.senderInstanceId && packet.senderInstanceId === this.instanceId) {
      return;
    }
    if (this.localPlayer && packet.playerId === this.localPlayer.id && !packet.senderInstanceId) {
      if (this.localPlayer.isHost || (packet.type !== 'START_GAME' && packet.type !== 'PLAY_AGAIN')) {
        return;
      }
    }

    // Process Presence & Heartbeats
    if (packet.playerId && packet.type === 'HEARTBEAT' || packet.type === 'JOIN' || packet.type === 'READY_STATUS') {
      const existing = this.playerRegistry.get(packet.playerId);
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

    // Process Ping / Pong for latency measurement
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

    // Forward to general listener
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

  private notifyPlayers() {
    const list = Array.from(this.playerRegistry.values()).sort((a, b) => (b.isHost ? 1 : 0) - (a.isHost ? 1 : 0));
    this.onPlayersChanged?.(list);
  }

  private startHeartbeatLoop() {
    this.heartbeatTimer = setInterval(() => {
      if (!this.client || !this.client.connected || !this.localPlayer) return;
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
    this.pingTimer = setInterval(() => {
      if (!this.client || !this.client.connected || !this.localPlayer) return;
      this.sendPacket({
        type: 'PING',
        playerId: this.localPlayer.id,
        pingTimestamp: Date.now()
      });
    }, 2000);
  }

  private startLivenessLoop() {
    this.livenessTimer = setInterval(() => {
      const now = Date.now();
      let changed = false;
      for (const [id, player] of this.playerRegistry.entries()) {
        if (this.localPlayer && id === this.localPlayer.id) continue;
        // If no heartbeat for > 12 seconds, remove inactive player
        if (now - player.lastSeenTimestamp > 12000) {
          this.playerRegistry.delete(id);
          changed = true;
        }
      }
      if (changed) {
        this.notifyPlayers();
      }
    }, 3000);
  }

  public disconnect() {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.pingTimer) clearInterval(this.pingTimer);
    if (this.livenessTimer) clearInterval(this.livenessTimer);
    
    if (this.client) {
      if (this.currentRoomCode && this.localPlayer) {
        this.sendPacket({
          type: 'LEAVE',
          playerId: this.localPlayer.id,
          displayName: this.localPlayer.displayName
        });
      }
      try {
        this.client.end(true);
      } catch {}
      this.client = null;
    }

    this.currentRoomCode = null;
    this.playerRegistry.clear();
  }
}

export const roomSync = new MqttRoomManager();
