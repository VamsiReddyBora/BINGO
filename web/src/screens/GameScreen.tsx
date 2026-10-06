import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import {
  LogOut,
  Wifi,
  Pause,
  Play,
  Volume2,
  VolumeX,
  MessageSquare,
  Crown,
  RotateCcw,
  RotateCw
} from 'lucide-react';
import {
  Player,
  Board,
  FloatingEmoteItem,
  RoomMessagePacket,
  InGameChatMessage,
  MatchRecord,
  CloudUserDataBackup
} from '../types/models';
import { BingoEngine } from '../engine/bingoEngine';
import { roomSync } from '../network/mqttSync';
import { CloudRegistry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { useTheme } from '../theme/theme';
import { EmojiPreferences } from '../services/emojiPreferences';
import { BingoBoardView } from '../components/BingoBoardView';
import { InGameChatSpace } from '../components/InGameChatSpace';
import { QuickChatDrawer } from '../components/QuickChatDrawer';
import { FloatingEmotes } from '../components/FloatingEmotes';
import { PlayerAvatar } from '../components/PlayerAvatar';
import { RecentPicksQueuePill } from '../components/RecentPicksQueuePill';
import { useRealtimePing } from '../hooks/useRealtimePing';

interface Props {
  roomCode: string;
  localPlayer: Player;
  opponent: Player | null;
  seed: number;
  initialTurnPlayerId: string;
  isAiMode?: boolean;
  onLeaveGame: () => void;
  onReturnToLobby?: () => void;
  onUpdatePlayerStats?: (updated: Player) => void;
}

export const GameScreen: React.FC<Props> = ({
  roomCode,
  localPlayer,
  opponent,
  seed,
  initialTurnPlayerId,
  isAiMode = false,
  onLeaveGame,
  onReturnToLobby,
  onUpdatePlayerStats
}) => {
  const { tokens, isDark } = useTheme();

  // CRITICAL: AI mode is strictly ONLY active if roomCode is explicitly 'AI_SOLO' AND isAiMode is true.
  // In an online room match (6-digit room code or P2P code), effectiveAiMode is ALWAYS false.
  const effectiveAiMode = Boolean(isAiMode && roomCode === 'AI_SOLO');

  // Resolve authentic opponent from prop or MQTT player registry
  const resolvedOpponent = useMemo(() => {
    if (opponent && opponent.id && opponent.id !== localPlayer.id) return opponent;
    const fromMqtt = roomSync.getPlayers().find((p) => p.id !== localPlayer.id);
    if (fromMqtt) return fromMqtt;
    return opponent;
  }, [opponent, localPlayer.id]);

  const mySeed = effectiveAiMode
    ? (localPlayer.isHost ? seed : seed + 1)
    : BingoEngine.resolvePlayerBoardSeed(seed, localPlayer, localPlayer.isHost ? 0 : 1);
  const oppSeed = effectiveAiMode
    ? (localPlayer.isHost ? seed + 1 : seed)
    : (resolvedOpponent
        ? BingoEngine.resolvePlayerBoardSeed(seed, resolvedOpponent, resolvedOpponent.isHost ? 0 : 1)
        : BingoEngine.resolvePlayerBoardSeed(seed, { id: 'guest', username: 'guest', isHost: !localPlayer.isHost }, localPlayer.isHost ? 1 : 0));

  const [board, setBoard] = useState<Board>(() => BingoEngine.generateBoard(5, mySeed));
  const [opponentBoard, setOpponentBoard] = useState<Board>(() => BingoEngine.generateBoard(5, oppSeed));
  const [currentTurnPlayerId, setCurrentTurnPlayerId] = useState<string>(() => {
    if (initialTurnPlayerId && initialTurnPlayerId.trim()) return initialTurnPlayerId;
    if (effectiveAiMode) return localPlayer.id;
    return localPlayer.isHost ? localPlayer.id : (resolvedOpponent?.id || 'host');
  });
  const [turnNumber, setTurnNumber] = useState<number>(1);
  const [turnTimer, setTurnTimer] = useState<number>(30);
  const [pickedNumbersHistory, setPickedNumbersHistory] = useState<number[]>([]);
  const [myLinesCount, setMyLinesCount] = useState<number>(0);
  const [opponentLinesCount, setOpponentLinesCount] = useState<number>(0);

  const [isGameOver, setIsGameOver] = useState<boolean>(false);
  const [isWinner, setIsWinner] = useState<boolean>(false);
  const [winnerName, setWinnerName] = useState<string>('');
  const [wantsPlayAgainName, setWantsPlayAgainName] = useState<string | null>(null);

  // Review tab during Game Over: 'local' | 'opponent'
  const [selectedReviewBoard, setSelectedReviewBoard] = useState<'local' | 'opponent'>('local');

  // Pause state
  const [isGamePaused, setIsGamePaused] = useState<boolean>(false);
  const [pausedByPlayerName, setPausedByPlayerName] = useState<string>('');

  // Modals
  const [showExitDialog, setShowExitDialog] = useState<boolean>(false);

  // Chat & reactions
  const [activeEmotes, setActiveEmotes] = useState<FloatingEmoteItem[]>([]);
  const [chatMessages, setChatMessages] = useState<InGameChatMessage[]>([]);
  const [isQuickChatOpen, setIsQuickChatOpen] = useState<boolean>(false);
  const [soundOn, setSoundOn] = useState<boolean>(soundEffects.isEnabled());

  // 1-second real-time network ping monitor directly matching Android NetworkPingMonitor
  const realtimePing = useRealtimePing();
  const displayedPing = realtimePing > 0 ? realtimePing : 28;

  // Animated dots for "choosing..."
  const [dotPhase, setDotPhase] = useState<number>(1);
  useEffect(() => {
    if (isGameOver) return;
    const interval = setInterval(() => {
      setDotPhase((prev) => (prev % 3) + 1);
    }, 500);
    return () => clearInterval(interval);
  }, [isGameOver]);

  const animatedDots = '.'.repeat(dotPhase);
  const isMyTurn = currentTurnPlayerId === localPlayer.id;

  // Sound toggle
  const toggleSound = () => {
    const next = !soundOn;
    setSoundOn(next);
    soundEffects.setSoundEnabled(next);
  };

  // Spawn visual floating emote bubble
  const spawnEmote = useCallback(
    (emoji: string, isSelf: boolean, senderName?: string, scaleMultiplier: number = 1.0) => {
      const randomX = (10 + Math.random() * 75) / 100;
      const newItem: FloatingEmoteItem = {
        id: `${Date.now()}_${Math.random()}`,
        emoji,
        startXRatio: randomX,
        isSelf,
        senderName,
        scaleMultiplier,
        createdAt: Date.now()
      };
      setActiveEmotes((prev) => [...prev.slice(-15), newItem]);
    },
    []
  );

  const removeEmote = useCallback((id: string) => {
    setActiveEmotes((prev) => prev.filter((e) => e.id !== id));
  }, []);

  // Sync game stats on game over & update cloud record
  const syncMatchResults = useCallback(
    (won: boolean, oppName: string) => {
      const newPlayed = (localPlayer.gamesPlayed || 0) + 1;
      const newWon = won ? (localPlayer.gamesWon || 0) + 1 : (localPlayer.gamesWon || 0);
      const newStreak = won ? (localPlayer.currentStreak || 0) + 1 : 0;
      const newLevel = Math.max(1, Math.floor(newWon / 4) + 1);

      const updatedPlayer: Player = {
        ...localPlayer,
        gamesPlayed: newPlayed,
        gamesWon: newWon,
        currentStreak: newStreak,
        level: newLevel
      };

      onUpdatePlayerStats?.(updatedPlayer);

      // Save match record locally
      const matchRecord: MatchRecord = {
        id: `match_${Date.now()}`,
        roomCode,
        opponentName: oppName,
        opponentAvatarUrl: opponent?.avatarUrl || null,
        result: won ? 'WIN' : 'LOSS',
        myLinesCompleted: myLinesCount,
        opponentLinesCompleted: opponentLinesCount,
        timestamp: Date.now(),
        boardSize: 5
      };

      try {
        const rawHistory = localStorage.getItem('bingo_web_match_history_v1');
        const history: MatchRecord[] = rawHistory ? JSON.parse(rawHistory) : [];
        const nextHistory = [matchRecord, ...history].slice(0, 50);
        localStorage.setItem('bingo_web_match_history_v1', JSON.stringify(nextHistory));

        // Sync full backup to cloud (ExtendsClass + KeyVal) matching Android AccountSessionManager
        const backup: CloudUserDataBackup = {
          profile: {
            uid: updatedPlayer.id,
            username: updatedPlayer.username,
            displayName: updatedPlayer.displayName,
            avatarUrl: updatedPlayer.avatarUrl,
            gamesPlayed: newPlayed,
            gamesWon: newWon,
            currentStreak: newStreak,
            bestStreak: Math.max(newStreak, updatedPlayer.gamesWon || 0),
            level: newLevel,
            xp: 0,
            email: updatedPlayer.email
          },
          matchHistory: nextHistory,
          lastBackupTimestamp: Date.now()
        };

        const targetId = updatedPlayer.googleId || updatedPlayer.username;
        CloudRegistry.saveUserDataBackup(targetId, backup).catch(() => {});
      } catch (err) {
        console.warn('Failed to sync match record to cloud:', err);
      }
    },
    [localPlayer, onUpdatePlayerStats, opponent?.avatarUrl, myLinesCount, opponentLinesCount, roomCode]
  );

  // Turn timer countdown
  useEffect(() => {
    if (isGameOver || isGamePaused) return;

    const timer = setInterval(() => {
      setTurnTimer((prev) => {
        if (prev <= 1) {
          // Timeout switch turn
          if (isMyTurn) {
            const nextTurnId = effectiveAiMode ? 'ai_bot' : resolvedOpponent?.id || 'opponent';
            setCurrentTurnPlayerId(nextTurnId);
            setTurnNumber((t) => t + 1);
            soundEffects.playTurnAlert();

            if (!effectiveAiMode) {
              roomSync.sendTurnTimeout(turnNumber, nextTurnId, pickedNumbersHistory, seed);
            }
          }
          return 30;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, [isGameOver, isGamePaused, isMyTurn, effectiveAiMode, resolvedOpponent?.id, turnNumber, pickedNumbersHistory, seed]);

  // Execute pick
  const handlePickNumber = useCallback(
    (number: number) => {
      if (!isMyTurn || isGameOver || isGamePaused) return;
      if (pickedNumbersHistory.includes(number)) return;

      // 1. Mark on My Board
      const { board: newBoard, newLinesCompleted } = BingoEngine.markCell(
        board,
        number,
        localPlayer.id,
        true,
        turnNumber
      );
      setBoard(newBoard);
      const newMyLines = newBoard.completedLines.length;
      setMyLinesCount(newMyLines);

      // 2. Mark on Opponent Board (so both boards accurately track all picked numbers!)
      const { board: newOppBoard } = BingoEngine.markCell(
        opponentBoard,
        number,
        localPlayer.id,
        false,
        turnNumber
      );
      setOpponentBoard(newOppBoard);
      const newOppLines = newOppBoard.completedLines.length;
      setOpponentLinesCount(newOppLines);

      const updatedHistory = [...pickedNumbersHistory, number];
      setPickedNumbersHistory(updatedHistory);

      if (newLinesCompleted > 0) {
        soundEffects.playLineComplete();
      }

      // Check win condition
      if (newMyLines >= 5) {
        soundEffects.playBingoWin();
        setIsGameOver(true);
        setIsWinner(true);
        setWinnerName(localPlayer.displayName);
        syncMatchResults(true, resolvedOpponent?.displayName || (effectiveAiMode ? 'AI Bot' : 'Opponent'));

        if (!effectiveAiMode) {
          roomSync.sendPacket({
            type: 'BINGO_CLAIMED',
            playerId: localPlayer.id,
            displayName: localPlayer.displayName,
            number
          });
        }
        return;
      }

      // Switch turn
      const nextTurnId = effectiveAiMode ? 'ai_bot' : resolvedOpponent?.id || 'opponent';
      setCurrentTurnPlayerId(nextTurnId);
      setTurnNumber((prev) => prev + 1);
      setTurnTimer(30);

      // Broadcast move to opponent
      if (!effectiveAiMode) {
        roomSync.sendPick(number, turnNumber, nextTurnId, updatedHistory, seed);
      }
    },
    [
      board,
      effectiveAiMode,
      isGameOver,
      isGamePaused,
      isMyTurn,
      localPlayer,
      resolvedOpponent,
      opponentBoard,
      pickedNumbersHistory,
      turnNumber,
      syncMatchResults,
      seed
    ]
  );

  // AI Turn Logic (STRICTLY ONLY for solo AI mode against bot, NEVER in online room match)
  useEffect(() => {
    if (!effectiveAiMode || isGameOver || isGamePaused || isMyTurn) return;

    const timer = setTimeout(() => {
      // Find available unmarked numbers on AI board
      const available = opponentBoard.cells
        .filter((c) => c.markState.type === 'Unmarked')
        .map((c) => c.number);

      if (available.length === 0) return;

      // Smart bot picking
      const pick = available[Math.floor(Math.random() * available.length)];

      // 1. Mark on AI board
      const { board: newOppBoard } = BingoEngine.markCell(
        opponentBoard,
        pick,
        'ai_bot',
        true,
        turnNumber
      );
      setOpponentBoard(newOppBoard);
      const newOppLines = newOppBoard.completedLines.length;
      setOpponentLinesCount(newOppLines);

      // 2. Mark on player board
      const { board: newMyBoard, newLinesCompleted } = BingoEngine.markCell(
        board,
        pick,
        'ai_bot',
        false,
        turnNumber
      );
      setBoard(newMyBoard);
      const newMyLines = newMyBoard.completedLines.length;
      setMyLinesCount(newMyLines);

      setPickedNumbersHistory((prev) => [...prev, pick]);

      if (newLinesCompleted > 0) {
        soundEffects.playLineComplete();
      }

      if (newOppLines >= 5) {
        soundEffects.playGameOver();
        setIsGameOver(true);
        setIsWinner(false);
        setWinnerName('Master Bot');
        syncMatchResults(false, 'Master Bot');
        return;
      } else if (newMyLines >= 5) {
        soundEffects.playBingoWin();
        setIsGameOver(true);
        setIsWinner(true);
        setWinnerName(localPlayer.displayName);
        syncMatchResults(true, 'Master Bot');
        return;
      }

      setCurrentTurnPlayerId(localPlayer.id);
      setTurnNumber((prev) => prev + 1);
      setTurnTimer(30);
      soundEffects.playTurnAlert();
    }, 1200);

    return () => clearTimeout(timer);
  }, [
    board,
    currentTurnPlayerId,
    effectiveAiMode,
    isGameOver,
    isGamePaused,
    isMyTurn,
    localPlayer,
    opponentBoard,
    turnNumber,
    syncMatchResults
  ]);

  // Handle incoming MQTT packets
  useEffect(() => {
    if (effectiveAiMode) return;

    const unsubscribe = roomSync.addPacketListener((packet: RoomMessagePacket) => {
      if (packet.type === 'PICK_NUMBER' && packet.number) {
        const pickNum = packet.number;
        const incomingHistory = packet.pickedHistory || [pickNum];

        let newMyLines = 0;
        let newOppLines = 0;

        // 1. Mark on My Board (isOwnPick = false)
        setBoard((prevMy) => {
          let cur = prevMy;
          incomingHistory.forEach((num) => {
            if (num > 0 && !cur.cells.find((c) => c.number === num && c.markState.type === 'Marked')) {
              const res = BingoEngine.markCell(
                cur,
                num,
                packet.playerId,
                false,
                packet.turnNumber || turnNumber
              );
              cur = res.board;
              if (res.newLinesCompleted > 0) {
                soundEffects.playLineComplete();
              }
            }
          });
          newMyLines = cur.completedLines.length;
          setMyLinesCount(newMyLines);
          return cur;
        });

        // 2. Mark on Opponent Board (isOwnPick = true)
        setOpponentBoard((prevOpp) => {
          let cur = prevOpp;
          incomingHistory.forEach((num) => {
            if (num > 0 && !cur.cells.find((c) => c.number === num && c.markState.type === 'Marked')) {
              const res = BingoEngine.markCell(
                cur,
                num,
                packet.playerId,
                true,
                packet.turnNumber || turnNumber
              );
              cur = res.board;
            }
          });
          newOppLines = cur.completedLines.length;
          setOpponentLinesCount(newOppLines);
          return cur;
        });

        setPickedNumbersHistory((prev) => Array.from(new Set([...prev, ...incomingHistory, pickNum])));

        // 3. Evaluate win conditions
        if (newOppLines >= 5) {
          soundEffects.playGameOver();
          setIsGameOver(true);
          setIsWinner(false);
          setWinnerName(packet.displayName || resolvedOpponent?.displayName || 'Opponent');
          syncMatchResults(false, packet.displayName || resolvedOpponent?.displayName || 'Opponent');
          return;
        } else if (newMyLines >= 5) {
          soundEffects.playBingoWin();
          setIsGameOver(true);
          setIsWinner(true);
          setWinnerName(localPlayer.displayName);
          syncMatchResults(true, packet.displayName || resolvedOpponent?.displayName || 'Opponent');
          return;
        }

        setCurrentTurnPlayerId(localPlayer.id);
        setTurnNumber((prev) => (packet.turnNumber ? packet.turnNumber + 1 : prev + 1));
        setTurnTimer(30);
        soundEffects.playTurnAlert();
      } else if (packet.type === 'TURN_TIMEOUT') {
        setCurrentTurnPlayerId(localPlayer.id);
        setTurnNumber((prev) => (packet.turnNumber ? packet.turnNumber + 1 : prev + 1));
        setTurnTimer(30);
        soundEffects.playTurnAlert();
      } else if (packet.type === 'BINGO_CLAIMED') {
        soundEffects.playGameOver();
        setIsGameOver(true);
        setIsWinner(false);
        setWinnerName(packet.displayName || resolvedOpponent?.displayName || 'Opponent');
        syncMatchResults(false, packet.displayName || resolvedOpponent?.displayName || 'Opponent');
      } else if (packet.type === 'EMOTE' && packet.payload) {
        spawnEmote(packet.payload, false, packet.displayName || resolvedOpponent?.displayName || 'Opponent');
        soundEffects.playEmotePop();
      } else if (packet.type === 'CHAT_MESSAGE' && packet.payload) {
        setChatMessages((prev) => [
          ...prev,
          {
            id: `${Date.now()}_${Math.random()}`,
            text: packet.payload!,
            isSelf: false,
            senderName: packet.displayName || resolvedOpponent?.displayName || 'Opponent',
            timestamp: Date.now()
          }
        ]);
        soundEffects.playTap();
      } else if (packet.type === 'PLAY_AGAIN') {
        setWantsPlayAgainName(packet.displayName || resolvedOpponent?.displayName || 'Opponent');
        if (packet.seed) {
          const newSeed = packet.seed;
          const myNewSeed = BingoEngine.resolvePlayerBoardSeed(newSeed, localPlayer, localPlayer.isHost ? 0 : 1);
          const oppNewSeed = resolvedOpponent
            ? BingoEngine.resolvePlayerBoardSeed(newSeed, resolvedOpponent, resolvedOpponent.isHost ? 0 : 1)
            : BingoEngine.resolvePlayerBoardSeed(newSeed, { id: 'guest', username: 'guest', isHost: !localPlayer.isHost }, localPlayer.isHost ? 1 : 0);
          setBoard(BingoEngine.generateBoard(5, myNewSeed));
          setOpponentBoard(BingoEngine.generateBoard(5, oppNewSeed));
          setIsGameOver(false);
          setIsWinner(false);
          setWinnerName('');
          setTurnNumber(1);
          setTurnTimer(30);
          setPickedNumbersHistory([]);
          setMyLinesCount(0);
          setOpponentLinesCount(0);
          setSelectedReviewBoard('local');
          setCurrentTurnPlayerId(packet.playerId || localPlayer.id);
        }
      } else if (packet.type === 'PAUSE_GAME') {
        setIsGamePaused(true);
        setPausedByPlayerName(packet.displayName || resolvedOpponent?.displayName || 'Opponent');
      } else if (packet.type === 'RESUME_GAME') {
        setIsGamePaused(false);
        setPausedByPlayerName('');
      } else if (packet.type === 'SURRENDER' || packet.type === 'LEAVE') {
        soundEffects.playBingoWin();
        setIsGameOver(true);
        setIsWinner(true);
        setWinnerName(localPlayer.displayName);
        syncMatchResults(true, packet.displayName || resolvedOpponent?.displayName || 'Opponent');
        alert(`${packet.displayName || resolvedOpponent?.displayName || 'Opponent'} left the game. You win! 🏆`);
      }
    });

    return () => {
      unsubscribe();
    };
  }, [
    effectiveAiMode,
    localPlayer,
    resolvedOpponent,
    turnNumber,
    spawnEmote,
    syncMatchResults
  ]);

  // Send Emote
  const handleSendEmote = (emoji: string, scale: number = 1.0) => {
    spawnEmote(emoji, true, localPlayer.displayName, scale);
    soundEffects.playEmotePop();
    if (!effectiveAiMode) {
      roomSync.sendPacket({
        type: 'EMOTE',
        playerId: localPlayer.id,
        displayName: localPlayer.displayName,
        payload: emoji
      });
    }
  };

  // Send Chat Message
  const handleSendChatMessage = (text: string) => {
    setChatMessages((prev) => [
      ...prev,
      {
        id: `${Date.now()}_${Math.random()}`,
        text,
        isSelf: true,
        senderName: localPlayer.displayName,
        timestamp: Date.now()
      }
    ]);
    if (!effectiveAiMode) {
      roomSync.sendPacket({
        type: 'CHAT_MESSAGE',
        playerId: localPlayer.id,
        displayName: localPlayer.displayName,
        payload: text
      });
    }
  };

  // Toggle Pause
  const handleTogglePause = () => {
    const nextState = !isGamePaused;
    setIsGamePaused(nextState);
    if (!effectiveAiMode) {
      roomSync.sendPacket({
        type: nextState ? 'PAUSE_GAME' : 'RESUME_GAME',
        playerId: localPlayer.id,
        displayName: localPlayer.displayName
      });
    }
  };

  // Play Again
  const handlePlayAgain = () => {
    const newSeed = Math.floor(Math.random() * 100000) + 1;
    const myNewSeed = effectiveAiMode
      ? (localPlayer.isHost ? newSeed : newSeed + 1)
      : BingoEngine.resolvePlayerBoardSeed(newSeed, localPlayer, localPlayer.isHost ? 0 : 1);
    const oppNewSeed = effectiveAiMode
      ? (localPlayer.isHost ? newSeed + 1 : newSeed)
      : (resolvedOpponent
          ? BingoEngine.resolvePlayerBoardSeed(newSeed, resolvedOpponent, resolvedOpponent.isHost ? 0 : 1)
          : BingoEngine.resolvePlayerBoardSeed(newSeed, { id: 'guest', username: 'guest', isHost: !localPlayer.isHost }, localPlayer.isHost ? 1 : 0));

    setBoard(BingoEngine.generateBoard(5, myNewSeed));
    setOpponentBoard(BingoEngine.generateBoard(5, oppNewSeed));
    setIsGameOver(false);
    setIsWinner(false);
    setSelectedReviewBoard('local');
    setMyLinesCount(0);
    setOpponentLinesCount(0);
    setTurnNumber(1);
    setTurnTimer(30);
    setPickedNumbersHistory([]);
    setWantsPlayAgainName(null);
    setCurrentTurnPlayerId(localPlayer.id);

    if (!effectiveAiMode) {
      roomSync.sendPacket({
        type: 'PLAY_AGAIN',
        playerId: localPlayer.id,
        displayName: localPlayer.displayName,
        seed: newSeed
      });
    }
  };

  // Sync game state / refresh button matching Android InGameBottomBar Item 6
  const [isSyncing, setIsSyncing] = useState<boolean>(false);
  const handleSyncGame = () => {
    setIsSyncing(true);
    soundEffects.playTap();
    if (!effectiveAiMode) {
      roomSync.sendPacket({
        type: 'SYNC_GAME',
        playerId: localPlayer.id,
        displayName: localPlayer.displayName
      });
    }
    setTimeout(() => setIsSyncing(false), 600);
  };

  // Ping color thresholds matching Android
  const pingColor = displayedPing <= 250 ? '#16A34A' : displayedPing <= 500 ? '#EAB308' : '#DC2626';

  // Dynamic humorous / informative turn status message matching Android GameScreen.kt
  const activeOpponentName = resolvedOpponent?.displayName || (effectiveAiMode ? 'Master Bot' : 'Opponent');
  const statusMessage = isMyTurn
    ? turnTimer > 20
      ? `You are choosing${animatedDots}`
      : turnTimer >= 11
      ? 'You are cooking something...'
      : "let's have some coffee, you are sleeping I think..."
    : turnTimer > 20
    ? `${activeOpponentName} is choosing${animatedDots}`
    : turnTimer >= 11
    ? `${activeOpponentName} is cooking something...`
    : `let's have some coffee, ${activeOpponentName} is sleeping I think...`;

  const displayedBoard = isGameOver
    ? selectedReviewBoard === 'local'
      ? board
      : opponentBoard
    : board;

  const favoriteEmojis = EmojiPreferences.getFavoriteEmojis();

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="h-[100dvh] w-full max-w-md mx-auto flex flex-col justify-between p-3 sm:p-4 select-none overflow-hidden transition-colors duration-300 relative"
    >
      {/* ── Top Bar matching Android GameScreen.kt (Items 1, 2, 3) ── */}
      <header className="w-full flex items-center justify-between pb-1 flex-shrink-0 relative">
        {/* Item 1: Top Left Door Open Exit Button */}
        <button
          type="button"
          onClick={() => setShowExitDialog(true)}
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder,
            color: tokens.cellNeutralText
          }}
          className="w-9 h-9 rounded-xl border flex items-center justify-center cursor-pointer active:scale-95 transition-transform"
          title="Exit Match"
        >
          <LogOut className="w-4 h-4 opacity-80" />
        </button>

        {/* Item 2: Top Center 🛜 Wifi Icon + Ping (1s stabilized, perfectly centered) */}
        <div className="flex items-center gap-1.5 px-3 py-1">
          <Wifi style={{ color: tokens.cellNeutralText }} className="w-4 h-4 opacity-80" />
          <span style={{ color: pingColor }} className="text-xs font-bold font-mono">
            {displayedPing > 999 ? '999+ms' : `${displayedPing}ms`}
          </span>
        </div>

        {/* Item 3: Top Right Pause Button FIRST, then ⌛ Timer NEXT */}
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleTogglePause}
            style={{
              backgroundColor: tokens.surface,
              borderColor: tokens.surfaceBorder,
              color: tokens.cellNeutralText
            }}
            className="w-8 h-8 rounded-xl border flex items-center justify-center cursor-pointer active:scale-95"
            title={isGamePaused ? 'Resume Match' : 'Pause Match'}
          >
            {isGamePaused ? <Play className="w-3.5 h-3.5" /> : <Pause className="w-3.5 h-3.5" />}
          </button>

          <div
            className={`flex items-center gap-1 px-2.5 py-1 rounded-xl border transition-transform ${
              turnTimer <= 5 ? 'scale-105 border-red-500/50 bg-red-500/10' : ''
            }`}
            style={{
              backgroundColor: turnTimer <= 5 ? undefined : tokens.surface,
              borderColor: turnTimer <= 5 ? undefined : tokens.surfaceBorder
            }}
          >
            <span className="text-sm">⌛</span>
            <span
              style={{ color: turnTimer <= 5 ? '#DC2626' : tokens.cellNeutralText }}
              className="text-xs font-mono font-black"
            >
              {turnTimer}s
            </span>
          </div>
        </div>
      </header>

      {/* ── Post-Game Review Tabs Strip (Only shown when game is over) ── */}
      {isGameOver && (
        <div
          style={{ backgroundColor: tokens.backgroundSecondary, borderColor: tokens.surfaceBorder }}
          className="w-full p-1 rounded-xl border flex items-center gap-1.5 my-1 flex-shrink-0 animate-fade-in"
        >
          <button
            type="button"
            onClick={() => setSelectedReviewBoard('local')}
            style={{
              backgroundColor: selectedReviewBoard === 'local' ? tokens.surface : 'transparent',
              borderColor: selectedReviewBoard === 'local' ? tokens.accentBrand : 'transparent',
              color: selectedReviewBoard === 'local' ? tokens.accentBrand : tokens.textMuted
            }}
            className="flex-1 py-1.5 px-2 rounded-lg border text-xs font-bold flex items-center justify-center gap-1.5 transition-all cursor-pointer"
          >
            <span>My Board ({myLinesCount}/5)</span>
            {isWinner && <span>👑</span>}
          </button>

          <button
            type="button"
            onClick={() => setSelectedReviewBoard('opponent')}
            style={{
              backgroundColor: selectedReviewBoard === 'opponent' ? tokens.surface : 'transparent',
              borderColor: selectedReviewBoard === 'opponent' ? tokens.accentOpponent : 'transparent',
              color: selectedReviewBoard === 'opponent' ? tokens.accentOpponent : tokens.textMuted
            }}
            className="flex-1 py-1.5 px-2 rounded-lg border text-xs font-bold flex items-center justify-center gap-1.5 transition-all cursor-pointer"
          >
            <span>{activeOpponentName} ({opponentLinesCount}/5)</span>
            {!isWinner && <span>👑</span>}
          </button>
        </div>
      )}

      {/* ── Reserved Space / Dynamic Turn Status Message / Victory Stamp Badge ── */}
      <div className="w-full flex items-center justify-center py-1 flex-shrink-0 min-h-[36px]">
        {isGameOver ? (
          <div
            style={{
              borderColor: isWinner ? '#16A34A' : '#DC2626',
              color: isWinner ? '#16A34A' : '#DC2626',
              backgroundColor: isWinner ? 'rgba(22, 163, 74, 0.12)' : 'rgba(220, 38, 38, 0.12)'
            }}
            className="px-6 py-1.5 rounded-2xl border-2 font-black text-sm sm:text-base tracking-widest uppercase transform rotate-[-2deg] shadow-md animate-bounce"
          >
            {isWinner ? "YOU'VE WON! 🏆" : 'YOU LOST! 💀'}
          </div>
        ) : (
          <p
            style={{ color: tokens.cellNeutralText }}
            className="text-xs font-semibold tracking-wide text-center opacity-85 px-2 truncate"
          >
            {statusMessage}
          </p>
        )}
      </div>

      {/* ── 5x5 Bingo Board with B-I-N-G-O Letters ── */}
      <div className="w-full flex-1 flex flex-col justify-center items-center py-1">
        <BingoBoardView
          board={displayedBoard}
          isInteractive={isMyTurn && !isGameOver && !isGamePaused && selectedReviewBoard === 'local'}
          onCellClick={handlePickNumber}
          isWinningBoard={isGameOver && displayedBoard.completedLines.length >= 5}
        />
      </div>

      {/* ── In-Game WhatsApp Style Chat Space (Between board and emoji reactions) ── */}
      <div className="w-full flex-shrink-0 my-0.5">
        <InGameChatSpace
          messages={chatMessages}
          onOpenChatDrawer={() => setIsQuickChatOpen(true)}
        />
      </div>

      {/* ── Bottom Section: Emoji Reaction Strip with Chat OR Post-Game Buttons ── */}
      <div className="w-full flex-shrink-0 pt-1 pb-2">
        {isGameOver ? (
          <div className="w-full flex flex-col gap-2 animate-fade-in">
            {wantsPlayAgainName && (
              <div
                style={{
                  backgroundColor: tokens.badgeSurface,
                  borderColor: tokens.badgeOutline,
                  color: tokens.badgeContent
                }}
                className="py-1.5 px-3 rounded-xl border text-center text-xs font-bold"
              >
                🎮 {wantsPlayAgainName} wants to play again!
              </div>
            )}

            <div className="flex gap-2">
              <button
                type="button"
                onClick={handlePlayAgain}
                style={{
                  backgroundColor: tokens.primaryButtonBg,
                  color: tokens.primaryButtonText
                }}
                className="flex-1 h-12 rounded-2xl font-bold text-sm flex items-center justify-center gap-2 cursor-pointer active:scale-98 shadow-md"
              >
                <RotateCcw className="w-4 h-4" />
                <span>Play Again</span>
              </button>

              <button
                type="button"
                onClick={onReturnToLobby || onLeaveGame}
                style={{
                  backgroundColor: tokens.surface,
                  borderColor: tokens.surfaceBorder,
                  color: tokens.cellNeutralText
                }}
                className="px-4 h-12 rounded-2xl border font-bold text-xs flex items-center justify-center cursor-pointer active:scale-98"
              >
                {onReturnToLobby ? 'Lobby' : 'Menu'}
              </button>
            </div>
          </div>
        ) : (
          <div className="w-full flex flex-col gap-1.5">
            {/* Emoji Reaction Strip with Quick Chat */}
            <div
              style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
              className="w-full p-1.5 rounded-full border shadow-sm flex items-center justify-between gap-1"
            >
              {/* Scrollable Favorite Emojis */}
              <div className="flex-1 flex items-center gap-1 overflow-x-auto no-scrollbar px-1">
                {favoriteEmojis.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    onClick={() => handleSendEmote(emoji, 1.0)}
                    className="w-8 h-8 rounded-full flex items-center justify-center text-lg hover:scale-125 active:scale-95 transition-transform cursor-pointer"
                  >
                    {emoji}
                  </button>
                ))}
              </div>

              {/* Quick Chat Popover Button */}
              <button
                type="button"
                onClick={() => setIsQuickChatOpen(true)}
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  color: tokens.cellNeutralText
                }}
                className="w-8 h-8 rounded-full flex items-center justify-center cursor-pointer active:scale-95 flex-shrink-0"
                title="Open Chat Phrases"
              >
                <MessageSquare className="w-4 h-4" />
              </button>
            </div>

            {/* ── InGameBottomBar matching Android GameScreen.kt: Item 5 (Recent Picks), Item 6 (Turn Spotlight Profile vs Profile), Item 6 Right (Sync) ── */}
            <div
              style={{
                backgroundColor: tokens.surface,
                borderColor: tokens.surfaceBorder
              }}
              className="w-full px-2.5 py-1.5 rounded-2xl border shadow-sm flex items-center justify-between gap-1"
            >
              {/* Item 5: Recent Picks Queue Pill (Left) */}
              <RecentPicksQueuePill pickedNumbersHistory={pickedNumbersHistory} />

              {/* Item 6 Center: Turn Spotlight Profile vs Profile (Zoomed avatar on active turn) */}
              <div className="flex items-center gap-2">
                <div
                  style={{
                    borderColor: isMyTurn ? tokens.accentBrand : tokens.surfaceBorder,
                    boxShadow: isMyTurn ? `0 0 8px ${tokens.accentBrand}88` : 'none'
                  }}
                  className={`rounded-full border-2 transition-all duration-300 ${
                    isMyTurn ? 'scale-110' : 'scale-95 opacity-75'
                  }`}
                  title={`${localPlayer.displayName} ${isMyTurn ? '(Your Turn)' : ''}`}
                >
                  <PlayerAvatar
                    avatarUrl={localPlayer.avatarUrl}
                    displayName={localPlayer.displayName}
                    size={26}
                  />
                </div>

                <span
                  style={{ color: tokens.textMuted }}
                  className="text-[10px] font-black uppercase tracking-wider select-none"
                >
                  vs
                </span>

                <div
                  style={{
                    borderColor: !isMyTurn ? tokens.accentOpponent : tokens.surfaceBorder,
                    boxShadow: !isMyTurn ? `0 0 8px ${tokens.accentOpponent}88` : 'none'
                  }}
                  className={`rounded-full border-2 transition-all duration-300 ${
                    !isMyTurn ? 'scale-110' : 'scale-95 opacity-75'
                  }`}
                  title={`${activeOpponentName} ${!isMyTurn ? '(Their Turn)' : ''}`}
                >
                  <PlayerAvatar
                    avatarUrl={opponent?.avatarUrl || null}
                    displayName={activeOpponentName}
                    size={26}
                  />
                </div>
              </div>

              {/* Item 6 Right: Clockwise Rotating Refresh / Sync Button */}
              <button
                type="button"
                onClick={handleSyncGame}
                style={{
                  color: tokens.cellNeutralText,
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder
                }}
                className="w-7 h-7 rounded-xl border flex items-center justify-center cursor-pointer active:scale-95 transition-transform"
                title="Sync Match State"
              >
                <RotateCw
                  className={`w-3.5 h-3.5 opacity-80 transition-transform duration-500 ${
                    isSyncing ? 'rotate-180' : ''
                  }`}
                />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* ── Rising Floating Emotes Overlay ── */}
      <FloatingEmotes emotes={activeEmotes} onRemoveEmote={removeEmote} />

      {/* ── WhatsApp-Style Quick Chat Drawer / Popover ── */}
      <QuickChatDrawer
        isOpen={isQuickChatOpen}
        onClose={() => setIsQuickChatOpen(false)}
        onSendMessage={handleSendChatMessage}
      />

      {/* ── Pause Modal matching Android ── */}
      {isGamePaused && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full max-w-xs rounded-3xl p-5 border shadow-2xl flex flex-col items-center text-center"
          >
            <div className="w-12 h-12 rounded-full bg-amber-500/20 text-amber-500 flex items-center justify-center mb-3">
              <Pause className="w-6 h-6" />
            </div>
            <h3 style={{ color: tokens.cellNeutralText }} className="font-bold text-base mb-1">
              Match Paused
            </h3>
            <p style={{ color: tokens.textMuted }} className="text-xs mb-4">
              {pausedByPlayerName ? `${pausedByPlayerName} paused the game.` : 'The match is currently paused.'}
            </p>
            <div className="w-full flex flex-col gap-2">
              <button
                type="button"
                onClick={handleTogglePause}
                style={{
                  backgroundColor: tokens.primaryButtonBg,
                  color: tokens.primaryButtonText
                }}
                className="w-full h-10 rounded-xl font-bold text-xs cursor-pointer active:scale-98"
              >
                Resume Game
              </button>
              <button
                type="button"
                onClick={() => {
                  setIsGamePaused(false);
                  setShowExitDialog(true);
                }}
                style={{ color: tokens.textMuted }}
                className="text-xs py-1 cursor-pointer hover:underline"
              >
                Exit Match
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Exit Match / Surrender Confirmation Modal ── */}
      {showExitDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full max-w-xs rounded-3xl p-5 border shadow-2xl flex flex-col text-left"
          >
            <h3 style={{ color: tokens.textPrimary }} className="font-bold text-base mb-1">
              {onReturnToLobby ? 'Return to Lobby?' : 'Exit Match?'}
            </h3>
            <p style={{ color: tokens.textSecondary }} className="text-xs mb-4 leading-relaxed">
              {onReturnToLobby
                ? 'Are you sure you want to leave this match? You will return to the lobby.'
                : 'Are you sure you want to leave? This will count as a surrender.'}
            </p>
            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => setShowExitDialog(false)}
                style={{ color: tokens.textMuted }}
                className="px-3 py-1.5 rounded-xl text-xs font-semibold cursor-pointer"
              >
                Stay
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowExitDialog(false);
                  if (onReturnToLobby) onReturnToLobby();
                  else onLeaveGame();
                }}
                className="px-3.5 py-1.5 rounded-xl text-xs font-bold bg-rose-600 text-white cursor-pointer active:scale-95 shadow-xs"
              >
                {onReturnToLobby ? 'Return to Lobby' : 'Exit to Menu'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
