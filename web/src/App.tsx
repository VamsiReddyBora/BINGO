import React, { useState, useEffect } from 'react';
import { Player } from './types/models';
import { ThemeProvider, useTheme } from './theme/theme';
import { LoginScreen } from './screens/LoginScreen';
import { MainContainer } from './screens/MainContainer';
import { OnlineMatchChoiceScreen } from './screens/OnlineMatchChoiceScreen';
import { JoinRoomScreen } from './screens/JoinRoomScreen';
import { LobbyScreen } from './screens/LobbyScreen';
import { GameScreen } from './screens/GameScreen';
import { roomSync } from './network/mqttSync';
import { CloudRegistry, GameInvite } from './network/cloudRegistry';
import { soundEffects } from './audio/sounds';
import { useRealtimePing } from './hooks/useRealtimePing';

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
  const { tokens } = useTheme();

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

  const [selectedTab, setSelectedTab] = useState<number>(0);
  const [showExitAppDialog, setShowExitAppDialog] = useState<boolean>(false);
  const [isAppExited, setIsAppExited] = useState<boolean>(false);

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
  const pingMs = useRealtimePing();

  // Unified navigation helper with browser history state
  const navigateTo = (screen: ScreenState, tab: number = 0) => {
    setCurrentScreen(screen);
    setSelectedTab(tab);
    try {
      const isHome = screen === 'MAIN_CONTAINER' && tab === 0;
      window.history.pushState(
        { app: 'bingo', screen, tab, depth: isHome ? 1 : 2 },
        ''
      );
    } catch {}
  };

  const handleSelectTab = (tab: number) => {
    if (currentScreen === 'MAIN_CONTAINER' && selectedTab === tab) return;
    navigateTo('MAIN_CONTAINER', tab);
  };

  // Back Button Navigation & Accidental Exit Interceptor (Matching Android BackHandler)
  useEffect(() => {
    if (!localPlayer) return;

    // Initialize baseline sentinel state
    try {
      const state = window.history.state;
      if (!state || state.app !== 'bingo') {
        window.history.replaceState({ app: 'bingo', screen: 'MAIN_CONTAINER', tab: 0, depth: 0 }, '');
        window.history.pushState({ app: 'bingo', screen: 'MAIN_CONTAINER', tab: 0, depth: 1 }, '');
      }
    } catch {}

    const handlePopState = (e: PopStateEvent) => {
      const state = e.state;

      // If user is on Home screen (MAIN_CONTAINER tab 0) and pressed back:
      // Prevent exiting the browser! Re-push state and show exit dialog.
      if (!state || state.depth === 0 || (currentScreen === 'MAIN_CONTAINER' && selectedTab === 0)) {
        try {
          window.history.pushState({ app: 'bingo', screen: 'MAIN_CONTAINER', tab: 0, depth: 1 }, '');
        } catch {}
        setShowExitAppDialog(true);
        soundEffects.playTap();
        return;
      }

      // If leaving lobby, notify peers
      if (currentScreen === 'LOBBY') {
        roomSync.leaveRoom();
      }

      // If currently on a sub-screen or tab (Dashboard / Settings / Choice / Lobby / Game),
      // return to the Home screen (MAIN_CONTAINER tab 0) or the prior recorded screen.
      if (state.screen) {
        setCurrentScreen(state.screen);
        setSelectedTab(typeof state.tab === 'number' ? state.tab : 0);
      } else {
        setCurrentScreen('MAIN_CONTAINER');
        setSelectedTab(0);
      }
    };

    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, [localPlayer, currentScreen, selectedTab]);

  // Initialize global presence and MQTT listeners
  useEffect(() => {
    if (localPlayer) {
      roomSync.initGlobalClient(localPlayer);
      CloudRegistry.publishPresence(localPlayer.username, 'ONLINE');

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
          navigateTo('GAME', 0);
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
    navigateTo('MAIN_CONTAINER', 0);
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
    navigateTo('LOBBY', 0);
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
    navigateTo('LOBBY', 0);
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
    navigateTo('GAME', 0);
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
    navigateTo('GAME', 0);
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
    navigateTo('MAIN_CONTAINER', 0);
  };

  // Exit App confirmation handler
  const handleConfirmExitApp = () => {
    setShowExitAppDialog(false);
    try {
      window.close();
    } catch {}
    setIsAppExited(true);
  };

  if (isAppExited) {
    return (
      <div
        style={{ backgroundColor: tokens.background }}
        className="w-full min-h-[100dvh] flex flex-col items-center justify-center p-6 text-center select-none"
      >
        <div
          style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
          className="w-full max-w-xs rounded-3xl p-6 border shadow-2xl flex flex-col items-center"
        >
          <div className="w-16 h-16 rounded-full bg-emerald-500/10 text-emerald-500 flex items-center justify-center text-3xl mb-4">
            👋
          </div>
          <h2 style={{ color: tokens.cellNeutralText }} className="text-lg font-bold mb-2">
            You've Exited Bingo
          </h2>
          <p style={{ color: tokens.textMuted }} className="text-xs mb-6 leading-relaxed">
            You can now safely close this tab or return to your device home screen.
          </p>
          <button
            type="button"
            onClick={() => {
              setIsAppExited(false);
              navigateTo('MAIN_CONTAINER', 0);
            }}
            style={{
              backgroundColor: tokens.primaryButtonBg,
              color: tokens.primaryButtonText
            }}
            className="w-full py-3 rounded-xl font-bold text-xs cursor-pointer active:scale-95 shadow-md"
          >
            Reopen Bingo
          </button>
        </div>
      </div>
    );
  }

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
            selectedTab={selectedTab}
            onSelectTab={handleSelectTab}
            onDismissOngoingMatch={handleDismissOngoingMatch}
            onRejoinOngoingMatch={handleRejoinOngoingMatch}
            onPlayAi={handleStartAiGame}
            onPlayOnline={() => navigateTo('ONLINE_CHOICE', 0)}
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
            onJoinGame={() => navigateTo('JOIN_ROOM', 0)}
            onBack={() => navigateTo('MAIN_CONTAINER', 0)}
          />
        )}

        {currentScreen === 'JOIN_ROOM' && (
          <JoinRoomScreen
            onJoinRoom={handleJoinRoom}
            onBack={() => navigateTo('ONLINE_CHOICE', 0)}
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
              navigateTo('MAIN_CONTAINER', 0);
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
            onReturnToLobby={() => navigateTo('LOBBY', 0)}
            onUpdatePlayerStats={handleUpdatePlayer}
          />
        )}

        {/* ── System Exit Confirmation Modal (Triggered on Home Screen back button) ── */}
        {showExitAppDialog && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in select-none">
            <div
              style={{
                backgroundColor: tokens.surface,
                borderColor: tokens.surfaceBorder
              }}
              className="w-full max-w-xs rounded-3xl p-6 border shadow-2xl flex flex-col items-center text-center animate-scale-up"
            >
              <div
                style={{
                  backgroundColor: `${tokens.accentBrand}20`,
                  color: tokens.accentBrand
                }}
                className="w-14 h-14 rounded-2xl flex items-center justify-center text-2xl mb-4 shadow-sm"
              >
                🚪
              </div>

              <h3 style={{ color: tokens.cellNeutralText }} className="font-extrabold text-lg mb-1">
                Exit Bingo?
              </h3>
              <p style={{ color: tokens.textMuted }} className="text-xs mb-6 leading-relaxed">
                Are you sure you want to exit the app?
              </p>

              <div className="w-full flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => {
                    soundEffects.playTap();
                    setShowExitAppDialog(false);
                  }}
                  style={{
                    backgroundColor: tokens.surface,
                    borderColor: tokens.surfaceBorder,
                    color: tokens.cellNeutralText
                  }}
                  className="flex-1 py-3 px-4 rounded-xl border font-bold text-xs cursor-pointer active:scale-95 transition-transform"
                >
                  Stay
                </button>

                <button
                  type="button"
                  onClick={() => {
                    soundEffects.playTap();
                    handleConfirmExitApp();
                  }}
                  style={{
                    backgroundColor: '#DC2626',
                    color: '#FFFFFF'
                  }}
                  className="flex-1 py-3 px-4 rounded-xl font-bold text-xs cursor-pointer active:scale-95 transition-transform shadow-md"
                >
                  Exit
                </button>
              </div>
            </div>
          </div>
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
