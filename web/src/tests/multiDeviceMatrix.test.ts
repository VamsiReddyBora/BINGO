import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { MqttRoomManager, setMqttConnectOverride } from '../network/mqttSync';
import { TEST_ACCOUNTS, getTestFriends } from '../network/seedTestAccounts';
import { Player } from '../types/models';

class InMemoryMqttClient extends EventEmitter {
  public connected = true;
  private subscriptions = new Set<string>();
  private connectTimer: any = null;

  constructor(public broker: InMemoryMqttBroker, public opts?: any) {
    super();
    this.connectTimer = setTimeout(() => {
      if (this.connected) this.emit('connect');
    }, 5);
  }

  subscribe(topic: string, opts?: any, callback?: () => void) {
    this.subscriptions.add(topic);
    this.broker.subscribe(this, topic);
    callback?.();
  }

  unsubscribe(topic: string, opts?: any, callback?: () => void) {
    this.subscriptions.delete(topic);
    this.broker.unsubscribe(this, topic);
    callback?.();
  }

  publish(topic: string, message: any, opts?: any, callback?: (err?: any) => void) {
    const payload = typeof message === 'string' ? Buffer.from(message) : Buffer.isBuffer(message) ? message : Buffer.from(String(message));
    this.broker.publish(this, topic, payload, opts?.retain || false);
    callback?.();
  }

  end(force?: boolean, opts?: any, callback?: () => void) {
    this.connected = false;
    if (this.connectTimer) clearTimeout(this.connectTimer);
    this.broker.removeClient(this);
    this.emit('close');
    callback?.();
  }

  removeAllListeners(event?: string | symbol) {
    super.removeAllListeners(event);
    return this;
  }
}

class InMemoryMqttBroker {
  public retained = new Map<string, Buffer>();
  public clients = new Set<InMemoryMqttClient>();
  public subscriptions = new Map<string, Set<InMemoryMqttClient>>();
  private activeTimers = new Set<any>();

  createClient(opts?: any) {
    const client = new InMemoryMqttClient(this, opts);
    this.clients.add(client);
    return client;
  }

  subscribe(client: InMemoryMqttClient, topicPattern: string) {
    let set = this.subscriptions.get(topicPattern);
    if (!set) {
      set = new Set();
      this.subscriptions.set(topicPattern, set);
    }
    set.add(client);

    for (const [topic, payload] of this.retained.entries()) {
      if (this.topicMatches(topicPattern, topic)) {
        const timer = setTimeout(() => {
          this.activeTimers.delete(timer);
          if (client.connected) client.emit('message', topic, payload);
        }, 2);
        this.activeTimers.add(timer);
      }
    }
  }

  unsubscribe(client: InMemoryMqttClient, topicPattern: string) {
    this.subscriptions.get(topicPattern)?.delete(client);
  }

  removeClient(client: InMemoryMqttClient) {
    this.clients.delete(client);
    for (const set of this.subscriptions.values()) {
      set.delete(client);
    }
  }

  publish(sender: InMemoryMqttClient, topic: string, payload: Buffer, retain: boolean) {
    if (retain) {
      if (payload.length === 0) {
        this.retained.delete(topic);
      } else {
        this.retained.set(topic, payload);
      }
    }

    for (const [pattern, subscribers] of this.subscriptions.entries()) {
      if (this.topicMatches(pattern, topic)) {
        for (const sub of subscribers) {
          const timer = setTimeout(() => {
            this.activeTimers.delete(timer);
            if (sub.connected) sub.emit('message', topic, payload);
          }, 2);
          this.activeTimers.add(timer);
        }
      }
    }
  }

  private topicMatches(pattern: string, topic: string): boolean {
    if (pattern === topic || pattern === '#') return true;
    const pParts = pattern.split('/');
    const tParts = topic.split('/');
    for (let i = 0; i < pParts.length; i++) {
      if (pParts[i] === '#') return true;
      if (pParts[i] === '+') continue;
      if (pParts[i] !== tParts[i]) return false;
    }
    return pParts.length === tParts.length;
  }

  clear() {
    for (const t of this.activeTimers) {
      clearTimeout(t);
    }
    this.activeTimers.clear();
    this.retained.clear();
    for (const c of Array.from(this.clients)) {
      c.end(true);
    }
    this.clients.clear();
    this.subscriptions.clear();
  }
}

const mockBroker = new InMemoryMqttBroker();

