import React, { useState, useEffect, useCallback } from 'react';
import { Volume2, VolumeX, Flag, MessageSquare } from 'lucide-react';
import {
  Player,
  Board,
  FloatingEmoteItem,
  RoomMessagePacket,
  InGameChatMessage
} from '../types/models';
import { BingoEngine } from '../engine/bingoEngine';
import { roomSync } from '../network/mqttSync';
import { soundEffects } from '../audio/sounds';
import { useTheme } from '../theme/theme';
import { BingoBoardView } from '../components/BingoBoardView';
import { RecentPicksQueuePill } from '../components/RecentPicksQueuePill';
import { HeadToHeadScorecard } from '../components/HeadToHeadScorecard';
import { InGameChatSpace } from '../components/InGameChatSpace';
import { QuickChatDrawer } from '../components/QuickChatDrawer';
import { FloatingEmotes } from '../components/FloatingEmotes';
import { WinningModal } from '../components/WinningModal';

interface Props {
  roomCode: string;
  localPlayer: Player;
  opponent: Player | null;
  seed: number;
  initialTurnPlayerId: string;
  isAiMode?: boolean;
  onLeaveGame: () => void;
}

const QUICK_EMOJIS = ['😂', '🔥', '😎', '👏', '😱', '⚡'];

export const GameScreen: React.FC<Props> = ({
  roomCode,
  localPlayer,
  opponent,
  seed,
  initialTurnPlayerId,
  isAiMode = false,
  onLeaveGame
}) => {
  const { tokens, isDark } = useTheme();

  const mySeed = localPlayer.isHost || isAiMode ? seed : seed + 1;
  const oppSeed = localPlayer.isHost || isAiMode ? seed + 1 : seed;

  const [board, setBoard] = useState<Board>(() =>
    BingoEngine.generateBoard(5, mySeed)
  );
  const [opponentBoard, setOpponentBoard] = useState<Board>(() =>
    BingoEngine.generateBoard(5, oppSeed)
  );
  const [currentTurnPlayerId, setCurrentTurnPlayerId] =
    useState<string>(initialTurnPlayerId);
  const [turnNumber, setTurnNumber] = useState<number>(1);
  const [turnTimer, setTurnTimer] = useState<number>(30);
  const [pickedNumbersHistory, setPickedNumbersHistory] = useState<number[]>([]);
  const [myLinesCount, setMyLinesCount] = useState<number>(0);
  const [opponentLinesCount, setOpponentLinesCount] = useState<number>(0);
  const [isGameOver, setIsGameOver] = useState<boolean>(false);
  const [isWinner, setIsWinner] = useState<boolean>(false);
  const [winnerName, setWinnerName] = useState<string>('');
  const [wantsPlayAgainName, setWantsPlayAgainName] = useState<string | null>(null);

  const [activeEmotes, setActiveEmotes] = useState<FloatingEmoteItem[]>([]);
  const [chatMessages, setChatMessages] = useState<InGameChatMessage[]>([]);
  const [isQuickChatOpen, setIsQuickChatOpen] = useState<boolean>(false);
  const [soundOn, setSoundOn] = useState<boolean>(true);

  const isMyTurn = currentTurnPlayerId === localPlayer.id;

  // Sound toggle
  const toggleSound = () => {
    const next = !soundOn;
    setSoundOn(next);
    soundEffects.setSoundEnabled(next);
  };

  // Spawn visual floating emote
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

  // Handle cell pick
  const handlePickNumber = useCallback(
    (number: number) => {
      if (!isMyTurn || isGameOver) return;

      soundEffects.playPick();

      // 1. Mark on local board
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

      // Record in recent picks history
      setPickedNumbersHistory((prev) => [...prev, number]);

      if (newLinesCompleted > 0) {
        soundEffects.playLineComplete();
      }

      // Check win condition
      if (newMyLines >= 5) {
        soundEffects.playBingoWin();
        setIsGameOver(true);
        setIsWinner(true);
        setWinnerName(localPlayer.displayName);
        if (!isAiMode) {
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
      const nextTurnId = isAiMode
        ? 'ai_bot'
        : opponent?.id || 'opponent';
      setCurrentTurnPlayerId(nextTurnId);
      setTurnNumber((prev) => prev + 1);
      setTurnTimer(30);

      // Transmit to opponent
      if (!isAiMode) {
        roomSync.sendPick(number, turnNumber, nextTurnId, [
          ...pickedNumbersHistory,
          number
        ]);
      }
    },
    [
      board,
      isAiMode,
      isGameOver,
      isMyTurn,
      localPlayer,
      opponent,
      pickedNumbersHistory,
      turnNumber
    ]
  );

  // AI Turn simulation
  useEffect(() => {
    if (!isAiMode || isMyTurn || isGameOver) return;

    const timer = setTimeout(() => {
      const unmarkedCells = opponentBoard.cells.filter(
        (c) => c.markState.type === 'Unmarked'
      );
      if (unmarkedCells.length === 0) return;

      // Smart pick or random pick
      const pick =
        unmarkedCells[Math.floor(Math.random() * unmarkedCells.length)].number;

      // 1. Mark on opponent board
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

      // 2. Mark on local player's board
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
        return;
      } else if (newMyLines >= 5) {
        soundEffects.playBingoWin();
        setIsGameOver(true);
        setIsWinner(true);
        setWinnerName(localPlayer.displayName);
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
    isAiMode,
    isGameOver,
    isMyTurn,
    localPlayer,
    opponentBoard,
    turnNumber
  ]);

  // Turn Countdown Timer
  useEffect(() => {
    if (isGameOver) return;

    const timer = setInterval(() => {
      setTurnTimer((prev) => {
        if (prev <= 1) {
          if (isMyTurn) {
            // Auto-pick a random unmarked cell on timeout
            const unmarked = board.cells.filter(
              (c) => c.markState.type === 'Unmarked'
            );
            if (unmarked.length > 0) {
              const randPick =
                unmarked[Math.floor(Math.random() * unmarked.length)].number;
              handlePickNumber(randPick);
            }
          }
          return 30;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(timer);
  }, [board, handlePickNumber, isGameOver, isMyTurn]);

  // Listen for incoming opponent packets over MQTT
  useEffect(() => {
    if (isAiMode) return;

    roomSync.onPacketReceived = (packet: RoomMessagePacket) => {
      if (packet.playerId === localPlayer.id) return;

      switch (packet.type) {
        case 'PICK_NUMBER': {
          if (packet.number) {
            const pickNum = packet.number;
            setPickedNumbersHistory((prev) => [...prev, pickNum]);

            // 1. Mark on my board
            let newMyLines = 0;
            let newOppLines = 0;

            setBoard((prevMy) => {
              const { board: updatedMy, newLinesCompleted } =
                BingoEngine.markCell(
                  prevMy,
                  pickNum,
                  packet.playerId,
                  false,
                  packet.turnNumber || turnNumber
                );
              newMyLines = updatedMy.completedLines.length;
              setMyLinesCount(newMyLines);
              if (newLinesCompleted > 0) {
                soundEffects.playLineComplete();
              }
              return updatedMy;
            });

            // 2. Mark on opponent board
            setOpponentBoard((prevOpp) => {
              const { board: updatedOpp } = BingoEngine.markCell(
                prevOpp,
                pickNum,
                packet.playerId,
                true,
                packet.turnNumber || turnNumber
              );
              newOppLines = updatedOpp.completedLines.length;
              setOpponentLinesCount(newOppLines);
              return updatedOpp;
            });

            // 3. Evaluate win conditions
            if (newOppLines >= 5) {
              soundEffects.playGameOver();
              setIsGameOver(true);
              setIsWinner(false);
              setWinnerName(
                packet.displayName || opponent?.displayName || 'Opponent'
              );
              return;
            } else if (newMyLines >= 5) {
              soundEffects.playBingoWin();
              setIsGameOver(true);
              setIsWinner(true);
              setWinnerName(localPlayer.displayName);
              return;
            }

            // 4. Switch turn to me
            setCurrentTurnPlayerId(localPlayer.id);
            setTurnNumber((prev) =>
              packet.turnNumber ? packet.turnNumber + 1 : prev + 1
            );
            setTurnTimer(30);
            soundEffects.playTurnAlert();
          }
          break;
        }

        case 'BINGO_CLAIMED': {
          setIsGameOver(true);
          setIsWinner(false);
          setWinnerName(
            packet.displayName || opponent?.displayName || 'Opponent'
          );
          soundEffects.playGameOver();
          setOpponentLinesCount(5);
          break;
        }

        case 'EMOTE': {
          if (packet.displayName) {
            const scale =
              packet.number && packet.number > 0 ? packet.number / 100 : 1.0;
            spawnEmote(
              packet.displayName,
              false,
              opponent?.displayName || 'Opponent',
              scale
            );
          }
          break;
        }

        case 'CHAT_MESSAGE':
        case 'CHAT_PHRASE': {
          const text = packet.displayName || packet.payload || '';
          if (text) {
            const sender =
              packet.username || opponent?.displayName || 'Opponent';
            const newMsg: InGameChatMessage = {
              id: packet.timestamp || Date.now(),
              text,
              isSelf: false,
              senderName: sender,
              timestamp: packet.timestamp || Date.now()
            };
            setChatMessages((prev) => [...prev.slice(-30), newMsg]);
            spawnEmote(text, false, sender, 1.0);
            soundEffects.playTurnAlert();
          }
          break;
        }

        case 'PLAY_AGAIN': {
          setWantsPlayAgainName(packet.displayName || 'Opponent');
          if (packet.seed && packet.seed !== seed) {
            const myNewSeed = localPlayer.isHost ? packet.seed : packet.seed + 1;
            const oppNewSeed = localPlayer.isHost ? packet.seed + 1 : packet.seed;

            setBoard(BingoEngine.generateBoard(5, myNewSeed));
            setOpponentBoard(BingoEngine.generateBoard(5, oppNewSeed));
            setIsGameOver(false);
            setIsWinner(false);
            setMyLinesCount(0);
            setOpponentLinesCount(0);
            setTurnNumber(1);
            setTurnTimer(30);
            setPickedNumbersHistory([]);
            setWantsPlayAgainName(null);
            setCurrentTurnPlayerId(localPlayer.id);
          }
          break;
        }

        case 'LEAVE':
        case 'SURRENDER': {
          setIsGameOver(true);
          setIsWinner(true);
          setWinnerName(localPlayer.displayName);
          alert(`${opponent?.displayName || 'Opponent'} left the game!`);
          break;
        }
      }
    };

    return () => {
      roomSync.onPacketReceived = null;
    };
  }, [isAiMode, localPlayer, opponent, seed, spawnEmote, turnNumber]);

  // Send Emote
  const handleSendEmote = (emoji: string, scale: number = 1.0) => {
    spawnEmote(emoji, true, undefined, scale);
    if (!isAiMode) {
      roomSync.sendPacket({
        type: 'EMOTE',
        playerId: localPlayer.id,
        displayName: emoji,
        number: Math.round(scale * 100)
      });
    }
  };

  // Send Chat Message
  const handleSendMessage = (text: string) => {
    const trimmed = text.trim().slice(0, 100);
    if (!trimmed) return;

    const newMsg: InGameChatMessage = {
      id: Date.now(),
      text: trimmed,
      isSelf: true,
      senderName: localPlayer.displayName,
      timestamp: Date.now()
    };
    setChatMessages((prev) => [...prev.slice(-30), newMsg]);
    spawnEmote(trimmed, true, localPlayer.displayName, 1.0);

    if (!isAiMode) {
      roomSync.sendPacket({
        type: 'CHAT_MESSAGE',
        playerId: localPlayer.id,
        displayName: trimmed,
        username: localPlayer.displayName,
        timestamp: Date.now()
      });
    }
  };

  // Play Again
  const handlePlayAgain = () => {
    const newSeed = Math.floor(Math.random() * 100000) + 1;
    const myNewSeed = localPlayer.isHost || isAiMode ? newSeed : newSeed + 1;
    const oppNewSeed = localPlayer.isHost || isAiMode ? newSeed + 1 : newSeed;

    setBoard(BingoEngine.generateBoard(5, myNewSeed));
    setOpponentBoard(BingoEngine.generateBoard(5, oppNewSeed));
    setIsGameOver(false);
    setIsWinner(false);
    setMyLinesCount(0);
    setOpponentLinesCount(0);
    setTurnNumber(1);
    setTurnTimer(30);
    setPickedNumbersHistory([]);
    setWantsPlayAgainName(null);
    setCurrentTurnPlayerId(localPlayer.id);

    if (!isAiMode) {
      roomSync.sendPacket({
        type: 'PLAY_AGAIN',
        playerId: localPlayer.id,
        displayName: localPlayer.displayName,
        seed: newSeed
      });
    }
  };

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="h-[100dvh] w-full max-w-md mx-auto flex flex-col justify-between p-3 sm:p-4 select-none overflow-hidden transition-colors duration-300 relative"
    >
      {/* ── Top Bar with Status & Sound ── */}
      <header className="w-full flex items-center justify-between pb-1 flex-shrink-0">
        <div className="flex items-center gap-2">
          <span
            style={{ color: tokens.cellNeutralText }}
            className="text-lg font-black font-heading tracking-wider"
          >
            B I N G O
          </span>
          <span
            style={{
              backgroundColor: tokens.backgroundSecondary,
              color: tokens.textMuted
            }}
            className="text-[10px] font-mono font-bold px-2 py-0.5 rounded-full border border-inherit"
          >
            Room {roomCode}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={toggleSound}
            style={{
              backgroundColor: tokens.backgroundSecondary,
              color: tokens.cellNeutralText
            }}
            className="w-8 h-8 rounded-full flex items-center justify-center cursor-pointer active:scale-95 shadow-xs"
          >
            {soundOn ? (
              <Volume2 className="w-4 h-4" />
            ) : (
              <VolumeX className="w-4 h-4 opacity-50" />
            )}
          </button>

          <button
            type="button"
            onClick={() => {
              if (confirm('Leave match? This will count as surrender.')) {
                onLeaveGame();
              }
            }}
            style={{
              backgroundColor: tokens.backgroundSecondary,
              color: tokens.textMuted
            }}
            className="w-8 h-8 rounded-full flex items-center justify-center cursor-pointer active:scale-95 hover:text-rose-500"
          >
            <Flag className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* ── Top Turn Spotlight Bar ── */}
      <div className="w-full flex-shrink-0 my-1">
        <div
          style={{
            backgroundColor: isMyTurn
              ? isDark
                ? '#1C1917'
                : '#F5EEFF'
              : tokens.surface,
            borderColor: isMyTurn ? tokens.accentBrand : tokens.surfaceBorder
          }}
          className={`w-full py-2 px-3 rounded-2xl border flex items-center justify-between shadow-xs transition-all ${
            isMyTurn ? 'animate-pulse' : ''
          }`}
        >
          <div className="flex items-center gap-2">
            <span
              className={`w-2 h-2 rounded-full ${
                isMyTurn ? 'bg-emerald-500 animate-ping' : 'bg-amber-500'
              }`}
            />
            <span
              style={{
                color: isMyTurn
                  ? tokens.accentBrand
                  : tokens.cellNeutralText
              }}
              className="text-xs sm:text-sm font-bold tracking-wide"
            >
              {isMyTurn
                ? 'YOUR TURN • Pick any number'
                : `Waiting for ${
                    opponent?.displayName || (isAiMode ? 'AI Bot' : 'Opponent')
                  }...`}
            </span>
          </div>

          {/* Countdown Pill */}
          <span
            style={{
              color: turnTimer <= 5 ? '#DC2626' : tokens.textMuted
            }}
            className="text-xs font-mono font-bold"
          >
            {turnTimer}s
          </span>
        </div>
      </div>

      {/* ── 5x5 Bingo Board with B-I-N-G-O Letters ── */}
      <div className="w-full flex-1 flex flex-col justify-center items-center py-1">
        <BingoBoardView
          board={board}
          isInteractive={isMyTurn && !isGameOver}
          onCellClick={handlePickNumber}
          isWinningBoard={isGameOver && isWinner}
        />
      </div>

      {/* ── Recent Picks Queue Pill ── */}
      <div className="w-full flex justify-center flex-shrink-0 my-1">
        <RecentPicksQueuePill pickedNumbersHistory={pickedNumbersHistory} />
      </div>

      {/* ── Head-to-Head PvP Scorecard ── */}
      <div className="w-full flex-shrink-0 my-1">
        <HeadToHeadScorecard
          playerName={localPlayer.displayName}
          playerAvatarUrl={localPlayer.avatarUrl}
          playerUsername={localPlayer.username}
          playerLines={myLinesCount}
          opponentName={
            opponent?.displayName || (isAiMode ? 'Master Bot' : 'Opponent')
          }
          opponentAvatarUrl={opponent?.avatarUrl}
          opponentUsername={opponent?.username}
          opponentLines={opponentLinesCount}
          targetLines={5}
          isMyTurn={isMyTurn}
        />
      </div>

      {/* ── In-Game WhatsApp Style Chat Space ── */}
      <div className="w-full flex-shrink-0">
        <InGameChatSpace
          messages={chatMessages}
          onOpenChatDrawer={() => setIsQuickChatOpen(true)}
        />
      </div>

      {/* ── Bottom Emoji Reaction Strip & Chat Trigger ── */}
      <footer className="w-full flex items-center justify-between gap-1.5 pt-1 pb-1 flex-shrink-0">
        <button
          type="button"
          onClick={() => setIsQuickChatOpen(true)}
          style={{
            backgroundColor: tokens.backgroundSecondary,
            borderColor: tokens.surfaceBorder,
            color: tokens.cellNeutralText
          }}
          className="h-10 px-3 rounded-xl border flex items-center gap-1.5 text-xs font-bold cursor-pointer active:scale-95 shadow-xs"
        >
          <MessageSquare className="w-4 h-4 text-purple-500" />
          <span>Chat</span>
        </button>

        {/* Quick Emoji Reaction Buttons */}
        <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
          {QUICK_EMOJIS.map((emoji) => (
            <button
              key={emoji}
              type="button"
              onClick={() => handleSendEmote(emoji, 1.2)}
              style={{
                backgroundColor: tokens.surface,
                borderColor: tokens.surfaceBorder
              }}
              className="w-9 h-9 rounded-xl border flex items-center justify-center text-lg hover:scale-110 active:scale-95 transition-transform cursor-pointer shadow-xs"
            >
              {emoji}
            </button>
          ))}
        </div>
      </footer>

      {/* ── Floating Emotes Particle Overlay ── */}
      <FloatingEmotes emotes={activeEmotes} onRemoveEmote={removeEmote} />

      {/* ── Quick Chat Drawer ── */}
      <QuickChatDrawer
        isOpen={isQuickChatOpen}
        onClose={() => setIsQuickChatOpen(false)}
        onSendMessage={handleSendMessage}
      />

      {/* ── Victory / Defeat Modal with Confetti ── */}
      {isGameOver && (
        <WinningModal
          isWinner={isWinner}
          winnerName={winnerName}
          myLinesCount={myLinesCount}
          opponentLinesCount={opponentLinesCount}
          onPlayAgain={handlePlayAgain}
          onLeave={onLeaveGame}
          wantsPlayAgainName={wantsPlayAgainName}
        />
      )}
    </div>
  );
};
