import React, { useState } from 'react';
import { X, Send, Heart, CheckCircle2 } from 'lucide-react';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';
import { CloudRegistry } from '../network/cloudRegistry';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  username?: string;
}

export const DeveloperNoteModal: React.FC<Props> = ({
  isOpen,
  onClose,
  username = 'guest'
}) => {
  const { tokens } = useTheme();
  const [feedback, setFeedback] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [sentSuccess, setSentSuccess] = useState(false);

  if (!isOpen) return null;

  const handleSend = async () => {
    if (!feedback.trim()) return;
    setIsSending(true);
    soundEffects.playTap();

    try {
      const cleanMsg = feedback.trim();
      const cleanUser = username.trim().toLowerCase().replace(/^@/, '') || 'player';
      const formatted = `*Bingo Web Feedback*\n\n*From*: @${cleanUser}\n\n*Message*:\n${cleanMsg}`;

      // 1. Background backup to KeyValue cloud store
      CloudRegistry.setKeyValue(
        `feedback_${Date.now()}_${cleanUser}`,
        JSON.stringify({ user: cleanUser, message: cleanMsg, time: Date.now() })
      ).catch(() => {});

      // 2. Direct WhatsApp chat to +918688869780 (same as Android FeedbackManager)
      const encoded = encodeURIComponent(formatted);
      window.open(`https://wa.me/918688869780?text=${encoded}`, '_blank');

      setSentSuccess(true);
      setTimeout(() => {
        setSentSuccess(false);
        setFeedback('');
        onClose();
      }, 1500);
    } catch {
      setSentSuccess(true);
      setTimeout(() => {
        setSentSuccess(false);
        setFeedback('');
        onClose();
      }, 1500);
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in select-none">
      <div
        style={{
          backgroundColor: tokens.surface,
          borderColor: tokens.surfaceBorder
        }}
        className="w-full max-w-md rounded-3xl border shadow-2xl p-5 sm:p-6 overflow-hidden max-h-[90vh] flex flex-col"
      >
        {/* Header */}
        <div className="flex items-center justify-between pb-3 border-b border-inherit">
          <div className="flex items-center gap-2">
            <span className="text-xl">☕</span>
            <span
              style={{ color: tokens.cellNeutralText }}
              className="font-bold text-base"
            >
              Developer note
            </span>
          </div>
          <button
            type="button"
            onClick={onClose}
            style={{
              backgroundColor: tokens.backgroundSecondary,
              color: tokens.textSecondary
            }}
            className="w-8 h-8 rounded-full flex items-center justify-center cursor-pointer active:scale-95"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Content */}
        <div className="overflow-y-auto no-scrollbar py-3.5 space-y-3.5 flex-1 text-sm">
          <div className="flex items-center gap-2 text-rose-500 font-bold text-base">
            <Heart className="w-5 h-5 fill-rose-500" />
            <span>Made for Bingo lovers</span>
          </div>

          <p
            style={{ color: tokens.cellNeutralText }}
            className="leading-relaxed opacity-90"
          >
            Thank you so much for playing Bingo Multiplayer! This game was created out of pure love to bring the nostalgic joy of paper Bingo into a fast, real-time multiplayer experience with friends and players worldwide.
          </p>

          <p
            style={{ color: tokens.cellNeutralText }}
            className="leading-relaxed opacity-90"
          >
            If you are facing any bugs, strange glitches, or have ideas and suggestions to make the gameplay better, kindly reach out below. Every word you type is sent directly to me, and your feedback will directly shape upcoming updates!
          </p>

          <p style={{ color: tokens.textMuted }} className="text-xs font-semibold">
            — With gratitude & warmth ☕<br />
            Vamsi Reddy Bora
          </p>

          {/* Feedback input box */}
          <div className="pt-2">
            <label
              style={{ color: tokens.textMuted }}
              className="text-[11px] font-bold uppercase tracking-wider block mb-1.5"
            >
              YOUR MESSAGE OR SUGGESTION
            </label>
            <textarea
              rows={3}
              maxLength={400}
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              placeholder="Tell me what you love, report a bug, or suggest a feature..."
              style={{
                backgroundColor: tokens.backgroundSecondary,
                borderColor: tokens.surfaceBorder,
                color: tokens.cellNeutralText
              }}
              className="w-full p-3 rounded-2xl border text-sm focus:outline-none focus:ring-1 focus:ring-purple-500 transition-all select-text"
            />
          </div>
        </div>

        {/* Action Button */}
        <div className="pt-3 border-t border-inherit">
          {sentSuccess ? (
            <div className="w-full py-2.5 rounded-xl bg-emerald-500/20 border border-emerald-500 text-emerald-400 font-bold text-sm flex items-center justify-center gap-2">
              <CheckCircle2 className="w-4 h-4" />
              <span>Transmitted to Developer! Thank you ❤️</span>
            </div>
          ) : (
            <button
              type="button"
              onClick={handleSend}
              disabled={!feedback.trim() || isSending}
              style={{
                backgroundColor: feedback.trim()
                  ? tokens.primaryButtonBg
                  : tokens.surfaceBorder,
                color: feedback.trim()
                  ? tokens.primaryButtonText
                  : tokens.textMuted
              }}
              className="w-full h-11 rounded-xl font-bold text-sm flex items-center justify-center gap-2 transition-all cursor-pointer disabled:cursor-not-allowed"
            >
              <Send className="w-4 h-4" />
              <span>{isSending ? 'Transmitting...' : 'Transmit to Developer'}</span>
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
