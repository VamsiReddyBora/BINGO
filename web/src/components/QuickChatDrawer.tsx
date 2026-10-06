import React, { useState } from 'react';
import { X, Send } from 'lucide-react';
import { soundEffects } from '../audio/sounds';
import { useTheme } from '../theme/theme';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSendMessage: (message: string) => void;
}

const QUICK_PHRASES = [
  'Hello! 👋',
  'Nice move! 🎯',
  "I'm close to BINGO! ⚡",
  'Good game! 🤝',
  'Hurry up! ⏳',
  'Well played! 👏',
  'Good luck! 🍀',
  'Thanks! 🙌',
  'Oops! 😅',
  'Boom! 💥',
  'GG! 🏆',
  'One more game? 🎲'
];

export const QuickChatDrawer: React.FC<Props> = ({
  isOpen,
  onClose,
  onSendMessage
}) => {
  const { tokens, isDark } = useTheme();
  const [customInput, setCustomInput] = useState<string>('');

  if (!isOpen) return null;

  const handleSendCustom = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const trimmed = customInput.trim();
    if (!trimmed) return;
    soundEffects.playTap();
    onSendMessage(trimmed);
    setCustomInput('');
    onClose();
  };

  const handleSelectPhrase = (phrase: string) => {
    soundEffects.playTap();
    onSendMessage(phrase);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col justify-end bg-black/60 backdrop-blur-sm animate-fade-in select-none">
      {/* Click outside backdrop to close */}
      <div className="flex-1" onClick={onClose} />

      <div
        style={{
          backgroundColor: tokens.surface,
          borderColor: tokens.surfaceBorder
        }}
        className="w-full max-w-md mx-auto border-t rounded-t-3xl p-4 sm:p-5 shadow-2xl animate-slide-up"
      >
        {/* Header */}
        <div
          style={{ borderColor: tokens.surfaceBorder }}
          className="flex items-center justify-between pb-3 border-b"
        >
          <div className="flex items-center gap-2">
            <span className="text-base">💬</span>
            <span
              style={{ color: tokens.cellNeutralText }}
              className="text-sm font-bold tracking-wide"
            >
              In-Game Live Chat
            </span>
          </div>
          <button
            type="button"
            onClick={onClose}
            style={{
              backgroundColor: tokens.backgroundSecondary,
              color: tokens.textSecondary
            }}
            className="w-8 h-8 rounded-full flex items-center justify-center transition-all cursor-pointer active:scale-95"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Custom Message Input */}
        <form onSubmit={handleSendCustom} className="mt-3.5">
          <div className="relative flex items-center">
            <input
              type="text"
              maxLength={100}
              value={customInput}
              onChange={(e) => setCustomInput(e.target.value)}
              placeholder="Type your message..."
              autoFocus
              style={{
                backgroundColor: tokens.backgroundSecondary,
                borderColor: tokens.surfaceBorder,
                color: tokens.cellNeutralText
              }}
              className="w-full pl-3.5 pr-20 py-2.5 rounded-2xl border text-sm focus:outline-none focus:ring-1 focus:ring-purple-500 transition-all select-text"
            />
            <div className="absolute right-1.5 flex items-center gap-1.5">
              <span
                style={{ color: tokens.textMuted }}
                className="text-[10px] font-semibold"
              >
                {customInput.length}/100
              </span>
              <button
                type="submit"
                disabled={!customInput.trim()}
                style={{
                  backgroundColor: customInput.trim()
                    ? tokens.primaryButtonBg
                    : tokens.surfaceBorder,
                  color: customInput.trim()
                    ? tokens.primaryButtonText
                    : tokens.textMuted
                }}
                className="w-8 h-8 rounded-xl flex items-center justify-center transition-all cursor-pointer disabled:cursor-not-allowed"
              >
                <Send className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </form>

        {/* Quick Phrases Section */}
        <div className="mt-3.5">
          <span
            style={{ color: tokens.textMuted }}
            className="text-[11px] font-extrabold uppercase tracking-wider block mb-2"
          >
            Quick Phrases
          </span>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-1.5 max-h-48 overflow-y-auto no-scrollbar pr-0.5">
            {QUICK_PHRASES.map((phrase, idx) => (
              <button
                key={idx}
                type="button"
                onClick={() => handleSelectPhrase(phrase)}
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder,
                  color: tokens.cellNeutralText
                }}
                className="py-2 px-2.5 rounded-xl border text-left text-xs font-semibold hover:border-purple-400 active:scale-95 transition-all cursor-pointer truncate"
              >
                {phrase}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
