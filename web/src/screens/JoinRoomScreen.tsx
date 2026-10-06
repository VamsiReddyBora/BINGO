import React, { useState } from 'react';
import { ArrowLeft, ArrowRight, AlertCircle } from 'lucide-react';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';
import { CloudRegistry } from '../network/cloudRegistry';

interface Props {
  onJoinRoom: (roomCode: string) => void;
  onBack: () => void;
}

export const JoinRoomScreen: React.FC<Props> = ({ onJoinRoom, onBack }) => {
  const { tokens } = useTheme();
  const [code, setCode] = useState('');
  const [isValidating, setIsValidating] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const handleJoin = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const clean = code.trim().toUpperCase();
    if (clean.length < 4) {
      setErrorMessage('Please enter a valid room code.');
      return;
    }

    soundEffects.playTap();
    setIsValidating(true);
    setErrorMessage(null);

    try {
      const room = await CloudRegistry.getRoom(clean);
      if (!room) {
        setErrorMessage(`Room "${clean}" not found. Verify the code with the host.`);
        setIsValidating(false);
        return;
      }

      if (room.status === 'CLOSED') {
        setErrorMessage(`Room "${clean}" has already closed.`);
        setIsValidating(false);
        return;
      }

      onJoinRoom(clean);
    } catch {
      // If network lookup fails, proceed anyway to allow P2P/MQTT join
      onJoinRoom(clean);
    } finally {
      setIsValidating(false);
    }
  };

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="min-h-[100dvh] w-full flex flex-col max-w-md mx-auto p-4 sm:p-5 select-none transition-colors duration-300"
    >
      {/* ── Top Bar ── */}
      <div className="flex items-center gap-3 py-2">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            onBack();
          }}
          style={{
            backgroundColor: tokens.surface,
            color: tokens.cellNeutralText
          }}
          className="w-9 h-9 rounded-xl flex items-center justify-center cursor-pointer active:scale-95 transition-transform"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1
          style={{ color: tokens.cellNeutralText }}
          className="text-xl font-bold font-heading"
        >
          Join Room
        </h1>
      </div>

      <div className="mt-8 flex flex-col gap-4">
        <p style={{ color: tokens.textSecondary }} className="text-sm">
          Enter the room code shared by your friend to jump into their multiplayer lobby:
        </p>

        {errorMessage && (
          <div className="p-3 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-center gap-2 text-rose-500 text-xs font-semibold">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span>{errorMessage}</span>
          </div>
        )}

        <form onSubmit={handleJoin} className="flex flex-col gap-4">
          <div>
            <label
              style={{ color: tokens.textMuted }}
              className="text-xs font-semibold block mb-1.5 uppercase tracking-wider"
            >
              Room Code
            </label>
            <input
              type="text"
              maxLength={8}
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              placeholder="e.g. 849201"
              autoFocus
              style={{
                backgroundColor: tokens.surface,
                borderColor: tokens.surfaceBorder,
                color: tokens.cellNeutralText
              }}
              className="w-full px-4 py-3.5 rounded-2xl border text-xl font-mono font-bold tracking-widest text-center focus:outline-none focus:ring-1 focus:ring-purple-500 uppercase transition-colors"
            />
          </div>

          <button
            type="submit"
            disabled={code.trim().length < 4 || isValidating}
            style={{
              backgroundColor: tokens.primaryButtonBg,
              color: tokens.primaryButtonText
            }}
            className="w-full h-12 rounded-xl font-bold text-sm flex items-center justify-center gap-2 transition-all cursor-pointer active:scale-98 shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <span>{isValidating ? 'Checking Room...' : 'Enter Lobby'}</span>
            {!isValidating && <ArrowRight className="w-4 h-4" />}
          </button>
        </form>
      </div>
    </div>
  );
};
