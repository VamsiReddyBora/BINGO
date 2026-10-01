import React, { useState, useEffect } from 'react';
import { ArrowLeft, Copy, Check, Share2, Play, CheckCircle2, Clock } from 'lucide-react';
import { Player, RoomMessagePacket } from '../types/models';
import { roomSync } from '../network/mqttSync';
import { soundEffects } from '../audio/sounds';

interface Props {
  roomCode: string;
  localPlayer: Player;
  onStartGame: (seed: number, firstTurnPlayerId: string, opponent: Player) => void;
  onLeaveLobby: () => void;
}

export const LobbyScreen: React.FC<Props> = ({
  roomCode,
  localPlayer,
  onStartGame,
  onLeaveLobby
}) => {
  const [players, setPlayers] = useState<Player[]>([localPlayer]);
  const [isReady, setIsReady] = useState<boolean>(localPlayer.isHost);
  const [copiedCode, setCopiedCode] = useState<boolean>(false);
  const [copiedLink, setCopiedLink] = useState<boolean>(false);
  const [countdown, setCountdown] = useState<number | null>(null);

  const opponent = players.find(p => p.id !== localPlayer.id) || null;
  const isHost = localPlayer.isHost;

  const allPlayersReady =
    players.length >= 2 &&
    players.every(p => p.lobbyReadyStatus === 'READY' || p.isHost);

  // Copy code
  const handleCopyCode = () => {
    navigator.clipboard?.writeText(roomCode);
    setCopiedCode(true);
    soundEffects.playTap();
    setTimeout(() => setCopiedCode(false), 2000);
  };

  // Copy invite link
  const handleCopyLink = () => {
    const url = `${window.location.origin}${window.location.pathname}?room=${roomCode}`;
    navigator.clipboard?.writeText(url);
    setCopiedLink(true);
    soundEffects.playTap();
    setTimeout(() => setCopiedLink(false), 2000);
  };

  // Toggle ready status
  const toggleReady = () => {
    soundEffects.playTap();
    const nextStatus = isReady ? 'NOT_READY' : 'READY';
    setIsReady(!isReady);
    roomSync.updateLocalReadyStatus(nextStatus);
  };

  // Host starts game
  const handleHostStart = () => {
    if (!isHost || !allPlayersReady || !opponent) return;

    soundEffects.playTap();
    const matchSeed = Date.now();
    const candidateIds = [localPlayer.id, opponent.id];
    const firstTurnId = candidateIds[Math.floor(Math.random() * candidateIds.length)];

    const startPacket: RoomMessagePacket = {
      type: 'START_GAME',
      boardSize: 5,
      seed: matchSeed,
      playerId: localPlayer.id,
      currentTurnPlayerId: firstTurnId,
      isManualBoard: false
    };

    roomSync.sendPacket(startPacket);
    // Redundant sends for zero packet loss
    setTimeout(() => roomSync.sendPacket(startPacket), 150);
    setTimeout(() => roomSync.sendPacket(startPacket), 300);

    triggerCountdown(matchSeed, firstTurnId, opponent);
  };

  const triggerCountdown = (seed: number, firstTurnId: string, opp: Player) => {
    setCountdown(3);
    soundEffects.playTap();

    let count = 3;
    const interval = setInterval(() => {
      count -= 1;
      if (count > 0) {
        setCountdown(count);
        soundEffects.playTap();
      } else {
        clearInterval(interval);
        setCountdown(null);
        soundEffects.playLine();
        onStartGame(seed, firstTurnId, opp);
      }
    }, 850);
  };

  useEffect(() => {
    roomSync.onPlayersChanged = (updated) => {
      setPlayers(updated);
    };

    roomSync.onPacketReceived = (packet) => {
      if (packet.type === 'START_GAME' && !isHost) {
        const opp = players.find(p => p.id !== localPlayer.id) || {
          id: packet.playerId,
          displayName: packet.displayName || 'Host',
          username: packet.username || '',
          isHost: true,
          score: 0,
          completedLinesCount: 0,
          gamesPlayed: 0,
          gamesWon: 0,
          currentStreak: 0,
          level: 1,
          lastSeenTimestamp: Date.now(),
          lobbyReadyStatus: 'READY',
          readyVersion: 0
        };

        const seed = packet.seed || Date.now();
        const firstTurnId = packet.currentTurnPlayerId || packet.playerId;
        triggerCountdown(seed, firstTurnId, opp);
      }
    };

    return () => {
      roomSync.onPlayersChanged = null;
      roomSync.onPacketReceived = null;
    };
  }, [isHost, localPlayer, players]);

  return (
    <div className="relative min-h-screen flex flex-col justify-between max-w-lg mx-auto p-4 select-none">
      {/* Countdown Fullscreen Overlay */}
      {countdown !== null && (
        <div className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-slate-950/90 backdrop-blur-xl animate-fade-in">
          <span className="text-8xl font-black font-heading bg-gradient-to-r from-amber-400 via-yellow-200 to-amber-500 bg-clip-text text-transparent animate-ping">
            {countdown}
          </span>
          <p className="mt-6 text-xl font-bold tracking-widest uppercase text-slate-300">
            Game Starting...
          </p>
        </div>
      )}

      {/* Top Bar */}
      <header className="flex items-center justify-between py-2">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            onLeaveLobby();
          }}
          className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-900 border border-slate-800 text-slate-400 hover:text-white transition-all cursor-pointer"
        >
          <ArrowLeft className="w-4 h-4" />
          <span className="text-xs font-bold">Leave</span>
        </button>

        <span className="text-xs font-extrabold uppercase tracking-widest text-slate-400 bg-slate-900 px-3 py-1.5 rounded-full border border-slate-800">
          {isHost ? '👑 Room Host' : '🎮 Guest Player'}
        </span>
      </header>

      {/* Room Code Showcase Card */}
      <section className="my-auto py-4">
        <div className="bg-gradient-to-b from-slate-900 to-slate-950 border border-slate-800 rounded-3xl p-6 shadow-2xl text-center">
          <span className="text-xs font-bold uppercase tracking-widest text-blue-400">
            Multiplayer Room Code
          </span>

          <div className="my-3 flex items-center justify-center">
            <h1 className="text-5xl font-black tracking-widest font-heading text-white bg-slate-800/80 px-6 py-2.5 rounded-2xl border border-slate-700/80 shadow-inner">
              {roomCode}
            </h1>
          </div>

          <p className="text-xs text-slate-400">
            Share this 6-digit code or direct link with a friend on Android or PC!
          </p>

          {/* Copy Buttons */}
          <div className="mt-5 grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={handleCopyCode}
              className="py-2.5 px-3 rounded-xl bg-slate-800 hover:bg-slate-750 text-slate-200 hover:text-white border border-slate-700 flex items-center justify-center gap-2 text-xs font-bold transition-all active:scale-95 cursor-pointer"
            >
              {copiedCode ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
              {copiedCode ? 'Copied Code!' : 'Copy Code'}
            </button>

            <button
              type="button"
              onClick={handleCopyLink}
              className="py-2.5 px-3 rounded-xl bg-blue-600/20 hover:bg-blue-600/30 text-blue-300 hover:text-white border border-blue-500/40 flex items-center justify-center gap-2 text-xs font-bold transition-all active:scale-95 cursor-pointer"
            >
              {copiedLink ? <Check className="w-4 h-4 text-emerald-400" /> : <Share2 className="w-4 h-4" />}
              {copiedLink ? 'Copied Link!' : 'Share Link'}
            </button>
          </div>
        </div>

        {/* Players in Room */}
        <div className="mt-5 space-y-3">
          <h2 className="text-xs font-extrabold uppercase tracking-wider text-slate-400 px-1">
            Players in Room ({players.length}/2)
          </h2>

          {/* Host Card */}
          <div className="flex items-center justify-between p-3.5 bg-slate-900/90 border border-slate-800 rounded-2xl">
            <div className="flex items-center gap-3">
              <div className="w-11 h-11 rounded-full bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center text-xl shadow">
                {localPlayer.isHost ? (localPlayer.avatarUrl || '🧑') : (opponent?.avatarUrl || '👤')}
              </div>
              <div>
                <div className="flex items-center gap-1.5">
                  <span className="text-sm font-bold text-white">
                    {localPlayer.isHost ? `${localPlayer.displayName} (You)` : (opponent?.displayName || 'Host')}
                  </span>
                  <span className="text-[10px] font-black px-1.5 py-0.2 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30">
                    HOST
                  </span>
                </div>
                <span className="text-[11px] font-medium text-slate-400">
                  Ready to start
                </span>
              </div>
            </div>

            <div className="flex items-center gap-1.5 text-xs font-bold text-emerald-400 bg-emerald-950/40 px-2.5 py-1 rounded-full border border-emerald-500/30">
              <CheckCircle2 className="w-3.5 h-3.5" />
              <span>READY</span>
            </div>
          </div>

          {/* Guest Card */}
          {opponent ? (
            <div className="flex items-center justify-between p-3.5 bg-slate-900/90 border border-slate-800 rounded-2xl animate-fade-in">
              <div className="flex items-center gap-3">
                <div className="w-11 h-11 rounded-full bg-gradient-to-tr from-purple-600 to-rose-600 flex items-center justify-center text-xl shadow">
                  {!localPlayer.isHost ? (localPlayer.avatarUrl || '🧑') : (opponent.avatarUrl || '👤')}
                </div>
                <div>
                  <span className="text-sm font-bold text-white">
                    {!localPlayer.isHost ? `${localPlayer.displayName} (You)` : opponent.displayName}
                  </span>
                  <p className="text-[11px] font-medium text-slate-400">
                    {opponent.lobbyReadyStatus === 'READY' ? 'Ready to play' : 'Waiting for ready'}
                  </p>
                </div>
              </div>

              <div
                className={`flex items-center gap-1.5 text-xs font-bold px-2.5 py-1 rounded-full border ${
                  opponent.lobbyReadyStatus === 'READY'
                    ? 'text-emerald-400 bg-emerald-950/40 border-emerald-500/30'
                    : 'text-amber-400 bg-amber-950/40 border-amber-500/30'
                }`}
              >
                {opponent.lobbyReadyStatus === 'READY' ? (
                  <>
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    <span>READY</span>
                  </>
                ) : (
                  <>
                    <Clock className="w-3.5 h-3.5" />
                    <span>NOT READY</span>
                  </>
                )}
              </div>
            </div>
          ) : (
            <div className="flex items-center justify-center p-6 border-2 border-dashed border-slate-800 rounded-2xl text-center">
              <div className="flex flex-col items-center">
                <span className="animate-pulse text-2xl mb-1">⏳</span>
                <span className="text-xs font-bold text-slate-400">
                  Waiting for opponent to join...
                </span>
                <span className="text-[10px] text-slate-500 mt-0.5">
                  Share the room code or link above
                </span>
              </div>
            </div>
          )}
        </div>
      </section>

      {/* Bottom Action Bar */}
      <footer className="mt-4 pt-2">
        {isHost ? (
          <button
            type="button"
            disabled={!allPlayersReady}
            onClick={handleHostStart}
            className={`w-full py-4 px-6 rounded-2xl font-black text-sm tracking-wider uppercase flex items-center justify-center gap-2 shadow-xl transition-all cursor-pointer ${
              allPlayersReady
                ? 'bg-gradient-to-r from-blue-600 via-indigo-600 to-blue-500 hover:from-blue-500 hover:to-indigo-500 text-white shadow-blue-500/30 active:scale-95'
                : 'bg-slate-800 text-slate-500 border border-slate-700/60 cursor-not-allowed opacity-80'
            }`}
          >
            <Play className="w-5 h-5 fill-current" />
            {players.length < 2
              ? 'Waiting for Player...'
              : !allPlayersReady
              ? 'Waiting for Opponent to Ready...'
              : 'START GAME NOW'}
          </button>
        ) : (
          <button
            type="button"
            onClick={toggleReady}
            className={`w-full py-4 px-6 rounded-2xl font-black text-sm tracking-wider uppercase flex items-center justify-center gap-2 shadow-xl transition-all active:scale-95 cursor-pointer ${
              isReady
                ? 'bg-emerald-600 hover:bg-emerald-500 text-white shadow-emerald-500/30'
                : 'bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white shadow-blue-500/30'
            }`}
          >
            <CheckCircle2 className="w-5 h-5" />
            {isReady ? 'YOU ARE READY (TAP TO UNREADY)' : 'READY UP'}
          </button>
        )}
      </footer>
    </div>
  );
};
