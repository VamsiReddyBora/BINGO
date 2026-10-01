import React, { useEffect } from 'react';
import { FloatingEmoteItem } from '../types/models';

interface Props {
  emotes: FloatingEmoteItem[];
  onRemoveEmote: (id: string) => void;
}

export const FloatingEmotes: React.FC<Props> = ({ emotes, onRemoveEmote }) => {
  useEffect(() => {
    const timers = emotes.map(e =>
      setTimeout(() => {
        onRemoveEmote(e.id);
      }, 2900)
    );
    return () => {
      timers.forEach(t => clearTimeout(t));
    };
  }, [emotes, onRemoveEmote]);

  return (
    <div className="pointer-events-none fixed inset-0 z-40 overflow-hidden">
      {emotes.map(item => {
        const leftPercent = Math.min(86, Math.max(8, item.startXRatio * 100));
        // Base emoji font size 2rem (32px), scaled by scaleMultiplier (up to 2.85x)
        const baseSizePx = 36;
        const finalSizePx = Math.round(baseSizePx * (item.scaleMultiplier || 1.0));

        return (
          <div
            key={item.id}
            className="absolute bottom-28 flex flex-col items-center animate-float-emote"
            style={{
              left: `${leftPercent}%`,
              transformOrigin: 'bottom center'
            }}
          >
            {/* Sender pill badge if available */}
            {item.senderName && (
              <span className="mb-1 text-[11px] font-bold px-2 py-0.5 rounded-full bg-slate-900/80 text-blue-300 border border-blue-500/30 backdrop-blur-md shadow-md">
                {item.senderName}
              </span>
            )}

            {/* Enlarged floating reaction emoji */}
            <span
              style={{
                fontSize: `${finalSizePx}px`,
                filter: 'drop-shadow(0 4px 12px rgba(0, 0, 0, 0.45))'
              }}
              className="select-none leading-none transform transition-transform"
            >
              {item.emoji}
            </span>
          </div>
        );
      })}
    </div>
  );
};
