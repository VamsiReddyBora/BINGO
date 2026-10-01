import React, { useState, useEffect } from 'react';
import { Player } from './types/models';
import { LoginScreen } from './screens/LoginScreen';
import { MainMenuScreen } from './screens/MainMenuScreen';
import { OnlineMatchChoiceScreen } from './screens/OnlineMatchChoiceScreen';
import { DashboardAndFriendsScreen } from './screens/DashboardAndFriendsScreen';
import { LobbyScreen } from './screens/LobbyScreen';
import { GameScreen } from './screens/GameScreen';
import { roomSync } from './network/mqttSync';
import { CloudRegistry, GameInvite } from './network/cloudRegistry';
import { soundEffects } from './audio/sounds';

export type ScreenState =
  | 'LOGIN'
  | 'MAIN_MENU'
  | 'ONLINE_CHOICE'
  | 'DASHBOARD'
  | 'LOBBY'
  | 'GAME';

const STORAGE_KEY_PLAYER = 'bingo_web_player_v1';

export const App: React.FC = () => {
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
    return localPlayer ? 'MAIN_MENU' : 'LOGIN';
  });

  const [roomCode, setRoomCode] = useState<string>('');
  const [opponent, setOpponent] = useState<Player | null>(null);
  const [matchSeed, setMatchSeed] = useState<number>(0);
  const [firstTurnPlayerId, setFirstTurnPlayerId] = useState<string>('');
  const [isAiMode, setIsAiMode] = useState<boolean>(false);
  const [pendingInvite, setPendingInvite] = useState<GameInvite | null>(null);

  // Initialize global presence and invite listener when player is logged in
  useEffect(() => {
    if (localPlayer) {
      roomSync.initGlobalClient(localPlayer);
      CloudRegistry.publishPresence(localPlayer.username, 'ONLINE');

      // Listen for incoming game invites from Android
      roomSync.onInviteReceived = (invite: GameInvite) => {
        soundEffects.playTurnAlert();
        setPendingInvite(invite);
      };
    }
  }, [localPlayer]);

  // Check URL query parameters for ?room=123456
  useEffect(() => {
    try {
      const params = new URLSearchParams(window.location.search);
      const code = params.get('room');
      if (code && code.trim().length === 6 && localPlayer) {
        handleJoinRoom(code.trim().toUpperCase());
      }
    } catch {}
  }, [localPlayer]);

  // Login Success
  const handleLoginSuccess = (player: Player) => {
    setLocalPlayer(player);
    try {
      localStorage.setItem(STORAGE_KEY_PLAYER, JSON.stringify(player));
    } catch {}
    setCurrentScreen('MAIN_MENU');
  };

  // Sign out
  const handleSignOut = () => {
    if (localPlayer) {
      CloudRegistry.publishPresence(localPlayer.username, 'OFFLINE');
    }
    localStorage.removeItem(STORAGE_KEY_PLAYER);
    setLocalPlayer(null);
    setCurrentScreen('LOGIN');
  };

  // Host Online Room (6-digit code already created and registered in CloudRegistry)
  const handleHostRoom = (code: string) => {
    if (!localPlayer) return;
    const clean = code.trim().toUpperCase();
    setRoomCode(clean);
    setIsAiMode(false);

    const hostPlayer: Player = {
      ...localPlayer,
      isHost: true,
      lobbyReadyStatus: 'READY'
    };
    setLocalPlayer(hostPlayer);

    roomSync.connect(clean, hostPlayer);
    setCurrentScreen('LOBBY');
  };

  // Join Online Room (validated with CloudRegistry)
  const handleJoinRoom = (code: string) => {
    if (!localPlayer) return;
    const clean = code.trim().toUpperCase();
    setRoomCode(clean);
    setIsAiMode(false);

    const guestPlayer: Player = {
      ...localPlayer,
      isHost: false,
      lobbyReadyStatus: 'NOT_READY'
    };
    setLocalPlayer(guestPlayer);

    roomSync.connect(clean, guestPlayer);
    setCurrentScreen('LOBBY');
  };

  // Play AI mode
  const handlePlayAi = (_difficulty: 'EASY' | 'MEDIUM' | 'HARD') => {
    if (!localPlayer) return;
    const aiRoomCode = 'SOLO_AI';
    setRoomCode(aiRoomCode);
    setIsAiMode(true);

    const aiPlayer: Player = {
      id: 'ai_opponent',
      displayName: 'AI Bot 🤖',
      username: 'ai_bot',
      isHost: false,
      avatarUrl: '🤖',
      score: 0,
      completedLinesCount: 0,
      gamesPlayed: 10,
      gamesWon: 5,
      currentStreak: 2,
      level: 3,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'READY',
      readyVersion: 0
    };
    setOpponent(aiPlayer);
    setMatchSeed(Date.now());
    setFirstTurnPlayerId(localPlayer.id);
    setCurrentScreen('GAME');
  };

  // Start match from Lobby
  const handleStartGame = (seed: number, firstTurnId: string, opp: Player) => {
    setMatchSeed(seed);
    setFirstTurnPlayerId(firstTurnId);
    setOpponent(opp);
    setCurrentScreen('GAME');
  };

  // Leave Game or Lobby back to Main Menu
  const handleLeaveToMenu = () => {
    roomSync.disconnect();
    setCurrentScreen('MAIN_MENU');
    setOpponent(null);
    setRoomCode('');
    setIsAiMode(false);
  };

  // Invite player from friends screen
  const handleInvitePlayer = async (targetUsername: string) => {
    if (!localPlayer) return;
    const cleanCode = Math.floor(100000 + Math.random() * 900000).toString();

    // Create room in CloudRegistry
    await CloudRegistry.createRoom(cleanCode, localPlayer, 5);

    // Send invite
    await CloudRegistry.sendInvite(targetUsername, {
      fromUsername: localPlayer.username,
      fromDisplayName: localPlayer.displayName,
      fromAvatarUrl: null,
      roomCode: cleanCode,
      timestamp: Date.now()
    });

    handleHostRoom(cleanCode);
  };

  return (
    <div className="min-h-screen bg-[#0b0f17] text-white flex flex-col justify-center">
      {/* Real-time Match Invitation Dialog */}
      {pendingInvite && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in select-none">
          <div className="w-full max-w-sm bg-slate-900 border border-blue-500/50 rounded-3xl p-6 shadow-2xl text-center">
            <span className="text-3xl mb-2 block">⚔️</span>
            <h3 className="text-lg font-bold text-white">Game Challenge!</h3>
            <p className="mt-2 text-xs text-slate-300">
              <strong className="text-blue-400">@{pendingInvite.fromUsername}</strong> ({pendingInvite.fromDisplayName}) has invited you to play Bingo!
            </p>
            <div className="my-4 p-2.5 rounded-xl bg-slate-800 text-xs font-mono font-bold text-amber-400">
              Room Code: {pendingInvite.roomCode}
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setPendingInvite(null)}
                className="flex-1 py-2.5 rounded-xl bg-slate-800 text-slate-300 font-semibold hover:bg-slate-700 cursor-pointer"
              >
                Decline
              </button>
              <button
                type="button"
                onClick={() => {
                  const code = pendingInvite.roomCode;
                  setPendingInvite(null);
                  handleJoinRoom(code);
                }}
                className="flex-1 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-bold shadow-lg shadow-blue-500/30 cursor-pointer"
              >
                Accept & Join
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Screen 1: Login / Profile Setup */}
      {currentScreen === 'LOGIN' && (
        <LoginScreen onLoginSuccess={handleLoginSuccess} />
      )}

      {/* Screen 2: Main Menu */}
      {currentScreen === 'MAIN_MENU' && localPlayer && (
        <MainMenuScreen
          localPlayer={localPlayer}
          onNavigateToDashboard={() => setCurrentScreen('DASHBOARD')}
          onPlayOnline={() => setCurrentScreen('ONLINE_CHOICE')}
          onPlayAi={handlePlayAi}
          onSignOut={handleSignOut}
        />
      )}

      {/* Screen 3: Online Match Choice */}
      {currentScreen === 'ONLINE_CHOICE' && localPlayer && (
        <OnlineMatchChoiceScreen
          localPlayer={localPlayer}
          onHostRoom={handleHostRoom}
          onJoinRoom={handleJoinRoom}
          onBack={() => setCurrentScreen('MAIN_MENU')}
        />
      )}

      {/* Screen 4: Dashboard & Friends */}
      {currentScreen === 'DASHBOARD' && localPlayer && (
        <DashboardAndFriendsScreen
          localPlayer={localPlayer}
          onBack={() => setCurrentScreen('MAIN_MENU')}
          onInvitePlayerToMatch={handleInvitePlayer}
        />
      )}

      {/* Screen 5: Lobby Screen */}
      {currentScreen === 'LOBBY' && localPlayer && (
        <LobbyScreen
          roomCode={roomCode}
          localPlayer={localPlayer}
          onStartGame={handleStartGame}
          onLeaveLobby={handleLeaveToMenu}
        />
      )}

      {/* Screen 6: Game Screen */}
      {currentScreen === 'GAME' && localPlayer && (
        <GameScreen
          roomCode={roomCode}
          localPlayer={localPlayer}
          opponent={opponent}
          seed={matchSeed}
          initialTurnPlayerId={firstTurnPlayerId}
          isAiMode={isAiMode}
          onLeaveGame={handleLeaveToMenu}
        />
      )}
    </div>
  );
};
