import React, { useState } from 'react';
import { ArrowLeft, Users, KeyRound, AlertCircle, Play } from 'lucide-react';
import { Player } from '../types/models';
import { CloudRegistry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';

interface Props {
  localPlayer: Player;
  onHostRoom: (roomCode: string) => void;
  onJoinRoom: (roomCode: string) => void;
  onBack: () => void;
}

export const OnlineMatchChoiceScreen: React.FC<Props> = ({
  localPlayer,
  onHostRoom,
  onJoinRoom,
  onBack
}) => {
  const [isJoining, setIsJoining] = useState<boolean>(false);
  const [codeInput, setCodeInput] = useState<string>('');
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Generate strictly 6-character room code (e.g. 482915)
  const handleHostClick = async () => {
    soundEffects.playTap();
    setIsLoading(true);
    setErrorMessage(null);

    // Generate 6 digit uppercase code
    const randomCode = Math.floor(100000 + Math.random() * 900000).toString();

    // Register room in KeyValue store AND MQTT retained topic
    const success = await CloudRegistry.createRoom(randomCode, localPlayer, 5);
    setIsLoading(false);

    if (success) {
      onHostRoom(randomCode);
    } else {
      setErrorMessage('Failed to register room in cloud. Please retry.');
    }
  };

  const handleJoinSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    soundEffects.playTap();
    const cleanCode = codeInput.trim().toUpperCase();

    if (cleanCode.length !== 6) {
      setErrorMessage('Room code must be exactly 6 characters.');
      return;
    }

    setIsLoading(true);
    setErrorMessage(null);

    // Validate room with KeyValue and MQTT fallback
    const result = await CloudRegistry.validateAndJoinRoom(cleanCode, localPlayer);
    setIsLoading(false);

    if (result.success) {
      onJoinRoom(cleanCode);
    } else {
      setErrorMessage(result.message || 'Room not found or expired.');
    }
  };

  return (
    <div className="min-h-screen flex flex-col justify-between max-w-md mx-auto p-4 select-none bg-[#FAFAFC] text-slate-800">
      {/* Header */}
      <header className="flex items-center gap-3 py-3">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            onBack();
          }}
          className="w-10 h-10 rounded-full bg-white border border-slate-200 text-slate-600 hover:text-slate-900 hover:bg-slate-50 flex items-center justify-center transition-all cursor-pointer shadow-sm"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div>
          <h1 className="text-xl font-black font-heading tracking-wide text-slate-800">
            Online Match
          </h1>
          <p className="text-xs text-slate-500">Host or join a cross-platform room</p>
        </div>
      </header>

      {/* Main Choice Body */}
      <main className="my-auto space-y-4 py-4">
        {errorMessage && (
          <div className="p-3.5 rounded-2xl bg-rose-50 border border-rose-200 flex items-center gap-2.5 text-rose-600 text-xs font-semibold animate-fade-in">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span>{errorMessage}</span>
          </div>
        )}

        {isLoading ? (
          <div className="py-16 flex flex-col items-center justify-center">
            <div className="w-10 h-10 border-3 border-[#7C3AED] border-t-transparent rounded-full animate-spin mb-3" />
            <p className="text-xs font-bold text-slate-500 uppercase tracking-widest">
              Connecting to Cloud Registry...
            </p>
          </div>
        ) : !isJoining ? (
          <>
            {/* Host Game Card */}
            <button
              type="button"
              onClick={handleHostClick}
              className="w-full p-5 rounded-3xl bg-white hover:bg-purple-50/30 text-slate-800 shadow-sm border border-slate-200 hover:border-purple-300 flex items-center justify-between transition-all active:scale-[0.98] cursor-pointer group"
            >
              <div className="flex items-center gap-4">
                <div className="w-13 h-13 rounded-2xl bg-[#F5EEFF] flex items-center justify-center text-[#7C3AED] shadow-sm">
                  <Users className="w-7 h-7" />
                </div>
                <div className="text-left">
                  <h2 className="text-base font-black tracking-wide text-slate-800">Host Online Room</h2>
                  <p className="text-xs text-slate-500 font-medium mt-0.5">
                    Generate a 6-digit code for your friend
                  </p>
                </div>
              </div>
              <span className="text-xl text-[#7C3AED] font-bold group-hover:translate-x-1 transition-transform">➔</span>
            </button>

            {/* Join Game Card */}
            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                setIsJoining(true);
                setErrorMessage(null);
              }}
              className="w-full p-5 rounded-3xl bg-white hover:bg-blue-50/30 text-slate-800 border border-slate-200 hover:border-blue-300 shadow-sm flex items-center justify-between transition-all active:scale-[0.98] cursor-pointer group"
            >
              <div className="flex items-center gap-4">
                <div className="w-13 h-13 rounded-2xl bg-blue-50 flex items-center justify-center text-blue-600 shadow-sm">
                  <KeyRound className="w-7 h-7" />
                </div>
                <div className="text-left">
                  <h2 className="text-base font-black tracking-wide text-slate-800">Join Online Room</h2>
                  <p className="text-xs text-slate-500 font-medium mt-0.5">
                    Enter 6-digit code created by your friend
                  </p>
                </div>
              </div>
              <span className="text-xl text-blue-600 font-bold group-hover:translate-x-1 transition-transform">➔</span>
            </button>
          </>
        ) : (
          /* Join Room Form */
          <div className="p-6 rounded-3xl bg-white border border-slate-200 shadow-xl animate-fade-in">
            <div className="flex items-center justify-between mb-4">
              <span className="text-xs font-bold uppercase tracking-widest text-[#7C3AED]">
                Enter Room Code
              </span>
              <button
                type="button"
                onClick={() => setIsJoining(false)}
                className="text-xs text-slate-500 hover:text-slate-800 cursor-pointer"
              >
                Cancel
              </button>
            </div>

            <form onSubmit={handleJoinSubmit} className="space-y-4">
              <input
                type="text"
                maxLength={6}
                value={codeInput}
                onChange={e => {
                  setCodeInput(e.target.value.toUpperCase());
                  setErrorMessage(null);
                }}
                placeholder="6-DIGIT CODE"
                autoFocus
                className="w-full px-4 py-4 rounded-2xl bg-[#F8FAFC] border-2 border-slate-300 text-slate-800 font-black text-2xl tracking-[0.3em] text-center uppercase placeholder:text-slate-400 placeholder:tracking-normal placeholder:font-normal placeholder:text-sm focus:outline-none focus:border-[#7C3AED] focus:bg-white transition-colors"
              />

              <button
                type="submit"
                className="w-full py-4 rounded-2xl bg-[#7C3AED] hover:bg-[#6D28D9] text-white font-black text-sm uppercase tracking-wider shadow-md active:scale-95 transition-all flex items-center justify-center gap-2 cursor-pointer"
              >
                <Play className="w-4 h-4 fill-current" />
                <span>Join Room</span>
              </button>
            </form>
          </div>
        )}
      </main>

      <footer className="py-2 text-center text-[11px] text-slate-400">
        Compatible with Android App v1.0.1
      </footer>
    </div>
  );
};
