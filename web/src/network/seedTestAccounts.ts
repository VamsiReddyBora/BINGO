import { Player } from '../types/models';
import { CloudRegistry, PlayerRegistryEntry } from './cloudRegistry';
import { MqttRoomManager, roomSync } from './mqttSync';

export const TEST_ACCOUNTS: Record<string, Player> = {
  kevin: {
    id: 'user_kevin',
    username: 'kevin',
    displayName: 'Kevin 👑',
    avatarUrl: '👑',
    isHost: false,
    score: 0,
    completedLinesCount: 0,
    gamesPlayed: 45,
    gamesWon: 28,
    currentStreak: 3,
    level: 12,
    lastSeenTimestamp: Date.now(),
    lobbyReadyStatus: 'READY',
    readyVersion: 0,
    authProvider: 'GOOGLE'
  },
  stuart: {
    id: 'user_stuart',
    username: 'stuart',
    displayName: 'Stuart 🤖',
    avatarUrl: '🤖',
    isHost: false,
    score: 0,
    completedLinesCount: 0,
    gamesPlayed: 32,
    gamesWon: 18,
    currentStreak: 2,
    level: 8,
    lastSeenTimestamp: Date.now(),
    lobbyReadyStatus: 'READY',
    readyVersion: 0,
    authProvider: 'GUEST'
  },
  phil: {
    id: 'user_phil',
    username: 'phil',
    displayName: 'Phil 👾',
    avatarUrl: '👾',
    isHost: false,
    score: 0,
    completedLinesCount: 0,
    gamesPlayed: 39,
    gamesWon: 21,
    currentStreak: 1,
    level: 10,
    lastSeenTimestamp: Date.now(),
    lobbyReadyStatus: 'READY',
    readyVersion: 0,
    authProvider: 'GUEST'
  },
  otto: {
    id: 'user_otto',
    username: 'otto',
    displayName: 'Otto 🦊',
    avatarUrl: '🦊',
    isHost: false,
    score: 0,
    completedLinesCount: 0,
    gamesPlayed: 25,
    gamesWon: 14,
    currentStreak: 0,
    level: 7,
    lastSeenTimestamp: Date.now(),
    lobbyReadyStatus: 'READY',
    readyVersion: 0,
    authProvider: 'GUEST'
  }
};

/**
 * Returns list of mutual friends for a given username among the 4 test accounts.
 */
export function getTestFriends(username: string): PlayerRegistryEntry[] {
  const clean = username.trim().toLowerCase().replace(/^@/, '');
  const others = Object.values(TEST_ACCOUNTS).filter(
    (acc) => acc.username.toLowerCase() !== clean
  );

  return others.map((acc) => ({
    uid: acc.id,
    username: acc.username,
    displayName: acc.displayName,
    avatarUrl: acc.avatarUrl || null,
    gamesPlayed: acc.gamesPlayed,
    gamesWon: acc.gamesWon,
    currentStreak: acc.currentStreak,
    bestStreak: acc.currentStreak,
    level: acc.level,
    lastSeenTimestamp: Date.now()
  }));
}

/**
 * Ensures all 4 test accounts are pre-seeded in localStorage, CloudRegistry, and MQTT.
 */
export async function seedAllTestAccounts(manager?: MqttRoomManager): Promise<void> {
  const sync = manager || roomSync;
  const accounts = Object.values(TEST_ACCOUNTS);

  for (const acc of accounts) {
    try {
      // 1. Seed user in localStorage
      localStorage.setItem(`bingo_user_${acc.username}`, JSON.stringify(acc));

      // 2. Compute mutual friends
      const friends = getTestFriends(acc.username);
      localStorage.setItem(`bingo_friends_${acc.username}`, JSON.stringify(friends));

      // 3. Register user in CloudRegistry
      await CloudRegistry.claimAndRegisterUser(acc);

      // 4. Save friends list in CloudRegistry
      await CloudRegistry.saveCloudFriends(acc.username, friends);

      // 5. Broadcast retained presence & friends via MQTT
      if (sync && typeof sync.sendRetainedPresence === 'function') {
        const presPayload = JSON.stringify({
          username: acc.username,
          status: 'ONLINE',
          timestamp: Date.now(),
          appVersion: '1.5.0',
          appVersionCode: 53
        });
        sync.sendRetainedPresence(acc.username, presPayload).catch(() => {});
      }
    } catch (e) {
      console.warn(`Error seeding account ${acc.username}:`, e);
    }
  }
}
