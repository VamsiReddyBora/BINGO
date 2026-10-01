import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Wifi, Flag, Volume2, VolumeX, Copy, Check } from 'lucide-react';
import { Player, Board, FloatingEmoteItem, RoomMessagePacket } from '../types/models';
import { BingoEngine } from '../engine/bingoEngine';
import { roomSync } from '../network/mqttSync';
import { soundEffects } from '../audio/sounds';
import { FloatingEmotes } from '../components/FloatingEmotes';
import { EmojiReactionStrip } from '../components/EmojiReactionStrip';
import { QuickChatDrawer } from '../components/QuickChatDrawer';
import { WinningModal } from '../components/WinningModal';
import { PlayerAvatar } from '../components/PlayerAvatar';

interface Props {
  roomCode: string;
  localPlayer: Player;
  opponent: Player | null;
  seed: number;
  initialTurnPlayerId: string;
  isAiMode?: boolean;
  onLeaveGame: () => void;
}

const BINGO_LETTERS = ['B', 'I', 'N', 'G', 'O'];

export const GameScreen: React.FC<Props> = ({
  roomCode,
  localPlayer,
  opponent,
  seed,
  initialTurnPlayerId,
  isAiMode = false,
  onLeaveGame
}) => {
  const [board, setBoard] = useState<Board>(() => BingoEngine.generateBoard(5, seed));
  const [currentTurnPlayerId, setCurrentTurnPlayerId] = useState<string>(initialTurnPlayerId);
  const [turnNumber, setTurnNumber] = useState<number>(1);
  const [turnTimer, setTurnTimer] = useState<number>(30);
  const [myLinesCount, setMyLinesCount] = useState<number>(0);
  const [opponentLinesCount, setOpponentLinesCount] = useState<number>(0);
  const [isGameOver, setIsGameOver] = useState<boolean>(false);
  const [isWinner, setIsWinner] = useState<boolean>(false);
  const [winnerName, setWinnerName] = useState<string>('');
  const [wantsPlayAgainName, setWantsPlayAgainName] = useState<string | null>(null);

  const [activeEmotes, setActiveEmotes] = useState<FloatingEmoteItem[]>([]);
  const [isQuickChatOpen, setIsQuickChatOpen] = useState<boolean>(false);
  const [pingMs, setPingMs] = useState<number>(24);
  const [soundOn, setSoundOn] = useState<boolean>(true);
  const [copiedCode, setCopiedCode] = useState<boolean>(false);

  const isMyTurn = currentTurnPlayerId === localPlayer.id;

  // Sound toggle
  const toggleSound = () => {
    const next = !soundOn;
    setSoundOn(next);
    soundEffects.setSoundEnabled(next);
  };

  // Copy room code
  const handleCopyCode = () => {
    navigator.clipboard?.writeText(roomCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  };

  // Spawn visual floating emote
  const spawnEmote = useCallback((emoji: string, isSelf: boolean, senderName?: string, scaleMultiplier: number = 1.0) => {
    const randomX = (8 + Math.random() * 78) / 100;
    const newItem: FloatingEmoteItem = {
      id: `${Date.now()}_${Math.random()}`,
      emoji,
      startXRatio: randomX,
      isSelf,
      senderName,
      scaleMultiplier,
      createdAt: Date.now()
    };
    setActiveEmotes(prev => [...prev.slice(-15), newItem]);
  }, []);

  const removeEmote = useCallback((id: string) => {
    setActiveEmotes(prev => prev.filter(e => e.id !== id));
  }, []);

  // Handle cell pick
  const handlePickNumber = useCallback((number: number) => {
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
    const newCount = newBoard.completedLines.length;
    setMyLinesCount(newCount);

    if (newLinesCompleted > 0) {
      soundEffects.playLine();
    }

    // 2. Check Win
    if (newCount >= 5 && !isGameOver) {
      setIsGameOver(true);
      setIsWinner(true);
      setWinnerName(localPlayer.displayName);
      if (!isAiMode) {
        roomSync.sendPacket({
          type: 'BINGO_CLAIMED',
          playerId: localPlayer.id,
          displayName: localPlayer.displayName,
          number: 5
        });
      }
    }

    // 3. Switch turn
    const nextTurnPlayerId = opponent?.id || 'ai_opponent';
    setCurrentTurnPlayerId(nextTurnPlayerId);
    setTurnNumber(prev => prev + 1);
    setTurnTimer(30);

    // 4. Broadcast move
    if (!isAiMode) {
      roomSync.sendPacket({
        type: 'PICK_NUMBER',
        number,
        playerId: localPlayer.id,
        turnNumber,
        currentTurnPlayerId: nextTurnPlayerId,
        seed
      });
    } else {
      // Trigger AI turn after delay
      setTimeout(() => {
        handleAiTurn(newBoard);
      }, 900);
    }
  }, [isMyTurn, isGameOver, board, localPlayer, turnNumber, opponent, seed, isAiMode]);

  // AI Solo mode turn
  const handleAiTurn = (currentBoard: Board) => {
    if (isGameOver) return;
    const aiPick = BingoEngine.pickAiMove(currentBoard);
    if (aiPick <= 0) return;

    soundEffects.playPick();

    const { board: updatedBoard, newLinesCompleted } = BingoEngine.markCell(
      currentBoard,
      aiPick,
      'ai_opponent',
      false,
      turnNumber + 1
    );
    setBoard(updatedBoard);

    if (newLinesCompleted > 0) {
      soundEffects.playLine();
    }

    // Opponent lines simulation
    setOpponentLinesCount(prev => {
      const next = prev + (Math.random() < 0.28 ? 1 : 0);
      if (next >= 5 && !isGameOver) {
        setIsGameOver(true);
        setIsWinner(false);
        setWinnerName(opponent?.displayName || 'AI Bot');
      }
      return next;
    });

    setCurrentTurnPlayerId(localPlayer.id);
    setTurnNumber(prev => prev + 1);
    setTurnTimer(30);
    soundEffects.playTurnAlert();
  };

  // Turn timer interval
  useEffect(() => {
    if (isGameOver) return;
    const timer = setInterval(() => {
      setTurnTimer(prev => {
        if (prev <= 1) {
          // Timeout: automatically pass turn or pick first available unmarked cell
          if (isMyTurn) {
            const firstUnmarked = board.cells.find(c => c.markState.type === 'Unmarked');
            if (firstUnmarked) {
              handlePickNumber(firstUnmarked.number);
            }
          }
          return 30;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [isGameOver, isMyTurn, board, handlePickNumber]);

  // Listen to MQTT room packets
  useEffect(() => {
    if (isAiMode) return;

    roomSync.onPingChanged = (ping) => setPingMs(ping);

    roomSync.onPacketReceived = (packet: RoomMessagePacket) => {
      switch (packet.type) {
        case 'PICK_NUMBER': {
          if (packet.number && packet.number > 0) {
            soundEffects.playPick();
            setBoard(prevBoard => {
              const { board: updated, newLinesCompleted } = BingoEngine.markCell(
                prevBoard,
                packet.number!,
                packet.playerId,
                false,
                packet.turnNumber || turnNumber
              );
              if (newLinesCompleted > 0) soundEffects.playLine();
              setMyLinesCount(updated.completedLines.length);
              return updated;
            });

            // Opponent finished turn -> switch to me
            setCurrentTurnPlayerId(localPlayer.id);
            setTurnNumber(prev => (packet.turnNumber ? packet.turnNumber + 1 : prev + 1));
            setTurnTimer(30);
            soundEffects.playTurnAlert();
          }
          break;
        }

        case 'BINGO_CLAIMED': {
          setIsGameOver(true);
          setIsWinner(packet.playerId === localPlayer.id);
          setWinnerName(packet.displayName || 'Opponent');
          setOpponentLinesCount(5);
          break;
        }

        case 'EMOTE': {
          if (packet.displayName) {
            const scale = packet.number && packet.number > 0 ? packet.number / 100 : 1.0;
            spawnEmote(packet.displayName, false, opponent?.displayName || 'Opponent', scale);
          }
          break;
        }

        case 'CHAT_PHRASE': {
          if (packet.displayName) {
            spawnEmote(packet.displayName, false, opponent?.displayName || 'Opponent', 1.0);
          }
          break;
        }

        case 'PLAY_AGAIN': {
          setWantsPlayAgainName(packet.displayName || 'Opponent');
          // If both agreed or host triggered new seed
          if (packet.seed && packet.seed !== seed) {
            // Restart match
            setBoard(BingoEngine.generateBoard(5, packet.seed));
            setIsGameOver(false);
            setIsWinner(false);
            setMyLinesCount(0);
            setOpponentLinesCount(0);
            setTurnNumber(1);
            setTurnTimer(30);
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
          alert(`${opponent?.displayName || 'Opponent'} surrendered the game!`);
          break;
        }
      }
    };

    return () => {
      roomSync.onPacketReceived = null;
    };
  }, [isAiMode, localPlayer, opponent, seed, turnNumber, spawnEmote]);

  // Send reaction emote
  const handleSendEmote = (emoji: string, scale: number) => {
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

  // Send quick chat phrase
  const handleSendPhrase = (phrase: string) => {
    spawnEmote(phrase, true, undefined, 1.0);
    if (!isAiMode) {
      roomSync.sendPacket({
        type: 'CHAT_PHRASE',
        playerId: localPlayer.id,
        displayName: phrase
      });
    }
  };

  // Play again handler
  const handlePlayAgain = () => {
    if (isAiMode) {
      setBoard(BingoEngine.generateBoard(5));
      setIsGameOver(false);
      setIsWinner(false);
      setMyLinesCount(0);
      setOpponentLinesCount(0);
      setTurnNumber(1);
      setTurnTimer(30);
      setCurrentTurnPlayerId(localPlayer.id);
      return;
    }

    const newSeed = Date.now();
    roomSync.sendPacket({
      type: 'PLAY_AGAIN',
      playerId: localPlayer.id,
      displayName: localPlayer.displayName,
      seed: newSeed
    });

    setBoard(BingoEngine.generateBoard(5, newSeed));
    setIsGameOver(false);
    setIsWinner(false);
    setMyLinesCount(0);
    setOpponentLinesCount(0);
    setTurnNumber(1);
    setTurnTimer(30);
    setWantsPlayAgainName(null);
    setCurrentTurnPlayerId(localPlayer.id);
  };

  return (
    <div className="relative min-h-[100dvh] flex flex-col justify-between max-w-lg lg:max-w-5xl mx-auto p-2 sm:p-4 pb-3 sm:pb-4 select-none bg-[#FAFAFC] text-slate-800">
      {/* Floating Emotes Layer */}
      <FloatingEmotes emotes={activeEmotes} onRemoveEmote={removeEmote} />

      {/* Top Header Bar */}
      <header className="flex items-center justify-between py-1.5 sm:py-2 px-1">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            if (confirm('Are you sure you want to surrender and leave the game?')) {
              onLeaveGame();
            }
          }}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white border border-slate-200 text-rose-600 hover:bg-rose-50 text-xs font-bold transition-all cursor-pointer shadow-sm active:scale-95"
        >
          <Flag className="w-3.5 h-3.5" />
          <span>Surrender</span>
        </button>

        {/* Room Code Badge */}
        <button
          type="button"
          onClick={handleCopyCode}
          title="Click to copy room code"
          className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 text-xs font-extrabold tracking-wider transition-all cursor-pointer shadow-sm active:scale-95"
        >
          <span>ROOM: <strong className="text-[#7C3AED]">{roomCode}</strong></span>
          {copiedCode ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3 h-3 text-slate-400" />}
        </button>

        <div className="flex items-center gap-2">
          {/* Ping indicator */}
          {!isAiMode && (
            <div className="flex items-center gap-1 text-[11px] font-bold text-slate-600 bg-white px-2.5 py-1 rounded-full border border-slate-200 shadow-sm">
              <span className={`w-2 h-2 rounded-full ${pingMs < 80 ? 'bg-emerald-500' : pingMs < 180 ? 'bg-amber-500' : 'bg-rose-500'}`} />
              <span>{pingMs}ms</span>
            </div>
          )}

          {/* Sound toggle */}
          <button
            type="button"
            onClick={toggleSound}
            className="w-8 h-8 rounded-full bg-white border border-slate-200 text-slate-600 hover:text-slate-900 hover:bg-slate-50 flex items-center justify-center transition-all cursor-pointer shadow-sm active:scale-95"
          >
            {soundOn ? <Volume2 className="w-4 h-4" /> : <VolumeX className="w-4 h-4 text-slate-400" />}
          </button>
        </div>
      </header>

      {/* Main Adaptive Game Area: Stacked on Mobile/Tablet portrait, 2 Columns on Laptop/Desktop */}
      <div className="flex-1 flex flex-col lg:flex-row lg:items-center lg:justify-between lg:gap-8 my-1 sm:my-2">
        {/* Left Column on Desktop / Top Section on Mobile: B-I-N-G-O Letters & Player Status */}
        <div className="w-full lg:w-[360px] xl:w-[400px] flex flex-col justify-center space-y-2 sm:space-y-3">
          {/* B-I-N-G-O Letters Banner */}
          <div className="flex justify-center items-center gap-2 sm:gap-3 py-2 px-3 bg-white border border-slate-200 rounded-2xl shadow-sm">
            {BINGO_LETTERS.map((letter, idx) => {
              const isLit = myLinesCount > idx;
              return (
                <div
                  key={letter}
                  className={`relative flex items-center justify-center w-10 h-10 sm:w-12 sm:h-12 lg:w-13 lg:h-13 rounded-xl font-heading font-black text-xl sm:text-2xl transition-all duration-300 ${
                    isLit
                      ? 'bg-gradient-to-tr from-amber-400 to-amber-500 text-white shadow-md scale-105'
                      : 'bg-slate-50 text-slate-400 border border-slate-200'
                  }`}
                >
                  {letter}
                  {isLit && (
                    <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-amber-500"></span>
                    </span>
                  )}
                </div>
              );
            })}
          </div>

          {/* Players Status & Turn Bar */}
          <div className="grid grid-cols-2 lg:grid-cols-1 gap-2">
            {/* Local Player Card */}
            <div
              className={`flex items-center gap-2.5 p-2 sm:p-2.5 rounded-2xl border transition-all ${
                isMyTurn
                  ? 'bg-[#F5EEFF] border-2 border-[#7C3AED] shadow-sm'
                  : 'bg-white border border-slate-200 shadow-sm'
              }`}
            >
              <PlayerAvatar
                avatarUrl={localPlayer.avatarUrl}
                displayName={localPlayer.displayName}
                sizeClassName="w-10 h-10 text-lg"
                fallbackIcon="🧑"
                className="border border-purple-200 bg-[#F5EEFF] shadow-sm"
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between gap-1">
                  <span className="text-xs font-bold text-slate-800 truncate">{localPlayer.displayName}</span>
                  <span className="text-[10px] font-black px-1.5 py-0.5 rounded bg-purple-100 text-[#7C3AED] flex-shrink-0">
                    {myLinesCount}/5
                  </span>
                </div>
                <div className="flex items-center gap-1 mt-0.5">
                  {isMyTurn ? (
                    <span className="text-[11px] font-extrabold text-[#7C3AED] flex items-center gap-1">
                      <span className="w-1.5 h-1.5 rounded-full bg-[#7C3AED] animate-ping" />
                      Your Turn ({turnTimer}s)
                    </span>
                  ) : (
                    <span className="text-[11px] font-medium text-slate-400">Waiting...</span>
                  )}
                </div>
              </div>
            </div>

            {/* Opponent Card */}
            <div
              className={`flex items-center gap-2.5 p-2 sm:p-2.5 rounded-2xl border transition-all ${
                !isMyTurn
                  ? 'bg-sky-50 border-2 border-sky-400 shadow-sm'
                  : 'bg-white border border-slate-200 shadow-sm'
              }`}
            >
              <PlayerAvatar
                avatarUrl={opponent?.avatarUrl}
                displayName={opponent?.displayName}
                sizeClassName="w-10 h-10 text-lg"
                fallbackIcon={isAiMode ? '🤖' : '👤'}
                className="border border-sky-200 bg-sky-50 shadow-sm"
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between gap-1">
                  <span className="text-xs font-bold text-slate-800 truncate">
                    {opponent?.displayName || (isAiMode ? 'AI Bot' : 'Opponent')}
                  </span>
                  <span className="text-[10px] font-black px-1.5 py-0.5 rounded bg-sky-100 text-sky-700 flex-shrink-0">
                    {opponentLinesCount}/5
                  </span>
                </div>
                <div className="flex items-center gap-1 mt-0.5">
                  {!isMyTurn ? (
                    <span className="text-[11px] font-extrabold text-sky-600 flex items-center gap-1">
                      <span className="w-1.5 h-1.5 rounded-full bg-sky-500 animate-ping" />
                      Picking... ({turnTimer}s)
                    </span>
                  ) : (
                    <span className="text-[11px] font-medium text-slate-400">Waiting for you</span>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Desktop-only Game Guidance Panel */}
          <div className="hidden lg:block p-3.5 bg-slate-50 border border-slate-200 rounded-2xl text-xs text-slate-600 space-y-1">
            <p className="font-bold text-slate-700">🎯 Match Objective:</p>
            <p className="text-[11px] text-slate-500 leading-relaxed">
              Complete 5 lines across rows, columns, or diagonals. First to claim 5 lines wins the game!
            </p>
          </div>
        </div>

        {/* Right Column on Desktop / Center Section on Mobile: 5x5 Grid */}
        <main className="flex-1 flex flex-col items-center justify-center my-auto py-1 sm:py-2">
          <div className="aspect-square w-full max-w-[360px] sm:max-w-[420px] lg:max-w-[460px] xl:max-w-[480px] grid grid-cols-5 gap-1.5 sm:gap-2 p-2 sm:p-2.5 bg-white border border-slate-200 rounded-3xl shadow-sm">
            {board.cells.map(cell => {
              const isMarked = cell.markState.type === 'Marked';
              const isOwnPick = isMarked && (cell.markState as any).isOwnPick;
              const isLine = cell.isPartOfCompletedLine;

              return (
                <button
                  key={cell.number}
                  type="button"
                  disabled={isMarked || !isMyTurn || isGameOver}
                  onClick={() => handlePickNumber(cell.number)}
                  className={`relative flex items-center justify-center rounded-2xl font-black text-lg sm:text-2xl lg:text-3xl transition-all duration-200 cursor-pointer ${
                    isMarked
                      ? isLine
                        ? 'bg-gradient-to-tr from-amber-400 to-amber-500 text-white font-black shadow-md border-2 border-amber-300 scale-95'
                        : isOwnPick
                        ? 'bg-[#EADBFF] text-[#6B21A8] shadow-sm scale-95'
                        : 'bg-[#D3EEFF] text-[#0369A1] shadow-sm scale-95'
                      : isMyTurn
                      ? 'bg-white text-slate-800 hover:border-[#7C3AED] hover:shadow-md border-2 border-slate-300 active:scale-90 hover:scale-105'
                      : 'bg-slate-50 text-slate-400 border border-slate-200 cursor-not-allowed opacity-90'
                  }`}
                >
                  <span>{cell.number}</span>

                  {/* Pick mark badge */}
                  {isMarked && (
                    <span className="absolute bottom-1 right-1 text-[9px] font-bold opacity-80">
                      {isOwnPick ? '✓' : '•'}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </main>
      </div>

      {/* Bottom Emoji Reaction Strip & Quick Chat */}
      <footer className="mt-1 pb-1 sm:pb-2">
        <EmojiReactionStrip
          onSendEmote={handleSendEmote}
          onToggleQuickChat={() => setIsQuickChatOpen(prev => !prev)}
          isQuickChatOpen={isQuickChatOpen}
        />
      </footer>

      {/* Quick Chat Drawer Panel */}
      <QuickChatDrawer
        isOpen={isQuickChatOpen}
        onClose={() => setIsQuickChatOpen(false)}
        onSelectPhrase={handleSendPhrase}
      />

      {/* Winning / Game Over Modal */}
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
