import React from 'react';
import { X } from 'lucide-react';
import { soundEffects } from '../audio/sounds';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSelectPhrase: (phrase: string) => void;
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
  onSelectPhrase
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex flex-col justify-end bg-black/40 backdrop-blur-sm animate-fade-in select-none">
      {/* Click outside to close */}
      <div className="flex-1" onClick={onClose} />

      <div className="w-full max-w-lg mx-auto bg-white border-t border-slate-200 rounded-t-3xl p-5 shadow-2xl animate-slide-up">
        <div className="flex items-center justify-between pb-3 border-b border-slate-100">
          <span className="text-sm font-bold tracking-wide text-slate-700 uppercase">
            Quick Chat Phrases
          </span>
          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 rounded-full bg-slate-100 hover:bg-slate-200 text-slate-500 hover:text-slate-800 flex items-center justify-center transition-all cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="mt-4 grid grid-cols-2 gap-2 max-h-60 overflow-y-auto pr-1">
          {QUICK_PHRASES.map((phrase, idx) => (
            <button
              key={idx}
              type="button"
              onClick={() => {
                soundEffects.playTap();
                onSelectPhrase(phrase);
                onClose();
              }}
              className="py-2.5 px-3 rounded-xl bg-slate-50 hover:bg-purple-50 active:bg-purple-100 border border-slate-200/80 hover:border-purple-300 text-left text-sm font-semibold text-slate-700 hover:text-purple-700 transition-all cursor-pointer truncate"
            >
              {phrase}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
