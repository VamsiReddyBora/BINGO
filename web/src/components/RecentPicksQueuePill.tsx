import React from 'react';
import { useTheme } from '../theme/theme';

interface Props {
  pickedNumbersHistory: number[];
  className?: string;
}

export const RecentPicksQueuePill: React.FC<Props> = ({
  pickedNumbersHistory,
  className = ''
}) => {
  const { tokens } = useTheme();
  const validPicks = pickedNumbersHistory.filter((n) => n > 0);
  const lastThree = validPicks.slice(-3);

  // Slots: slot0, slot1, slot2 (from oldest to latest)
  const slot0 = lastThree.length >= 3 ? lastThree[0] : lastThree.length === 2 ? lastThree[0] : null;
  const slot1 = lastThree.length >= 2 ? lastThree[lastThree.length - 2] : null;
  const slot2 = lastThree.length >= 1 ? lastThree[lastThree.length - 1] : null;

  return (
    <div
      style={{
        backgroundColor: tokens.backgroundSecondary,
        borderColor: tokens.surfaceBorder
      }}
      className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full border shadow-sm select-none ${className}`}
    >
      <span className="text-[10px] uppercase font-bold tracking-wider opacity-60 mr-1">
        Recent
      </span>
      {[slot0, slot1, slot2].map((num, idx) => {
        const isLatest = idx === 2 && num !== null;
        return (
          <div
            key={idx}
            style={{
              backgroundColor: isLatest ? tokens.recentPickBg : 'transparent',
              borderColor: isLatest ? tokens.recentPickBorder : 'transparent'
            }}
            className={`w-6 h-6 rounded-full flex items-center justify-center border transition-all duration-200 ${
              isLatest ? 'scale-105 shadow-sm' : ''
            }`}
          >
            <span
              style={{
                color: isLatest
                  ? tokens.recentPickText
                  : num !== null
                  ? tokens.cellNeutralText
                  : tokens.textMuted
              }}
              className={`text-xs font-mono font-bold leading-none ${
                num === null ? 'opacity-30' : ''
              }`}
            >
              {num !== null ? num : '·'}
            </span>
          </div>
        );
      })}
    </div>
  );
};
