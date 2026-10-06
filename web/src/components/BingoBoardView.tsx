import React from 'react';
import { Board } from '../types/models';
import { BingoCell } from './BingoCell';
import { useTheme } from '../theme/theme';

interface Props {
  board: Board;
  isInteractive: boolean;
  onCellClick: (number: number) => void;
  isWinningBoard?: boolean;
  className?: string;
}

const BINGO_LETTERS = ['B', 'I', 'N', 'G', 'O'];

export const BingoBoardView: React.FC<Props> = ({
  board,
  isInteractive,
  onCellClick,
  isWinningBoard = false,
  className = ''
}) => {
  const { tokens } = useTheme();
  const size = board.size || 5;

  return (
    <div className={`w-full max-w-[430px] mx-auto flex flex-col items-center select-none ${className}`}>
      {/* ── B - I - N - G - O Column Letters Row ── */}
      <div className="w-full grid grid-cols-5 gap-2 px-1 mb-2">
        {Array.from({ length: size }).map((_, c) => {
          const letter = c < BINGO_LETTERS.length ? BINGO_LETTERS[c] : 'O';
          const isUnlocked = c < board.completedLines.length || isWinningBoard;

          return (
            <div
              key={`letter_${c}`}
              className="relative h-9 flex items-center justify-center"
            >
              <span
                style={{
                  color: isUnlocked
                    ? tokens.isDark
                      ? '#F59E0B'
                      : '#D97706'
                    : tokens.cellNeutralText
                }}
                className={`text-2xl sm:text-3xl font-black font-heading tracking-widest transition-colors duration-300 ${
                  isUnlocked ? 'scale-105' : 'opacity-85'
                }`}
              >
                {letter}
              </span>

              {/* Diagonal pen strike across the completed letter */}
              {isUnlocked && (
                <svg
                  className="absolute inset-0 w-full h-full pointer-events-none"
                  viewBox="0 0 40 40"
                  fill="none"
                >
                  <line
                    x1="6"
                    y1="34"
                    x2="34"
                    y2="6"
                    stroke={tokens.isDark ? '#F59E0B' : '#D97706'}
                    strokeWidth="3.5"
                    strokeLinecap="round"
                    className="transition-all duration-300"
                  />
                </svg>
              )}
            </div>
          );
        })}
      </div>

      {/* ── 5x5 Grid of Cells ── */}
      <div className="w-full grid grid-cols-5 gap-2 px-1">
        {board.cells.map((cell) => (
          <BingoCell
            key={`cell_${cell.row}_${cell.col}`}
            cell={cell}
            boardDimension={size}
            enabled={isInteractive}
            onCellClick={() => onCellClick(cell.number)}
          />
        ))}
      </div>
    </div>
  );
};