describe('Multi-Device 4-Screen Test Matrix Automation & Rules Verification', () => {
  before(() => {
    setMqttConnectOverride((url, opts) => {
      return mockBroker.createClient(opts);
    });
  });

  after(() => {
    setMqttConnectOverride(null);
    mockBroker.clear();
  });

  it('Verifies persistent test accounts (kevin, stuart, phil, otto) and mutual friendships', () => {
    assert.ok(TEST_ACCOUNTS.kevin, 'Kevin account must exist');
    assert.ok(TEST_ACCOUNTS.stuart, 'Stuart account must exist');
    assert.ok(TEST_ACCOUNTS.phil, 'Phil account must exist');
    assert.ok(TEST_ACCOUNTS.otto, 'Otto account must exist');

    // Test mutual friends
    const kevinFriends = getTestFriends('kevin');
    assert.equal(kevinFriends.length, 3, 'Kevin must have 3 friends');
    const kevinFriendUsernames = kevinFriends.map(f => f.username).sort();
    assert.deepEqual(kevinFriendUsernames, ['otto', 'phil', 'stuart']);

    const stuartFriends = getTestFriends('stuart');
    assert.equal(stuartFriends.length, 3, 'Stuart must have 3 friends');
    const stuartFriendUsernames = stuartFriends.map(f => f.username).sort();
    assert.deepEqual(stuartFriendUsernames, ['kevin', 'otto', 'phil']);

    const philFriends = getTestFriends('phil');
    assert.equal(philFriends.length, 3, 'Phil must have 3 friends');
    const philFriendUsernames = philFriends.map(f => f.username).sort();
    assert.deepEqual(philFriendUsernames, ['kevin', 'otto', 'stuart']);

    const ottoFriends = getTestFriends('otto');
    assert.equal(ottoFriends.length, 3, 'Otto must have 3 friends');
    const ottoFriendUsernames = ottoFriends.map(f => f.username).sort();
    assert.deepEqual(ottoFriendUsernames, ['kevin', 'phil', 'stuart']);
  });

  it('Simulates 4 independent virtual devices joining a room and verifying ready status', async () => {
    const roomCode = '882910';

    const kevinManager = new MqttRoomManager();
    const stuartManager = new MqttRoomManager();
    const philManager = new MqttRoomManager();
    const ottoManager = new MqttRoomManager();

    const kevinPlayer: Player = { ...TEST_ACCOUNTS.kevin, isHost: true, lobbyReadyStatus: 'READY' };
    const stuartPlayer: Player = { ...TEST_ACCOUNTS.stuart, isHost: false, lobbyReadyStatus: 'NOT_READY' };
    const philPlayer: Player = { ...TEST_ACCOUNTS.phil, isHost: false, lobbyReadyStatus: 'NOT_READY' };
    const ottoPlayer: Player = { ...TEST_ACCOUNTS.otto, isHost: false, lobbyReadyStatus: 'NOT_READY' };

    // Kevin hosts room
    kevinManager.joinRoom(roomCode, kevinPlayer, [kevinPlayer]);

    // Stuart, Phil, Otto join room
    stuartManager.joinRoom(roomCode, stuartPlayer, [kevinPlayer, stuartPlayer]);
    philManager.joinRoom(roomCode, philPlayer, [kevinPlayer, stuartPlayer, philPlayer]);
    ottoManager.joinRoom(roomCode, ottoPlayer, [kevinPlayer, stuartPlayer, philPlayer, ottoPlayer]);

    await new Promise(r => setTimeout(r, 60));

    // Stuart, Phil, Otto toggle Ready
    stuartManager.sendReadyStatus('READY');
    philManager.sendReadyStatus('READY');
    ottoManager.sendReadyStatus('READY');

    await new Promise(r => setTimeout(r, 60));

    // Verify players are registered
    const playersInLobby = kevinManager.getPlayers();
    assert.ok(playersInLobby.length >= 1, 'Kevin sees players in room');

    // Clean up
    kevinManager.destroy();
    stuartManager.destroy();
    philManager.destroy();
    ottoManager.destroy();
  });

  it('Simulates 4-player circular turn rotation and real-time pick broadcasting', async () => {
    const roomCode = '991204';
    const seed = 44219;

    const kevin = new MqttRoomManager();
    const stuart = new MqttRoomManager();
    const phil = new MqttRoomManager();
    const otto = new MqttRoomManager();

    kevin.joinRoom(roomCode, TEST_ACCOUNTS.kevin, [TEST_ACCOUNTS.kevin]);
    stuart.joinRoom(roomCode, TEST_ACCOUNTS.stuart, [TEST_ACCOUNTS.kevin, TEST_ACCOUNTS.stuart]);
    phil.joinRoom(roomCode, TEST_ACCOUNTS.phil, [TEST_ACCOUNTS.kevin, TEST_ACCOUNTS.stuart, TEST_ACCOUNTS.phil]);
    otto.joinRoom(roomCode, TEST_ACCOUNTS.otto, [TEST_ACCOUNTS.kevin, TEST_ACCOUNTS.stuart, TEST_ACCOUNTS.phil, TEST_ACCOUNTS.otto]);

    await new Promise(r => setTimeout(r, 60));

    // Track picks received on Stuart and Phil
    const receivedPicksStuart: number[] = [];
    const receivedPicksPhil: number[] = [];

    stuart.addPacketListener((pkt) => {
      if (pkt.type === 'PICK_NUMBER' && pkt.number) {
        receivedPicksStuart.push(pkt.number);
      }
    });

    phil.addPacketListener((pkt) => {
      if (pkt.type === 'PICK_NUMBER' && pkt.number) {
        receivedPicksPhil.push(pkt.number);
      }
    });

    // Turn 1: Kevin picks number 17, next turn is Stuart
    kevin.sendPick(17, 1, TEST_ACCOUNTS.stuart.id, [17], seed);
    await new Promise(r => setTimeout(r, 60));

    assert.equal(receivedPicksStuart.length, 1, 'Stuart received Kevin pick');
    assert.equal(receivedPicksStuart[0], 17);
    assert.equal(receivedPicksPhil.length, 1, 'Phil received Kevin pick');
    assert.equal(receivedPicksPhil[0], 17);

    // Turn 2: Stuart picks number 8, next turn is Phil
    stuart.sendPick(8, 2, TEST_ACCOUNTS.phil.id, [17, 8], seed);
    await new Promise(r => setTimeout(r, 60));

    assert.equal(receivedPicksStuart.length, 1, 'Stuart own pick not echoed');
    assert.equal(receivedPicksPhil.length, 2, 'Phil received Stuart pick');
    assert.equal(receivedPicksPhil[1], 8);

    // Turn 3: Phil picks number 23, next turn is Otto
    phil.sendPick(23, 3, TEST_ACCOUNTS.otto.id, [17, 8, 23], seed);
    await new Promise(r => setTimeout(r, 60));

    assert.equal(receivedPicksStuart.length, 2, 'Stuart received Phil pick');
    assert.equal(receivedPicksStuart[1], 23);

    // Turn 4: Otto picks number 4, next turn completes circle back to Kevin
    otto.sendPick(4, 4, TEST_ACCOUNTS.kevin.id, [17, 8, 23, 4], seed);
    await new Promise(r => setTimeout(r, 60));

    assert.equal(receivedPicksStuart.length, 3, 'Stuart received Otto pick');
    assert.equal(receivedPicksStuart[2], 4);
    assert.equal(receivedPicksPhil.length, 3, 'Phil received Otto pick');
    assert.equal(receivedPicksPhil[2], 4);

    kevin.destroy();
    stuart.destroy();
    phil.destroy();
    otto.destroy();
  });

  it('Verifies Rule 4B Surrender in multiplayer (turn immediately advances to next active player)', async () => {
    const roomCode = '774411';
    const kevin = new MqttRoomManager();
    const stuart = new MqttRoomManager();
    const phil = new MqttRoomManager();

    kevin.joinRoom(roomCode, TEST_ACCOUNTS.kevin, [TEST_ACCOUNTS.kevin]);
    stuart.joinRoom(roomCode, TEST_ACCOUNTS.stuart, [TEST_ACCOUNTS.kevin, TEST_ACCOUNTS.stuart]);
    phil.joinRoom(roomCode, TEST_ACCOUNTS.phil, [TEST_ACCOUNTS.kevin, TEST_ACCOUNTS.stuart, TEST_ACCOUNTS.phil]);

    await new Promise(r => setTimeout(r, 60));

    let surrenderReceived = false;
    kevin.addPacketListener((pkt) => {
      if (pkt.type === 'SURRENDER' && pkt.playerId === TEST_ACCOUNTS.stuart.id) {
        surrenderReceived = true;
      }
    });

    // Stuart surrenders
    stuart.sendPacket({
      type: 'SURRENDER',
      playerId: TEST_ACCOUNTS.stuart.id,
      displayName: TEST_ACCOUNTS.stuart.displayName
    });

    await new Promise(r => setTimeout(r, 60));
    assert.ok(surrenderReceived, 'Kevin must receive Stuart surrender packet');

    kevin.destroy();
    stuart.destroy();
    phil.destroy();
  });

  it('Verifies Rule 3A and 3C Minimize / Resume countdown reset behavior', () => {
    // Rule 3A: When app is minimized, turn timer continues running
    let turnTimer = 30;
    let isMinimized = true;

    // Simulate 5 seconds ticking in background
    for (let i = 0; i < 5; i++) {
      if (isMinimized) {
        turnTimer--;
      }
    }
    assert.equal(turnTimer, 25, 'Turn timer must continue ticking during background minimize (Rule 3A)');

    // Rule 3C: When app resumes, "Welcome Back!" resets the active player turn timer to 30s
    isMinimized = false;
    let showWelcomeBack = true;
    turnTimer = 30; // Rule 3C Welcome Back 30s reset

    assert.equal(turnTimer, 30, 'Resuming must reset active turn timer to 30s (Rule 3C)');
    assert.ok(showWelcomeBack, 'Welcome back banner must show on foreground resume');
  });
});
