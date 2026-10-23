import React, { useState, useEffect } from 'react';
import { Player } from '../types/models';
import { useTheme } from '../theme/theme';
import { LoginScreen } from './LoginScreen';
import { MainContainer } from './MainContainer';
import { OnlineMatchChoiceScreen } from './OnlineMatchChoiceScreen';
import { JoinRoomScreen } from './JoinRoomScreen';
import { LobbyScreen } from './LobbyScreen';
import { GameScreen } from './GameScreen';
import { MqttRoomManager } from '../network/mqttSync';
import { useMqttSync } from '../network/MqttSyncContext';
import { CloudRegistry, GameInvite } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { useRealtimePing } from '../hooks/useRealtimePing';

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

export interface AppContentProps {
  initialPlayer?: Player | null;
  syncManager?: MqttRoomManager;
  onRoomCreated?: (roomCode: string) => void;
  isMuted?: boolean;
}

export const AppContent: React.FC<AppContentProps> = ({
  initialPlayer,
  syncManager,
  onRoomCreated,
  isMuted = false
}) => {
  const { tokens } = useTheme();
  const sync = syncManager || useMqttSync();

  const [localPlayer, setLocalPlayer] = useState<Player | null>(() => {
    if (initialPlayer) return initialPlayer;
    try {
      const raw = localStorage.getItem(STORAGE_KEY_PLAYER);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed.username && parsed.displayName) return parsed;
      }
    } catch {}
    return null;
  });

  useEffect(() => {
    if (initialPlayer) {
      setLocalPlayer(initialPlayer);
    }
  }, [initialPlayer]);

  const [currentScreen, setCurrentScreen] = useState<ScreenState>(() => {
    return (initialPlayer || localPlayer) ? 'MAIN_CONTAINER' : 'LOGIN';
  });

  // When localPlayer is set (e.g. from initialPlayer), ensure currentScreen is MAIN_CONTAINER
  useEffect(() => {
    if (localPlayer && currentScreen === 'LOGIN') {
      setCurrentScreen('MAIN_CONTAINER');
    }
  }, [localPlayer, currentScreen]);

  const [selectedTab, setSelectedTab] = useState<number>(0);
  const [showExitAppDialog, setShowExitAppDialog] = useState<boolean>(false);
  const [isAppExited, setIsAppExited] = useState<boolean>(false);

  const [ongoingMatch, setOngoingMatch] = useState<OngoingMatch | null>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_ONGOING);
      if (raw) {
        const parsed = JSON.parse(raw);
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

  // Back Button Navigation
  useEffect(() => {
    if (!localPlayer) return;

    try {
      const state = window.history.state;
      if (!state || state.app !== 'bingo') {
        window.history.replaceState({ app: 'bingo', screen: 'MAIN_CONTAINER', tab: 0, depth: 0 }, '');
        window.history.pushState({ app: 'bingo', screen: 'MAIN_CONTAINER', tab: 0, depth: 1 }, '');
      }
    } catch {}

    const handlePopState = (e: PopStateEvent) => {
      const state = e.state;
      if (!state || state.depth === 0 || (currentScreen === 'MAIN_CONTAINER' && selectedTab === 0)) {
        try {
          window.history.pushState({ app: 'bingo', screen: 'MAIN_CONTAINER', tab: 0, depth: 1 }, '');
        } catch {}
        setShowExitAppDialog(true);
        if (!isMuted) soundEffects.playTap();
        return;
      }

      if (currentScreen === 'LOBBY') {
        sync.leaveRoom();
      }

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
  }, [localPlayer, currentScreen, selectedTab, isMuted, sync]);

  // MQTT Listeners & Presence
  useEffect(() => {
    if (!localPlayer) return;

    sync.initGlobalClient(localPlayer);
    CloudRegistry.claimAndRegisterUser(localPlayer).catch(() => {});

    const presenceTimer = setInterval(() => {
      if (localPlayer.username) {
        CloudRegistry.publishPresence(localPlayer.username, 'ONLINE');
      }
    }, 8000);

    const handleBeforeUnload = () => {
      if (localPlayer.username) {
        CloudRegistry.publishPresence(localPlayer.username, 'OFFLINE');
      }
    };
    window.addEventListener('beforeunload', handleBeforeUnload);

    sync.onPlayersChanged = (newPlayers) => {
      setPlayersInLobby(newPlayers);
      const opp = newPlayers.find((p) => p.id !== localPlayer.id);
      if (opp) setOpponent(opp);
    };

    const unsubscribePacket = sync.addPacketListener((packet) => {
      if (packet.type === 'START_GAME') {
        if (!isMuted) soundEffects.playTurnAlert();
        setIsAiMode(false);
        const seed = packet.seed || Math.floor(Math.random() * 100000) + 1;
        setMatchSeed(seed);
        const firstTurn = packet.currentTurnPlayerId || localPlayer.id;
        setFirstTurnPlayerId(firstTurn);

        if (packet.players && packet.players.length > 0) {
          setPlayersInLobby(packet.players);
          const opp = packet.players.find((p) => p.id !== localPlayer.id);
          if (opp) setOpponent(opp);
        }
        navigateTo('GAME', 0);
      }
    });

    sync.onInviteReceived = (invite: GameInvite) => {
      if (!isMuted) soundEffects.playTurnAlert();
      if (confirm(`Game invite received from ${invite.fromDisplayName}! Join room ${invite.roomCode}?`)) {
        handleJoinRoom(invite.roomCode);
      }
    };

    return () => {
      unsubscribePacket();
      clearInterval(presenceTimer);
      window.removeEventListener('beforeunload', handleBeforeUnload);
    };
  }, [localPlayer, isMuted, sync]);

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

  const handleUpdatePlayer = (updated: Player) => {
    setLocalPlayer(updated);
    try {
      localStorage.setItem(STORAGE_KEY_PLAYER, JSON.stringify(updated));
    } catch {}
  };

  const handleLoginSuccess = (player: Player) => {
    handleUpdatePlayer(player);
    navigateTo('MAIN_CONTAINER', 0);
  };

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

  const handleHostRoom = () => {
    if (!localPlayer) return;
    const generated = Math.floor(100000 + Math.random() * 900000).toString();
    setRoomCode(generated);
    setIsAiMode(false);
    onRoomCreated?.(generated);

    const hostPlayer: Player = {
      ...localPlayer,
      isHost: true,
      lobbyReadyStatus: 'READY'
    };
    setPlayersInLobby([hostPlayer]);
    setOpponent(null);

    const ongoing: OngoingMatch = {
      roomCode: generated,
      isHost: true,
      timestamp: Date.now()
    };
    setOngoingMatch(ongoing);
    try {
      localStorage.setItem(STORAGE_KEY_ONGOING, JSON.stringify(ongoing));
    } catch {}

    CloudRegistry.createRoom(generated, hostPlayer).catch(() => {});
    sync.joinRoom(generated, hostPlayer, [hostPlayer]);
    navigateTo('LOBBY', 0);
  };

  const handleJoinRoom = async (code: string) => {
    if (!localPlayer) return;
    const clean = code.trim().toUpperCase();
    setRoomCode(clean);
    setIsAiMode(false);

    const joinerPlayer: Player = {
      ...localPlayer,
      isHost: false,
      lobbyReadyStatus: 'NOT_READY'
    };

    const ongoing: OngoingMatch = {
      roomCode: clean,
      isHost: false,
      timestamp: Date.now()
    };
    setOngoingMatch(ongoing);
    try {
      localStorage.setItem(STORAGE_KEY_ONGOING, JSON.stringify(ongoing));
    } catch {}

    let initialPlayers = [joinerPlayer];
    try {
      const joinRes = await CloudRegistry.validateAndJoinRoom(clean, joinerPlayer);
      if (joinRes && joinRes.success && joinRes.session && joinRes.session.players) {
        initialPlayers = joinRes.session.players;
      }
    } catch {}

    setPlayersInLobby(initialPlayers);
    const opp = initialPlayers.find((p) => p.id !== localPlayer.id) || null;
    setOpponent(opp);

    sync.joinRoom(clean, joinerPlayer, initialPlayers);
    navigateTo('LOBBY', 0);
  };

  const handleInviteFriendToMatch = async (friendUsername: string) => {
    if (!localPlayer) return;
    const generated = Math.floor(100000 + Math.random() * 900000).toString();
    setRoomCode(generated);
    setIsAiMode(false);
    onRoomCreated?.(generated);

    const hostPlayer: Player = {
      ...localPlayer,
      isHost: true,
      lobbyReadyStatus: 'READY'
    };
    setPlayersInLobby([hostPlayer]);
    setOpponent(null);

    CloudRegistry.createRoom(generated, hostPlayer).catch(() => {});
    sync.joinRoom(generated, hostPlayer, [hostPlayer]);

    const invite: GameInvite = {
      fromUsername: localPlayer.username,
      fromDisplayName: localPlayer.displayName,
      fromAvatarUrl: localPlayer.avatarUrl || null,
      roomCode: generated,
      timestamp: Date.now()
    };
    await CloudRegistry.sendInvite(friendUsername, invite, sync);
    navigateTo('LOBBY', 0);
  };

  const handleDismissOngoingMatch = () => {
    setOngoingMatch(null);
    try {
      localStorage.removeItem(STORAGE_KEY_ONGOING);
    } catch {}
  };

  const handleRejoinOngoingMatch = () => {
    if (!ongoingMatch || !localPlayer) return;
    handleJoinRoom(ongoingMatch.roomCode);
  };

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

  const handleHostStartLobbyGame = () => {
    if (!localPlayer) return;
    setIsAiMode(false);
    const generatedSeed = Math.floor(Math.random() * 100000) + 1;
    const starterId = localPlayer.id;

    setMatchSeed(generatedSeed);
    setFirstTurnPlayerId(starterId);

    const opp = playersInLobby.find((p) => p.id !== localPlayer.id) || null;
    if (opp) setOpponent(opp);

    const participants = playersInLobby.length > 0 ? playersInLobby : [localPlayer];
    sync.sendStartGame(generatedSeed, starterId, participants);
    navigateTo('GAME', 0);
  };

  const handleToggleReady = (ready: boolean) => {
    if (!localPlayer) return;
    const status = ready ? 'READY' : 'NOT_READY';
    setPlayersInLobby((prev) =>
      prev.map((p) => (p.id === localPlayer.id ? { ...p, lobbyReadyStatus: status } : p))
    );
    sync.sendReadyStatus(status);
  };

  const handleLeaveGame = () => {
    if (!isAiMode) {
      sync.leaveRoom();
    }
    handleDismissOngoingMatch();
    navigateTo('MAIN_CONTAINER', 0);
  };

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
    <div className="w-full min-h-full flex flex-col justify-center items-center bg-black">
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
            onInvitePlayerToMatch={handleInviteFriendToMatch}
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
            syncManager={sync}
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
              sync.leaveRoom();
              navigateTo('MAIN_CONTAINER', 0);
            }}
          />
        )}

        {currentScreen === 'GAME' && localPlayer && (
          <GameScreen
            roomCode={roomCode}
            localPlayer={localPlayer}
            opponent={opponent}
            participants={playersInLobby}
            syncManager={sync}
            seed={matchSeed}
            initialTurnPlayerId={firstTurnPlayerId}
            isAiMode={isAiMode}
            onLeaveGame={handleLeaveGame}
            onReturnToLobby={() => navigateTo('LOBBY', 0)}
            onUpdatePlayerStats={handleUpdatePlayer}
          />
        )}

        {/* ── System Exit Confirmation Modal ── */}
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
                    if (!isMuted) soundEffects.playTap();
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
                    if (!isMuted) soundEffects.playTap();
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
