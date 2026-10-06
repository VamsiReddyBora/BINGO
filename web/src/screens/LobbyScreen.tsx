import React, { useState } from 'react';
import {
  ArrowLeft,
  Copy,
  Share2,
  RefreshCw,
  CheckCircle2,
  Clock,
  Play,
  Check
} from 'lucide-react';
import { Player } from '../types/models';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';
import { PlayerAvatar } from '../components/PlayerAvatar';

interface Props {
  roomCode: string;
  players: Player[];
  localPlayer: Player;
  isHost: boolean;
  onToggleReady: (ready: boolean) => void;
  onStartGame: () => void;
  onRefresh: () => void;
  onBack: () => void;
}

export const LobbyScreen: React.FC<Props> = ({
  roomCode,
  players,
  localPlayer,
  isHost,
  onToggleReady,
  onStartGame,
  onRefresh,
  onBack
}) => {
  const { tokens, isDark } = useTheme();
  const [copied, setCopied] = useState(false);
  const [isReady, setIsReady] = useState(
    localPlayer.lobbyReadyStatus === 'READY'
  );

  const handleCopy = () => {
    soundEffects.playTap();
    navigator.clipboard.writeText(roomCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleShare = async () => {
    soundEffects.playTap();
    const shareUrl = `${window.location.origin}${window.location.pathname}?room=${roomCode}`;
    if (navigator.share) {
      try {
        await navigator.share({
          title: 'Join my Bingo Room!',
          text: `Join my Bingo room! Room code: ${roomCode}`,
          url: shareUrl
        });
        return;
      } catch {}
    }
    // Fallback: Copy direct room link
    navigator.clipboard.writeText(shareUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleReadyToggle = () => {
    soundEffects.playTap();
    const next = !isReady;
    setIsReady(next);
    onToggleReady(next);
  };

  // Determine readiness of all non-host players
  const nonHostPlayers = players.filter((p) => !p.isHost && p.id !== localPlayer.id);
  const readyCount = players.filter((p) => p.lobbyReadyStatus === 'READY').length;
  const canHostStart = players.length >= 2 && (nonHostPlayers.length === 0 || nonHostPlayers.every((p) => p.lobbyReadyStatus === 'READY'));

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="min-h-[100dvh] w-full flex flex-col justify-between max-w-md mx-auto p-4 sm:p-5 select-none transition-colors duration-300"
    >
      <div className="flex-1 flex flex-col">
        {/* ── Top Bar ── */}
        <div className="flex items-center gap-3 py-2">
          <button
            type="button"
            onClick={() => {
              soundEffects.playTap();
              onBack();
            }}
            style={{
              backgroundColor: tokens.surface,
              color: tokens.cellNeutralText
            }}
            className="w-9 h-9 rounded-xl flex items-center justify-center cursor-pointer active:scale-95 transition-transform"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <h1
            style={{ color: tokens.cellNeutralText }}
            className="text-xl font-bold font-heading"
          >
            Multiplayer Lobby
          </h1>
        </div>

        {/* ── Room Code Card ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-5 border shadow-sm flex flex-col items-center mt-4 transition-colors"
        >
          <span
            style={{ color: tokens.accentBrand }}
            className="text-[11px] font-bold tracking-widest uppercase mb-1"
          >
            ROOM CODE
          </span>

          <span
            style={{ color: tokens.cellNeutralText }}
            className="text-4xl font-black font-mono tracking-[0.25em] my-1"
          >
            {roomCode}
          </span>

          <div className="flex items-center gap-3 mt-3">
            <button
              type="button"
              onClick={handleCopy}
              style={{
                borderColor: tokens.surfaceBorder,
                color: tokens.textPrimary
              }}
              className="px-3.5 py-1.5 rounded-xl border text-xs font-semibold flex items-center gap-1.5 hover:opacity-85 active:scale-95 transition-all cursor-pointer shadow-xs"
            >
              {copied ? (
                <>
                  <Check className="w-3.5 h-3.5 text-emerald-500" />
                  <span className="text-emerald-500">Copied!</span>
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" />
                  <span>Copy</span>
                </>
              )}
            </button>

            <button
              type="button"
              onClick={handleShare}
              style={{
                backgroundColor: tokens.primaryButtonBg,
                color: tokens.primaryButtonText
              }}
              className="px-4 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90 active:scale-95 transition-all cursor-pointer shadow-xs"
            >
              <Share2 className="w-3.5 h-3.5" />
              <span>Share Code</span>
            </button>
          </div>
        </div>

        {/* ── Players in Lobby Card ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-5 border shadow-sm flex flex-col mt-4 transition-colors"
        >
          <div className="flex items-center justify-between pb-3 border-b border-inherit">
            <span
              style={{ color: tokens.textMuted }}
              className="text-[11px] font-bold tracking-wider uppercase opacity-80"
            >
              PLAYERS IN LOBBY ({players.length})
            </span>

            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                onRefresh();
              }}
              style={{ color: tokens.accentBrand }}
              className="text-xs font-bold flex items-center gap-1 cursor-pointer active:scale-95 hover:opacity-80"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              <span>Sync</span>
            </button>
          </div>

          {/* Player list */}
          <div className="divide-y divide-inherit">
            {players.map((player) => {
              const isPlayerHost = player.isHost;
              const isReadyStatus = player.lobbyReadyStatus === 'READY';

              return (
                <div
                  key={player.id}
                  className="py-3 flex items-center justify-between"
                >
                  <div className="flex items-center gap-3">
                    <div className="relative">
                      <PlayerAvatar
                        avatarUrl={player.avatarUrl}
                        displayName={player.displayName}
                        username={player.username}
                        size={40}
                        borderColor={isPlayerHost ? tokens.accentBrand : undefined}
                      />
                      {isPlayerHost && (
                        <span className="absolute -top-2.5 -left-1 text-base select-none">
                          👑
                        </span>
                      )}
                    </div>

                    <div className="flex flex-col">
                      <div className="flex items-center gap-2">
                        <span
                          style={{ color: tokens.cellNeutralText }}
                          className="font-bold text-sm"
                        >
                          {player.displayName}
                        </span>
                        {player.id === localPlayer.id && (
                          <span
                            style={{
                              backgroundColor: `${tokens.accentBrand}22`,
                              color: tokens.accentBrand
                            }}
                            className="text-[10px] font-bold px-1.5 py-0.5 rounded-md"
                          >
                            You
                          </span>
                        )}
                      </div>
                      <span className="text-[11px] text-emerald-500 font-medium">
                        in-lobby
                      </span>
                    </div>
                  </div>

                  {/* Ready Icon */}
                  <div>
                    {isReadyStatus ? (
                      <div className="flex items-center gap-1 text-emerald-500 text-xs font-bold">
                        <CheckCircle2 className="w-4 h-4" />
                        <span>Ready</span>
                      </div>
                    ) : (
                      <div className="flex items-center gap-1 text-amber-500 text-xs font-semibold">
                        <Clock className="w-4 h-4" />
                        <span>Waiting</span>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {players.length < 2 && (
            <div
              style={{ backgroundColor: tokens.backgroundSecondary }}
              className="mt-3 p-3 rounded-xl text-center"
            >
              <p style={{ color: tokens.textMuted }} className="text-xs">
                Waiting for other players to enter room code {roomCode}…
              </p>
            </div>
          )}
        </div>
      </div>

      {/* ── Bottom Sticky Action Bar ── */}
      <div className="pt-4 pb-2">
        {isHost ? (
          <div className="flex flex-col gap-2">
            <button
              type="button"
              disabled={!canHostStart}
              onClick={() => {
                soundEffects.playTap();
                onStartGame();
              }}
              style={{
                backgroundColor: canHostStart
                  ? tokens.primaryButtonBg
                  : tokens.surfaceBorder,
                color: canHostStart
                  ? tokens.primaryButtonText
                  : tokens.textMuted
              }}
              className="w-full h-12 rounded-xl font-bold text-sm flex items-center justify-center gap-2 transition-all cursor-pointer active:scale-98 shadow-sm disabled:cursor-not-allowed"
            >
              <Play className="w-4 h-4 fill-current" />
              <span>
                {players.length < 2
                  ? 'Need at least 2 Players'
                  : !canHostStart
                  ? `Waiting for Players to be Ready (${readyCount}/${players.length})`
                  : 'Start Match (5×5)'}
              </span>
            </button>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            <button
              type="button"
              onClick={handleReadyToggle}
              style={{
                backgroundColor: isReady
                  ? 'transparent'
                  : tokens.primaryButtonBg,
                borderColor: isReady
                  ? isDark
                    ? '#FFFFFF'
                    : tokens.accentBrand
                  : 'transparent',
                color: isReady
                  ? isDark
                    ? '#FFFFFF'
                    : tokens.accentBrand
                  : tokens.primaryButtonText
              }}
              className={`w-full h-12 rounded-xl font-bold text-sm flex items-center justify-center gap-2 border transition-all cursor-pointer active:scale-98 shadow-sm`}
            >
              {isReady ? (
                <span>I'm Not Ready</span>
              ) : (
                <>
                  <CheckCircle2 className="w-4 h-4" />
                  <span>I'm Ready</span>
                </>
              )}
            </button>
            <p
              style={{ color: tokens.textMuted }}
              className="text-[11px] text-center opacity-70"
            >
              {canHostStart
                ? 'All players ready! Waiting for host to start…'
                : 'Waiting for host to start once everyone is ready'}
            </p>
          </div>
        )}
      </div>
    </div>
  );
};
