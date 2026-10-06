import React from 'react';
import { ArrowLeft, Server, LogIn } from 'lucide-react';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';

interface Props {
  onHostGame: () => void;
  onJoinGame: () => void;
  onBack: () => void;
}

export const OnlineMatchChoiceScreen: React.FC<Props> = ({
  onHostGame,
  onJoinGame,
  onBack
}) => {
  const { tokens, isDark } = useTheme();

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="min-h-[100dvh] w-full flex flex-col max-w-md mx-auto p-4 sm:p-5 select-none transition-colors duration-300"
    >
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
          Online Match
        </h1>
      </div>

      <div className="mt-6 mb-3">
        <span
          style={{ color: tokens.textMuted }}
          className="text-[11px] font-bold tracking-wider uppercase opacity-80"
        >
          HOW DO YOU WANT TO PLAY?
        </span>
      </div>

      {/* ── 1. Host a Game Card ── */}
      <div
        onClick={() => {
          soundEffects.playTap();
          onHostGame();
        }}
        style={{
          backgroundColor: tokens.surface,
          borderColor: tokens.surfaceBorder
        }}
        className="w-full rounded-2xl p-4 sm:p-5 border shadow-sm flex items-start gap-4 mb-3.5 cursor-pointer hover:border-purple-400 active:scale-98 transition-all"
      >
        <div
          style={{
            backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : `${tokens.accentBrand}18`,
            color: isDark ? '#FFFFFF' : tokens.accentBrand
          }}
          className="w-12 h-12 rounded-2xl flex items-center justify-center flex-shrink-0"
        >
          <Server className="w-6 h-6" />
        </div>
        <div className="flex flex-col flex-1">
          <span
            style={{ color: tokens.cellNeutralText }}
            className="text-base font-bold mb-1"
          >
            Host a Game
          </span>
          <span
            style={{ color: tokens.textMuted }}
            className="text-xs leading-relaxed"
          >
            Create a room, share the code with friends, and start the match when everyone joins.
          </span>
        </div>
      </div>

      {/* ── 2. Join a Game Card ── */}
      <div
        onClick={() => {
          soundEffects.playTap();
          onJoinGame();
        }}
        style={{
          backgroundColor: tokens.surface,
          borderColor: tokens.surfaceBorder
        }}
        className="w-full rounded-2xl p-4 sm:p-5 border shadow-sm flex items-start gap-4 cursor-pointer hover:border-orange-400 active:scale-98 transition-all"
      >
        <div
          style={{
            backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : `${tokens.accentOpponent}18`,
            color: isDark ? '#FFFFFF' : tokens.accentOpponent
          }}
          className="w-12 h-12 rounded-2xl flex items-center justify-center flex-shrink-0"
        >
          <LogIn className="w-6 h-6" />
        </div>
        <div className="flex flex-col flex-1">
          <span
            style={{ color: tokens.cellNeutralText }}
            className="text-base font-bold mb-1"
          >
            Join a Game
          </span>
          <span
            style={{ color: tokens.textMuted }}
            className="text-xs leading-relaxed"
          >
            Enter a room code shared by a friend or host to join their match.
          </span>
        </div>
      </div>
    </div>
  );
};
