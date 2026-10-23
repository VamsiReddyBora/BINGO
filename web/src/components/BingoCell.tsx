import React, { useState } from 'react';
import { Cell } from '../types/models';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';

interface Props {
  cell: Cell;
  boardDimension: number;
  enabled: boolean;
  onCellClick: () => void;
  className?: string;
}

export const BingoCell: React.FC<Props> = ({
  cell,
  boardDimension,
  enabled,
  onCellClick,
  className = ''
}) => {
  const { tokens, isDark } = useTheme();
  const [isPressed, setIsPressed] = useState(false);

  const isMarked = cell.markState.type === 'Marked';
  const isOwn = isMarked && (cell.markState as any).isOwnPick;
  const isOpponent = isMarked && !(cell.markState as any).isOwnPick;
  const isOpponentRecent = isOpponent && cell.isRecentPick;
  const isCompletedLine = cell.isPartOfCompletedLine;

  const isInteractive = enabled && !isMarked;

  // Resolve styling exactly matching Android resolveMinimalCellStyling()
  let bg = tokens.cellNeutralBg;
  let text = tokens.cellNeutralText;
  let border = tokens.cellNeutralBorder;
  let bevel = tokens.cellNeutralBevel;

  if (isCompletedLine) {
    bg = tokens.completedLineBg;
    text = tokens.completedLineText;
    bevel = tokens.completedLineBevel;
    border = isDark ? '#475569' : '#64748B';
  } else if (isOwn) {
    bg = tokens.cellPlayerPickBg;
    text = tokens.cellPlayerPickText;
    border = tokens.cellPlayerPickBorder;
    bevel = tokens.cellPlayerPickBevel;
  } else if (isOpponentRecent) {
    bg = tokens.recentPickBg;
    text = tokens.recentPickText;
    border = tokens.recentPickBorder;
    bevel = tokens.recentPickBevel;
  } else if (isOpponent) {
    bg = tokens.cellOpponentPickBg;
    text = tokens.cellOpponentPickText;
    border = tokens.cellOpponentPickBorder;
    bevel = tokens.cellOpponentPickBevel;
  }

  // Tactile elevations matching Android:
  // Normal: bevelHeight = 3.5px, translateY = 0px, scale = 1.0
  // Pressed: bevelHeight = 1px, translateY = 2px, scale = 0.94
  const bevelHeight = isPressed ? 1 : 3.5;
  const translateY = isPressed ? 2 : 0;
  const pressScale = isPressed ? 0.94 : 1.0;

  const handleClick = () => {
    if (!isInteractive) return;
    try {
      if (navigator.vibrate) navigator.vibrate(20);
    } catch {}
    soundEffects.playPick();
    onCellClick();
  };

  return (
    <div
      className={`relative aspect-square w-full select-none ${isInteractive ? 'cursor-pointer' : 'cursor-default'} ${className}`}
      onMouseDown={() => isInteractive && setIsPressed(true)}
      onMouseUp={() => setIsPressed(false)}
      onMouseLeave={() => setIsPressed(false)}
      onTouchStart={() => isInteractive && setIsPressed(true)}
      onTouchEnd={() => setIsPressed(false)}
      onClick={handleClick}
    >
      {/* ── 1. Opponent Radar Ripple Aura ── */}
      {isOpponentRecent && (
        <div
          style={{
            borderColor: tokens.recentPickBorder,
            backgroundColor: tokens.recentPickGlow
          }}
          className="absolute inset-0 rounded-xl pointer-events-none animate-ping opacity-60 z-0"
        />
      )}

      {/* ── 2. Tactile 3D Extruded Body ── */}
      <div
        style={{
          transform: `translateY(${translateY}px) scale(${pressScale})`,
          transition: 'transform 80ms cubic-bezier(0.2, 0.8, 0.2, 1)',
          backgroundColor: bevel,
          borderRadius: '12px'
        }}
        className={`w-full h-full relative overflow-hidden z-10 ${
          isOpponentRecent ? 'animate-pulse' : ''
        }`}
      >
        {/* Elevated Surface Tile */}
        <div
          style={{
            height: `calc(100% - ${bevelHeight}px)`,
            backgroundColor: bg,
            borderColor: border,
            borderWidth: '1px',
            borderRadius: '12px'
          }}
          className="w-full flex items-center justify-center transition-colors duration-200 border"
        >
          <span
            style={{
              color: text,
              transform: `translateY(-${bevelHeight * 0.3}px)`
            }}
            className={`font-mono text-base sm:text-lg md:text-xl transition-colors duration-200 select-none ${
              isCompletedLine
                ? 'font-black'
                : isMarked || cell.isRecentPick
                ? 'font-extrabold'
                : 'font-bold'
            }`}
          >
            {cell.number}
          </span>
        </div>
      </div>
    </div>
  );
};
