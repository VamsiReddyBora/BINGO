import React, { useState, useEffect } from 'react';
import { Player } from './types/models';
import { ThemeProvider } from './theme/theme';
import { LoginScreen } from './screens/LoginScreen';
import { MainContainer } from './screens/MainContainer';
import { OnlineMatchChoiceScreen } from './screens/OnlineMatchChoiceScreen';
import { JoinRoomScreen } from './screens/JoinRoomScreen';
import { LobbyScreen } from './screens/LobbyScreen';
import { GameScreen } from './screens/GameScreen';
import { roomSync } from './network/mqttSync';
import { CloudRegistry, GameInvite } from './network/cloudRegistry';
import { soundEffects } from './audio/sounds';

export type ScreenState =
  | 'LOGIN'
  | 'MAIN_CONTAINER'
  | 'ONLINE_CHOICE'
  | 'JOIN_ROOM'
  | 'LOBBY'
  | 'GAME';

interface OngoingMatch {
  roomCode: string;
  isHost: boolean;
  timestamp: number;
}

const STORAGE_KEY_PLAYER = 'bingo_web_player_v2';
const STORAGE_KEY_ONGOING = 'bingo_web_ongoing_match_v2';

export const AppContent: React.FC = () => {
  const [localPlayer, setLocalPlayer] = useState<Player | null>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_PLAYER);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed.username && parsed.displayName) return parsed;
      }
    } catch {}
    return null;
  });

  const [currentScreen, setCurrentScreen] = useState<ScreenState>(() => {
    return localPlayer ? 'MAIN_CONTAINER' : 'LOGIN';
  });

  const [ongoingMatch, setOngoingMatch] = useState<OngoingMatch | null>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_ONGOING);
      if (raw) {
        const parsed = JSON.parse(raw);
        // Only keep if under 30 minutes old
        if (Date.now() - parsed.timestamp < 30 * 60 * 1000) {
          return parsed;
        }
      }
    } catch {}
    return null;
  });

  const [roomCode, setRoomCode] = useState<string>('');
  const [playersInLobby, setPlayersInLobby] = useState<Player[]>([]);
  const [opponent, setOpponent] = useState<Player | null>(null);
  const [matchSeed, setMatchSeed] = useState<number>(0);
  const [firstTurnPlayerId, setFirstTurnPlayerId] = useState<string>('');
  const [isAiMode, setIsAiMode] = useState<boolean>(false);
  const [pingMs, setPingMs] = useState<number>(24);

  // Initialize global presence and MQTT listeners
  useEffect(() => {
    if (localPlayer) {
      roomSync.initGlobalClient(localPlayer);
      CloudRegistry.publishPresence(localPlayer.username, 'ONLINE');

      roomSync.onPingChanged = (ms) => {
        setPingMs(ms);
      };

      roomSync.onPlayersChanged = (newPlayers) => {
        setPlayersInLobby(newPlayers);
        const opp = newPlayers.find((p) => p.id !== localPlayer.id);
        if (opp) setOpponent(opp);
      };

      roomSync.onPacketReceived = (packet) => {
        if (packet.type === 'START_GAME') {
          soundEffects.playTurnAlert();
          setMatchSeed(packet.seed || Math.floor(Math.random() * 100000) + 1);
          setFirstTurnPlayerId(packet.currentTurnPlayerId || localPlayer.id);
          setCurrentScreen('GAME');
        }
      };

      roomSync.onInviteReceived = (invite: GameInvite) => {
        soundEffects.playTurnAlert();
        if (confirm(`Game invite received from ${invite.fromDisplayName}! Join room ${invite.roomCode}?`)) {
          handleJoinRoom(invite.roomCode);
        }
      };
    }
  }, [localPlayer]);

  // Check URL query parameters for ?room=123456
  useEffect(() => {
    try {
      const params = new URLSearchParams(window.location.search);
      const code = params.get('room');
      if (code && code.trim().length >= 4 && localPlayer) {
        handleJoinRoom(code.trim().toUpperCase());
      }
    } catch {}
  }, [localPlayer]);

  // Save / Update player
  const handleUpdatePlayer = (updated: Player) => {
    setLocalPlayer(updated);
    try {
      localStorage.setItem(STORAGE_KEY_PLAYER, JSON.stringify(updated));
    } catch {}
  };

  // Login Success
  const handleLoginSuccess = (player: Player) => {
    handleUpdatePlayer(player);
    setCurrentScreen('MAIN_CONTAINER');
  };

  // Sign out
  const handleSignOut = () => {
    if (localPlayer) {
      CloudRegistry.publishPresence(localPlayer.username, 'OFFLINE');
    }
    localStorage.removeItem(STORAGE_KEY_PLAYER);
    localStorage.removeItem(STORAGE_KEY_ONGOING);
    setLocalPlayer(null);
    setOngoingMatch(null);
    setCurrentScreen('LOGIN');
  };

  // Host Online Room
  const handleHostRoom = () => {
    if (!localPlayer) return;
    const generated = Math.floor(100000 + Math.random() * 900000).toString();
    setRoomCode(generated);
    setIsAiMode(false);

    const hostPlayer: Player = {
      ...localPlayer,
      isHost: true,
      lobbyReadyStatus: 'READY'
    };

    setPlayersInLobby([hostPlayer]);
    setOpponent(null);

    // Save ongoing match record
    const ongoing: OngoingMatch = {
      roomCode: generated,
      isHost: true,
      timestamp: Date.now()
    };
    setOngoingMatch(ongoing);
    try {
      localStorage.setItem(STORAGE_KEY_ONGOING, JSON.stringify(ongoing));
    } catch {}

    // Register room in cloud registry
    CloudRegistry.createRoom(generated, hostPlayer).catch(() => {});

    // Join room over MQTT
    roomSync.joinRoom(generated, hostPlayer);
    setCurrentScreen('LOBBY');
  };

  // Join Online Room
  const handleJoinRoom = (code: string) => {
    if (!localPlayer) return;
    const clean = code.trim().toUpperCase();
    setRoomCode(clean);
    setIsAiMode(false);

    const joinerPlayer: Player = {
      ...localPlayer,
      isHost: false,
      lobbyReadyStatus: 'NOT_READY'
    };

    setPlayersInLobby([joinerPlayer]);

    const ongoing: OngoingMatch = {
      roomCode: clean,
      isHost: false,
      timestamp: Date.now()
    };
    setOngoingMatch(ongoing);
    try {
      localStorage.setItem(STORAGE_KEY_ONGOING, JSON.stringify(ongoing));
    } catch {}

    roomSync.joinRoom(clean, joinerPlayer);
    setCurrentScreen('LOBBY');
  };

  // Dismiss ongoing match
  const handleDismissOngoingMatch = () => {
    setOngoingMatch(null);
    try {
      localStorage.removeItem(STORAGE_KEY_ONGOING);
    } catch {}
  };

  // Rejoin ongoing match
  const handleRejoinOngoingMatch = () => {
    if (!ongoingMatch || !localPlayer) return;
    handleJoinRoom(ongoingMatch.roomCode);
  };

  // Start AI Match
  const handleStartAiGame = (difficulty: 'EASY' | 'HARD') => {
    if (!localPlayer) return;
    setIsAiMode(true);
    setRoomCode('AI_SOLO');

    const aiBot: Player = {
      id: 'ai_bot',
      displayName: difficulty === 'EASY' ? 'Easy Bot' : 'Master Bot',
      username: 'ai_bot',
      isHost: false,
      isAi: true,
      avatarUrl: null,
      score: 0,
      completedLinesCount: 0,
      gamesPlayed: 10,
      gamesWon: 5,
      currentStreak: 1,
      level: difficulty === 'EASY' ? 1 : 5,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'READY',
      readyVersion: 0
    };

    setOpponent(aiBot);
    setMatchSeed(Math.floor(Math.random() * 100000) + 1);
    setFirstTurnPlayerId(localPlayer.id);
    setCurrentScreen('GAME');
  };

  // Host starts game from Lobby
  const handleHostStartLobbyGame = () => {
    if (!localPlayer) return;
    const generatedSeed = Math.floor(Math.random() * 100000) + 1;
    const starterId = localPlayer.id;

    setMatchSeed(generatedSeed);
    setFirstTurnPlayerId(starterId);

    // Broadcast to room
    roomSync.sendStartGame(generatedSeed, starterId);
    setCurrentScreen('GAME');
  };

  // Toggle ready status
  const handleToggleReady = (ready: boolean) => {
    if (!localPlayer) return;
    const status = ready ? 'READY' : 'NOT_READY';
    setPlayersInLobby((prev) =>
      prev.map((p) => (p.id === localPlayer.id ? { ...p, lobbyReadyStatus: status } : p))
    );
    roomSync.sendReadyStatus(status);
  };

  // Leave Game
  const handleLeaveGame = () => {
    if (!isAiMode) {
      roomSync.leaveRoom();
    }
    handleDismissOngoingMatch();
    setCurrentScreen('MAIN_CONTAINER');
  };

  return (
    <div className="w-full min-h-[100dvh] flex flex-col justify-center items-center bg-black">
      {/* Centered responsive container: mobile full-width, desktop centered card */}
      <div className="w-full max-w-md min-h-[100dvh] flex flex-col shadow-2xl relative overflow-hidden">
        {currentScreen === 'LOGIN' && (
          <LoginScreen onLoginSuccess={handleLoginSuccess} />
        )}

        {currentScreen === 'MAIN_CONTAINER' && localPlayer && (
          <MainContainer
            localPlayer={localPlayer}
            pingMs={pingMs}
            ongoingMatch={ongoingMatch}
            onDismissOngoingMatch={handleDismissOngoingMatch}
            onRejoinOngoingMatch={handleRejoinOngoingMatch}
            onPlayAi={handleStartAiGame}
            onPlayOnline={() => setCurrentScreen('ONLINE_CHOICE')}
            onInvitePlayerToMatch={(friendUser) => {
              handleHostRoom();
            }}
            onUpdatePlayer={handleUpdatePlayer}
            onSignOut={handleSignOut}
          />
        )}

        {currentScreen === 'ONLINE_CHOICE' && (
          <OnlineMatchChoiceScreen
            onHostGame={handleHostRoom}
            onJoinGame={() => setCurrentScreen('JOIN_ROOM')}
            onBack={() => setCurrentScreen('MAIN_CONTAINER')}
          />
        )}

        {currentScreen === 'JOIN_ROOM' && (
          <JoinRoomScreen
            onJoinRoom={handleJoinRoom}
            onBack={() => setCurrentScreen('ONLINE_CHOICE')}
          />
        )}

        {currentScreen === 'LOBBY' && localPlayer && (
          <LobbyScreen
            roomCode={roomCode}
            players={playersInLobby}
            localPlayer={localPlayer}
            isHost={playersInLobby.find((p) => p.id === localPlayer.id)?.isHost || false}
            onToggleReady={handleToggleReady}
            onStartGame={handleHostStartLobbyGame}
            onRefresh={() => {
              if (roomCode) {
                CloudRegistry.getRoom(roomCode).catch(() => {});
              }
            }}
            onBack={() => {
              roomSync.leaveRoom();
              setCurrentScreen('MAIN_CONTAINER');
            }}
          />
        )}

        {currentScreen === 'GAME' && localPlayer && (
          <GameScreen
            roomCode={roomCode}
            localPlayer={localPlayer}
            opponent={opponent}
            seed={matchSeed}
            initialTurnPlayerId={firstTurnPlayerId}
            isAiMode={isAiMode}
            onLeaveGame={handleLeaveGame}
          />
        )}
      </div>
    </div>
  );
};

export const App: React.FC = () => {
  return (
    <ThemeProvider>
      <AppContent />
    </ThemeProvider>
  );
};
