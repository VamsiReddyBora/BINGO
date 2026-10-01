import React, { useState } from 'react';
import { X, Send } from 'lucide-react';
import { soundEffects } from '../audio/sounds';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSendMessage: (message: string) => void;
}

const QUICK_PHRASES = [
  'Hello! 👋',
  'Nice move! 🎯',
  'Almost Bingo! ⚡',
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
    <div className="fixed inset-0 z-50 flex flex-col justify-end bg-black/40 backdrop-blur-sm animate-fade-in select-none">
      {/* Click outside backdrop to close */}
      <div className="flex-1" onClick={onClose} />

      <div className="w-full max-w-lg mx-auto bg-white border-t border-slate-200 rounded-t-3xl p-4 sm:p-5 shadow-2xl animate-slide-up">
        {/* Header */}
        <div className="flex items-center justify-between pb-3 border-b border-slate-100">
          <div className="flex items-center gap-2">
            <span className="text-base">💬</span>
            <span className="text-sm font-bold tracking-wide text-slate-800">
              In-Game Live Chat
            </span>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 rounded-full bg-slate-100 hover:bg-slate-200 text-slate-500 hover:text-slate-800 flex items-center justify-center transition-all cursor-pointer active:scale-95"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Custom Message Input (Full width matching Android) */}
        <form onSubmit={handleSendCustom} className="mt-3.5">
          <div className="relative flex items-center">
            <input
              type="text"
              maxLength={100}
              value={customInput}
              onChange={e => setCustomInput(e.target.value)}
              placeholder="Type your message..."
              autoFocus
              className="w-full pl-3.5 pr-20 py-2.5 rounded-2xl bg-slate-50 border border-slate-200 text-slate-800 text-sm focus:outline-none focus:border-purple-500 focus:bg-white focus:ring-2 focus:ring-purple-500/20 transition-all select-text"
            />
            <div className="absolute right-1.5 flex items-center gap-1.5">
              <span className="text-[10px] font-semibold text-slate-400">
                {customInput.length}/100
              </span>
              <button
                type="submit"
                disabled={!customInput.trim()}
                className={`w-8 h-8 rounded-xl flex items-center justify-center transition-all cursor-pointer ${
                  customInput.trim()
                    ? 'bg-[#7C3AED] hover:bg-[#6D28D9] text-white shadow-md active:scale-95'
                    : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                }`}
              >
                <Send className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </form>

        {/* Quick Phrases Section */}
        <div className="mt-3.5">
          <span className="text-[11px] font-extrabold uppercase tracking-wider text-slate-400 block mb-2">
            Quick Phrases
          </span>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-1.5 max-h-48 overflow-y-auto no-scrollbar pr-0.5">
            {QUICK_PHRASES.map((phrase, idx) => (
              <button
                key={idx}
                type="button"
                onClick={() => handleSelectPhrase(phrase)}
                className="py-2 px-2.5 rounded-xl bg-slate-50 hover:bg-purple-50 active:bg-purple-100 border border-slate-200/80 hover:border-purple-300 text-left text-xs font-semibold text-slate-700 hover:text-purple-700 transition-all cursor-pointer truncate"
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
