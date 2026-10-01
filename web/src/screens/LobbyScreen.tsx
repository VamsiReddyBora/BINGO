import React, { useState, useEffect, useRef } from 'react';
import { ArrowLeft, Copy, Check, Share2, Play, CheckCircle2, Clock } from 'lucide-react';
import { Player, RoomMessagePacket } from '../types/models';
import { roomSync } from '../network/mqttSync';
import { CloudRegistry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { PlayerAvatar } from '../components/PlayerAvatar';

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
  const [players, setPlayers] = useState<Player[]>(() => roomSync.getPlayers());
  const [isReady, setIsReady] = useState<boolean>(localPlayer.isHost);
  const [copiedCode, setCopiedCode] = useState<boolean>(false);
  const [copiedLink, setCopiedLink] = useState<boolean>(false);
  const [countdown, setCountdown] = useState<number | null>(null);

  const isHost = localPlayer.isHost;
  const opponent = players.find(p => p.id !== localPlayer.id) || null;

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
    CloudRegistry.updateRoomReadyStatus(roomCode, localPlayer.id, nextStatus).catch(console.warn);
  };

  // Trigger countdown & transition to GameScreen
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

  // Host starts game
  const handleHostStart = () => {
    if (!isHost || !opponent) return;

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
    setTimeout(() => roomSync.sendPacket(startPacket), 150);
    setTimeout(() => roomSync.sendPacket(startPacket), 300);

    triggerCountdown(matchSeed, firstTurnId, opponent);
  };

  useEffect(() => {
    // 1. MQTT event listeners
    roomSync.onPlayersChanged = (updated) => {
      setPlayers(updated);
    };

    roomSync.onPacketReceived = (packet) => {
      if (packet.type === 'START_GAME' && !isHost) {
        const currentPlayers = roomSync.getPlayers();
        const opp = currentPlayers.find(p => p.id !== localPlayer.id) || {
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

    // 2. High-reliability dual-channel Cloud polling (every 1.5s)
    const cloudPollTimer = setInterval(async () => {
      try {
        const session = await CloudRegistry.getRoom(roomCode);
        if (session && session.players && session.players.length > 0) {
          roomSync.mergePlayers(session.players);
        }
      } catch {}
    }, 1500);

    return () => {
      clearInterval(cloudPollTimer);
      roomSync.onPlayersChanged = null;
      roomSync.onPacketReceived = null;
    };
  }, [isHost, localPlayer.id, roomCode]);

  return (
    <div className="relative min-h-[100dvh] flex flex-col justify-between max-w-lg lg:max-w-xl mx-auto p-4 select-none bg-[#FAFAFC] text-slate-800">
      {/* Countdown Fullscreen Overlay */}
      {countdown !== null && (
        <div className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-white/95 backdrop-blur-md animate-fade-in">
          <span className="text-8xl font-black font-heading text-[#7C3AED] animate-ping">
            {countdown}
          </span>
          <p className="mt-6 text-xl font-black tracking-widest uppercase text-slate-700">
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
          className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-white border border-slate-200 text-slate-600 hover:text-slate-900 transition-all cursor-pointer shadow-sm"
        >
          <ArrowLeft className="w-4 h-4" />
          <span className="text-xs font-bold">Leave</span>
        </button>

        <span className="text-xs font-extrabold uppercase tracking-widest text-[#7C3AED] bg-[#F5EEFF] px-3 py-1.5 rounded-full border border-purple-200">
          {isHost ? '👑 Room Host' : '🎮 Guest Player'}
        </span>
      </header>

      {/* Room Code Showcase Card */}
      <section className="my-auto py-4">
        <div className="bg-white border border-slate-200 rounded-3xl p-6 shadow-sm text-center">
          <span className="text-xs font-extrabold uppercase tracking-widest text-[#7C3AED]">
            Multiplayer Room Code
          </span>

          <div className="my-3 flex items-center justify-center">
            <h1 className="text-5xl font-black tracking-widest font-heading text-[#6B21A8] bg-[#F5EEFF] px-6 py-2.5 rounded-2xl border border-purple-200 shadow-inner">
              {roomCode}
            </h1>
          </div>

          <p className="text-xs text-slate-500">
            Share this 6-digit code or link with a friend on Android or PC!
          </p>

          {/* Copy Buttons */}
          <div className="mt-5 grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={handleCopyCode}
              className="py-2.5 px-3 rounded-xl bg-slate-50 hover:bg-slate-100 text-slate-700 border border-slate-200 flex items-center justify-center gap-2 text-xs font-bold transition-all active:scale-95 cursor-pointer shadow-sm"
            >
              {copiedCode ? <Check className="w-4 h-4 text-emerald-600" /> : <Copy className="w-4 h-4" />}
              {copiedCode ? 'Copied Code!' : 'Copy Code'}
            </button>

            <button
              type="button"
              onClick={handleCopyLink}
              className="py-2.5 px-3 rounded-xl bg-[#F5EEFF] hover:bg-purple-100 text-[#7C3AED] border border-purple-200 flex items-center justify-center gap-2 text-xs font-bold transition-all active:scale-95 cursor-pointer shadow-sm"
            >
              {copiedLink ? <Check className="w-4 h-4 text-emerald-600" /> : <Share2 className="w-4 h-4" />}
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
          <div className="flex items-center justify-between p-3.5 bg-white border border-slate-200 rounded-2xl shadow-sm">
            <div className="flex items-center gap-3">
              <PlayerAvatar
                avatarUrl={localPlayer.isHost ? localPlayer.avatarUrl : opponent?.avatarUrl}
                displayName={localPlayer.isHost ? localPlayer.displayName : opponent?.displayName}
                sizeClassName="w-11 h-11 text-xl"
                fallbackIcon={localPlayer.isHost ? '🧑' : '👤'}
                className="border border-purple-200 bg-[#F5EEFF] shadow-sm"
              />
              <div>
                <div className="flex items-center gap-1.5">
                  <span className="text-sm font-bold text-slate-800">
                    {localPlayer.isHost ? `${localPlayer.displayName} (You)` : (opponent?.displayName || 'Host')}
                  </span>
                  <span className="text-[10px] font-black px-1.5 py-0.2 rounded bg-amber-100 text-amber-800 border border-amber-200">
                    HOST
                  </span>
                </div>
                <span className="text-[11px] font-medium text-slate-500">
                  Ready to start
                </span>
              </div>
            </div>

            <div className="flex items-center gap-1.5 text-xs font-bold text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200">
              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" />
              <span>READY</span>
            </div>
          </div>

          {/* Guest Card */}
          {opponent ? (
            <div className="flex items-center justify-between p-3.5 bg-white border border-slate-200 rounded-2xl shadow-sm animate-fade-in">
              <div className="flex items-center gap-3">
                <PlayerAvatar
                  avatarUrl={!localPlayer.isHost ? localPlayer.avatarUrl : opponent.avatarUrl}
                  displayName={!localPlayer.isHost ? localPlayer.displayName : opponent.displayName}
                  sizeClassName="w-11 h-11 text-xl"
                  fallbackIcon={!localPlayer.isHost ? '🧑' : '👤'}
                  className="border border-blue-200 bg-blue-50 shadow-sm"
                />
                <div>
                  <span className="text-sm font-bold text-slate-800">
                    {!localPlayer.isHost ? `${localPlayer.displayName} (You)` : opponent.displayName}
                  </span>
                  <p className="text-[11px] font-medium text-slate-500">
                    {opponent.lobbyReadyStatus === 'READY' ? 'Ready to play' : 'Waiting for ready'}
                  </p>
                </div>
              </div>

              <div
                className={`flex items-center gap-1.5 text-xs font-bold px-2.5 py-1 rounded-full border ${
                  opponent.lobbyReadyStatus === 'READY'
                    ? 'text-emerald-700 bg-emerald-50 border-emerald-200'
                    : 'text-amber-700 bg-amber-50 border-amber-200'
                }`}
              >
                {opponent.lobbyReadyStatus === 'READY' ? (
                  <>
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" />
                    <span>READY</span>
                  </>
                ) : (
                  <>
                    <Clock className="w-3.5 h-3.5 text-amber-600" />
                    <span>NOT READY</span>
                  </>
                )}
              </div>
            </div>
          ) : (
            <div className="flex items-center justify-center p-6 border-2 border-dashed border-slate-200 rounded-2xl text-center bg-white">
              <div className="flex flex-col items-center">
                <span className="animate-pulse text-2xl mb-1">⏳</span>
                <span className="text-xs font-bold text-slate-600">
                  Waiting for opponent to join...
                </span>
                <span className="text-[10px] text-slate-400 mt-0.5">
                  Share the 6-digit code or link above
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
            className={`w-full py-4 px-6 rounded-2xl font-black text-sm tracking-wider uppercase flex items-center justify-center gap-2 shadow-md transition-all cursor-pointer ${
              allPlayersReady
                ? 'bg-[#7C3AED] hover:bg-[#6D28D9] text-white active:scale-95'
                : 'bg-slate-200 text-slate-400 cursor-not-allowed'
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
            className={`w-full py-4 px-6 rounded-2xl font-black text-sm tracking-wider uppercase flex items-center justify-center gap-2 shadow-md transition-all active:scale-95 cursor-pointer ${
              isReady
                ? 'bg-[#16A34A] hover:bg-emerald-700 text-white'
                : 'bg-[#7C3AED] hover:bg-[#6D28D9] text-white'
            }`}
          >
            <CheckCircle2 className="w-5 h-5" />
            {isReady ? 'YOU ARE READY (TAP TO CANCEL)' : 'TAP TO READY'}
          </button>
        )}
      </footer>
    </div>
  );
};
