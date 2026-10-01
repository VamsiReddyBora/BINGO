import React, { useState, useEffect } from 'react';
import { Player, ScreenType } from './types/models';
import { MainMenuScreen } from './screens/MainMenuScreen';
import { LobbyScreen } from './screens/LobbyScreen';
import { GameScreen } from './screens/GameScreen';
import { roomSync } from './network/mqttSync';

const STORAGE_KEY_PLAYER = 'bingo_web_player_v1';

function getInitialPlayer(): Player {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_PLAYER);
    if (raw) {
      return JSON.parse(raw);
    }
  } catch {}

  const randomDigits = Math.floor(1000 + Math.random() * 9000);
  const defaultPlayer: Player = {
    id: `web_player_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
    displayName: `Player ${randomDigits}`,
    username: `player_${randomDigits}`,
    isHost: false,
    score: 0,
    completedLinesCount: 0,
    gamesPlayed: 0,
    gamesWon: 0,
    currentStreak: 0,
    level: 1,
    avatarUrl: '🧑',
    lastSeenTimestamp: Date.now(),
    lobbyReadyStatus: 'NOT_READY',
    readyVersion: 0
  };
  return defaultPlayer;
}

export const App: React.FC = () => {
  const [currentScreen, setCurrentScreen] = useState<ScreenType>('MAIN_MENU');
  const [localPlayer, setLocalPlayer] = useState<Player>(getInitialPlayer);
  const [roomCode, setRoomCode] = useState<string>('');
  const [opponent, setOpponent] = useState<Player | null>(null);
  const [matchSeed, setMatchSeed] = useState<number>(0);
  const [firstTurnPlayerId, setFirstTurnPlayerId] = useState<string>('');
  const [isAiMode, setIsAiMode] = useState<boolean>(false);
  const [urlRoomCode, setUrlRoomCode] = useState<string | null>(null);

  // Check URL query parameters for ?room=123456
  useEffect(() => {
    try {
      const params = new URLSearchParams(window.location.search);
      const code = params.get('room');
      if (code && code.trim().length >= 4) {
        setUrlRoomCode(code.trim().toUpperCase());
      }
    } catch {}
  }, []);

  // Save player profile changes to localStorage
  const handleUpdatePlayer = (updated: Player) => {
    setLocalPlayer(updated);
    try {
      localStorage.setItem(STORAGE_KEY_PLAYER, JSON.stringify(updated));
    } catch {}
  };

  // Create room as Host
  const handleCreateRoom = () => {
    const generatedCode = Math.floor(100000 + Math.random() * 900000).toString();
    setRoomCode(generatedCode);
    setIsAiMode(false);

    const hostPlayer: Player = {
      ...localPlayer,
      isHost: true,
      lobbyReadyStatus: 'READY'
    };
    setLocalPlayer(hostPlayer);

    roomSync.connect(generatedCode, hostPlayer);
    setCurrentScreen('LOBBY');
  };

  // Join room as Guest
  const handleJoinRoom = (code: string) => {
    const cleanCode = code.trim().toUpperCase();
    setRoomCode(cleanCode);
    setIsAiMode(false);

    const guestPlayer: Player = {
      ...localPlayer,
      isHost: false,
      lobbyReadyStatus: 'NOT_READY'
    };
    setLocalPlayer(guestPlayer);

    roomSync.connect(cleanCode, guestPlayer);
    setCurrentScreen('LOBBY');
  };

  // Start AI practice match
  const handlePlayAi = () => {
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

  // Transition from Lobby to Game
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

  return (
    <div className="min-h-screen bg-[#0b0f17] text-white flex flex-col justify-center">
      {currentScreen === 'MAIN_MENU' && (
        <MainMenuScreen
          localPlayer={localPlayer}
          onUpdatePlayer={handleUpdatePlayer}
          onCreateRoom={handleCreateRoom}
          onJoinRoom={handleJoinRoom}
          onPlayAi={handlePlayAi}
          initialRoomCode={urlRoomCode}
        />
      )}

      {currentScreen === 'LOBBY' && (
        <LobbyScreen
          roomCode={roomCode}
          localPlayer={localPlayer}
          onStartGame={handleStartGame}
          onLeaveLobby={handleLeaveToMenu}
        />
      )}

      {currentScreen === 'GAME' && (
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
