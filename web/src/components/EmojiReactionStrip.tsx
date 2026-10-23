import React, { useRef, useState, useEffect } from 'react';
import { MessageSquare } from 'lucide-react';
import { soundEffects } from '../audio/sounds';

interface Props {
  onSendEmote: (emoji: string, scale: number) => void;
  onToggleQuickChat: () => void;
  isQuickChatOpen: boolean;
}

const EMOJI_LIST = [
  '😂', '🔥', '🎉', '👏', '😎', '🥳', '😱', '👍',
  '❤️', '🏆', '🤯', '🚀', '💪', '⚡', '✨', '💯',
  '🎯', '💥', '🤩', '🙌', '💀', '🤡', '🎲', '⭐'
];

export const EmojiReactionStrip: React.FC<Props> = ({
  onSendEmote,
  onToggleQuickChat,
  isQuickChatOpen
}) => {
  const stripRef = useRef<HTMLDivElement>(null);

  // Quick Chat button long-press detection
  const quickChatTimerRef = useRef<any>(null);
  const isQuickChatLongPressed = useRef(false);

  // Hold-to-Grow state
  const [activeHeldEmoji, setActiveHeldEmoji] = useState<string | null>(null);
  const [currentScale, setCurrentScale] = useState<number>(1.0);
  const [bubbleX, setBubbleX] = useState<number>(0);
  const holdStartTimeRef = useRef<number>(0);
  const holdAnimationRef = useRef<any>(null);
  const startPointerPos = useRef<{ x: number; y: number }>({ x: 0, y: 0 });

  // 1. Quick Chat button handlers
  const handleQuickChatPointerDown = () => {
    isQuickChatLongPressed.current = false;
    quickChatTimerRef.current = setTimeout(() => {
      isQuickChatLongPressed.current = true;
      if (stripRef.current) {
        stripRef.current.scrollTo({ left: 0, behavior: 'smooth' });
        soundEffects.playTap();
      }
    }, 320);
  };

  const handleQuickChatPointerUp = () => {
    if (quickChatTimerRef.current) {
      clearTimeout(quickChatTimerRef.current);
    }
    if (!isQuickChatLongPressed.current) {
      onToggleQuickChat();
      soundEffects.playTap();
    }
  };

  const handleQuickChatPointerCancel = () => {
    if (quickChatTimerRef.current) {
      clearTimeout(quickChatTimerRef.current);
    }
  };

  // 2. Emoji Hold-to-Grow handlers
  const handleEmojiPointerDown = (emoji: string, e: React.PointerEvent<HTMLButtonElement>) => {
    const target = e.currentTarget;
    const rect = target.getBoundingClientRect();
    setBubbleX(rect.left + rect.width / 2);
    startPointerPos.current = { x: e.clientX, y: e.clientY };

    setActiveHeldEmoji(emoji);
    setCurrentScale(1.0);
    holdStartTimeRef.current = Date.now();

    // Start scale loop
    const updateScale = () => {
      const elapsed = Date.now() - holdStartTimeRef.current;
      if (elapsed > 140) {
        const progress = Math.min(1.0, (elapsed - 140) / 1400);
        const scale = 1.0 + 1.85 * progress; // 1.0 to 2.85
        setCurrentScale(parseFloat(scale.toFixed(2)));
      }
      holdAnimationRef.current = requestAnimationFrame(updateScale);
    };

    holdAnimationRef.current = requestAnimationFrame(updateScale);
  };

  const handleEmojiPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!activeHeldEmoji) return;
    const dx = Math.abs(e.clientX - startPointerPos.current.x);
    const dy = Math.abs(e.clientY - startPointerPos.current.y);
    // If finger moves more than 18px horizontally, treat as scroll and cancel
    if (dx > 18 || dy > 24) {
      cancelHold();
    }
  };

  const handleEmojiPointerUp = () => {
    if (!activeHeldEmoji) return;
    const elapsed = Date.now() - holdStartTimeRef.current;
    const finalScale = elapsed < 160 ? 1.0 : currentScale;

    onSendEmote(activeHeldEmoji, finalScale);
    soundEffects.playEmotePop();

    cancelHold();
  };

  const cancelHold = () => {
    if (holdAnimationRef.current) {
      cancelAnimationFrame(holdAnimationRef.current);
    }
    setActiveHeldEmoji(null);
    setCurrentScale(1.0);
  };

  useEffect(() => {
    return () => {
      if (holdAnimationRef.current) cancelAnimationFrame(holdAnimationRef.current);
      if (quickChatTimerRef.current) clearTimeout(quickChatTimerRef.current);
    };
  }, []);

  return (
    <div className="relative w-full max-w-lg mx-auto px-2 select-none">
      {/* Real-time Magnification Floating Bubble Preview */}
      {activeHeldEmoji && currentScale > 1.05 && (
        <div
          className="fixed pointer-events-none z-50 transform -translate-x-1/2 -translate-y-full mb-3"
          style={{
            left: `${bubbleX}px`,
            bottom: '84px'
          }}
        >
          <div className="relative flex flex-col items-center">
            {/* Glowing circular bubble */}
            <div className="w-16 h-16 rounded-full bg-white border-2 border-purple-500 shadow-[0_4px_20px_rgba(124,58,237,0.35)] flex items-center justify-center animate-pulse-subtle">
              <span
                style={{
                  fontSize: `${Math.round(26 * (currentScale / 1.5))}px`
                }}
                className="select-none leading-none transform transition-transform"
              >
                {activeHeldEmoji}
              </span>
            </div>

            {/* Scale badge */}
            <span className="mt-1 text-[10px] font-extrabold px-1.5 py-0.5 rounded-full bg-purple-600 text-white shadow">
              {currentScale.toFixed(1)}x
            </span>

            {/* Bubble arrow pointing down */}
            <div className="w-2.5 h-2.5 bg-white border-r-2 border-b-2 border-purple-500 transform rotate-45 -mt-1" />
          </div>
        </div>
      )}

      {/* Main Container Pill */}
      <div className="flex items-center gap-1.5 p-1 sm:p-1.5 bg-white/95 border border-slate-200/90 rounded-full shadow-lg backdrop-blur-xl w-full max-w-md mx-auto overflow-hidden box-border">
        {/* Quick Chat Button (Click = toggle, Long-press = scroll to start) */}
        <button
          type="button"
          title="Single tap: Toggle chat | Long press: Scroll strip to start"
          onPointerDown={handleQuickChatPointerDown}
          onPointerUp={handleQuickChatPointerUp}
          onPointerCancel={handleQuickChatPointerCancel}
          className={`flex-shrink-0 w-9 h-9 sm:w-10 sm:h-10 rounded-full flex items-center justify-center transition-all ${
            isQuickChatOpen
              ? 'bg-purple-600 text-white shadow-md'
              : 'bg-slate-100 text-slate-700 hover:text-purple-600 hover:bg-purple-50 active:scale-95'
          }`}
        >
          <MessageSquare className="w-4 h-4" />
        </button>

        {/* Separator */}
        <div className="w-[1px] h-6 bg-slate-200 flex-shrink-0" />

        {/* Horizontally Scrollable Emoji Strip */}
        <div
          ref={stripRef}
          className="flex-1 min-w-0 flex items-center gap-1 overflow-x-auto no-scrollbar scroll-smooth py-0.5 px-1 touch-pan-x"
        >
          {EMOJI_LIST.map((emoji, idx) => (
            <button
              key={idx}
              type="button"
              onPointerDown={e => handleEmojiPointerDown(emoji, e)}
              onPointerMove={handleEmojiPointerMove}
              onPointerUp={handleEmojiPointerUp}
              onPointerCancel={cancelHold}
              className="flex-shrink-0 w-9 h-9 rounded-full flex items-center justify-center text-xl hover:bg-slate-100 active:bg-slate-200 transition-transform active:scale-110"
            >
              {emoji}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
