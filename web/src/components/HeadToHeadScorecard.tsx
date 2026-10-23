import React from 'react';
import { PlayerAvatar } from './PlayerAvatar';
import { useTheme } from '../theme/theme';

interface Props {
  playerName: string;
  playerAvatarUrl?: string | null;
  playerUsername?: string;
  playerLines: number;

  opponentName: string;
  opponentAvatarUrl?: string | null;
  opponentUsername?: string;
  opponentLines: number;

  targetLines?: number;
  isMyTurn: boolean;
  className?: string;
}

export const HeadToHeadScorecard: React.FC<Props> = ({
  playerName,
  playerAvatarUrl,
  playerUsername,
  playerLines,
  opponentName,
  opponentAvatarUrl,
  opponentUsername,
  opponentLines,
  targetLines = 5,
  isMyTurn,
  className = ''
}) => {
  const { tokens } = useTheme();

  const isPlayerMatchPoint = playerLines >= targetLines - 1 && playerLines < targetLines;
  const isOpponentMatchPoint = opponentLines >= targetLines - 1 && opponentLines < targetLines;

  return (
    <div
      style={{
        backgroundColor: tokens.surface,
        borderColor: isPlayerMatchPoint
          ? tokens.accentBrand
          : isOpponentMatchPoint
          ? tokens.accentOpponent
          : tokens.surfaceBorder
      }}
      className={`w-full max-w-[430px] mx-auto rounded-2xl p-3 border shadow-sm flex flex-col select-none transition-all duration-300 ${className}`}
    >
      {/* ── Match Point Alert Banner ── */}
      {(isPlayerMatchPoint || isOpponentMatchPoint) && (
        <div
          style={{
            backgroundColor: isPlayerMatchPoint
              ? `${tokens.cellPlayerPickBg}22`
              : `${tokens.cellOpponentPickBg}22`,
            borderColor: isPlayerMatchPoint
              ? tokens.accentBrand
              : tokens.accentOpponent
          }}
          className="mb-2 py-1 px-3 rounded-xl border flex items-center justify-center animate-pulse"
        >
          <span
            style={{
              color: isPlayerMatchPoint
                ? tokens.accentBrand
                : tokens.accentOpponent
            }}
            className="text-[11px] font-black uppercase tracking-wider"
          >
            {isPlayerMatchPoint && isOpponentMatchPoint
              ? '⚡ DUAL MATCH POINT! Next line wins!'
              : isPlayerMatchPoint
              ? '🔥 MATCH POINT! You need 1 more line!'
              : `⚠️ WATCH OUT! ${opponentName} needs 1 more line!`}
          </span>
        </div>
      )}

      {/* ── Players Row ── */}
      <div className="flex items-center justify-between gap-3">
        {/* Left: Local Player */}
        <div className="flex items-center gap-2.5 flex-1 min-w-0">
          <div className="relative">
            <PlayerAvatar
              avatarUrl={playerAvatarUrl}
              displayName={playerName}
              username={playerUsername}
              size={38}
              borderColor={isMyTurn ? tokens.accentBrand : undefined}
            />
            {isMyTurn && (
              <span className="absolute -bottom-0.5 -right-0.5 w-3 h-3 bg-emerald-500 border-2 border-white dark:border-black rounded-full" />
            )}
          </div>
          <div className="flex flex-col min-w-0">
            <span
              style={{ color: tokens.cellNeutralText }}
              className="text-xs sm:text-sm font-bold truncate"
            >
              {playerName || 'You'}
            </span>
            {/* Progress Pips */}
            <div className="flex items-center gap-1 mt-0.5">
              {Array.from({ length: targetLines }).map((_, i) => (
                <div
                  key={`p_pip_${i}`}
                  style={{
                    backgroundColor:
                      i < playerLines ? tokens.cellPlayerPickBg : `${tokens.surfaceBorder}`
                  }}
                  className="w-2 h-2 rounded-full transition-all duration-300"
                />
              ))}
              <span className="text-[10px] font-mono ml-1 font-bold opacity-75">
                {playerLines}/{targetLines}
              </span>
            </div>
          </div>
        </div>

        {/* Center: VS divider */}
        <div className="flex flex-col items-center px-1">
          <span
            style={{ color: tokens.textMuted }}
            className="text-[10px] font-black tracking-widest uppercase opacity-60"
          >
            VS
          </span>
        </div>

        {/* Right: Opponent Player */}
        <div className="flex items-center justify-end gap-2.5 flex-1 min-w-0 text-right">
          <div className="flex flex-col items-end min-w-0">
            <span
              style={{ color: tokens.cellNeutralText }}
              className="text-xs sm:text-sm font-bold truncate max-w-[100px] sm:max-w-[120px]"
            >
              {opponentName || 'Opponent'}
            </span>
            {/* Progress Pips */}
            <div className="flex items-center gap-1 mt-0.5">
              <span className="text-[10px] font-mono mr-1 font-bold opacity-75">
                {opponentLines}/{targetLines}
              </span>
              {Array.from({ length: targetLines }).map((_, i) => (
                <div
                  key={`o_pip_${i}`}
                  style={{
                    backgroundColor:
                      i < opponentLines
                        ? tokens.cellOpponentPickBg
                        : `${tokens.surfaceBorder}`
                  }}
                  className="w-2 h-2 rounded-full transition-all duration-300"
                />
              ))}
            </div>
          </div>
          <div className="relative">
            <PlayerAvatar
              avatarUrl={opponentAvatarUrl}
              displayName={opponentName}
              username={opponentUsername}
              size={38}
              borderColor={!isMyTurn ? tokens.accentOpponent : undefined}
            />
            {!isMyTurn && (
              <span className="absolute -bottom-0.5 -right-0.5 w-3 h-3 bg-amber-500 border-2 border-white dark:border-black rounded-full" />
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
