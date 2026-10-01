import React, { useState } from 'react';
import { User, AlertCircle, Sparkles } from 'lucide-react';
import { Player } from '../types/models';
import { CloudRegistry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';

interface Props {
  onLoginSuccess: (player: Player) => void;
}

export const LoginScreen: React.FC<Props> = ({ onLoginSuccess }) => {
  const [displayName, setDisplayName] = useState<string>('Player');
  const [username, setUsername] = useState<string>('');
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [loadingMessage, setLoadingMessage] = useState<string>('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const cleanUsername = username.trim().toLowerCase().replace(/^@/, '');

  const handleGuestLogin = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    soundEffects.playTap();

    const trimmedName = displayName.trim() || 'Player';
    const chosenUser = cleanUsername || `player_${Math.floor(1000 + Math.random() * 9000)}`;

    if (chosenUser.length < 3) {
      setErrorMessage('Player ID (username) must be at least 3 characters.');
      return;
    }

    if (!/^[a-z0-9_]+$/.test(chosenUser)) {
      setErrorMessage('Username can only contain letters, numbers, and underscores.');
      return;
    }

    setIsLoading(true);
    setLoadingMessage('Setting up your player profile...');
    setErrorMessage(null);

    const uid = `web_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;

    // Quick non-blocking availability check (max 1.5s timeout)
    const isAvail = await Promise.race([
      CloudRegistry.checkUsernameAvailable(chosenUser, uid),
      new Promise<boolean>(res => setTimeout(() => res(true), 1500))
    ]);

    if (!isAvail) {
      setIsLoading(false);
      setErrorMessage(`Username '@${chosenUser}' is already taken. Please choose another.`);
      return;
    }

    const newPlayer: Player = {
      id: uid,
      displayName: trimmedName,
      username: chosenUser,
      isHost: false,
      avatarUrl: '🧑',
      score: 0,
      completedLinesCount: 0,
      gamesPlayed: 0,
      gamesWon: 0,
      currentStreak: 0,
      level: 1,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'NOT_READY',
      readyVersion: 0
    };

    // Register universally in background
    CloudRegistry.claimAndRegisterUser(newPlayer).catch(console.warn);

    setIsLoading(false);
    onLoginSuccess(newPlayer);
  };

  const handleGoogleSignIn = () => {
    soundEffects.playTap();
    const randomSuffix = Math.floor(100 + Math.random() * 900);
    const googleUser = `google_player_${randomSuffix}`;
    const newPlayer: Player = {
      id: `web_google_${Date.now()}`,
      displayName: 'Google Player',
      username: googleUser,
      isHost: false,
      avatarUrl: '🧑',
      score: 0,
      completedLinesCount: 0,
      gamesPlayed: 0,
      gamesWon: 0,
      currentStreak: 0,
      level: 1,
      lastSeenTimestamp: Date.now(),
      lobbyReadyStatus: 'NOT_READY',
      readyVersion: 0
    };

    CloudRegistry.claimAndRegisterUser(newPlayer).catch(console.warn);
    onLoginSuccess(newPlayer);
  };

  return (
    <div className="min-h-[100dvh] flex flex-col justify-center items-center p-4 max-w-md mx-auto select-none bg-[#FAFAFC] text-slate-800">
      {/* Aesthetic App Icon & Header matching Android LoginScreen.kt */}
      <div className="flex flex-col items-center mb-6">
        <div className="w-18 h-18 rounded-[22px] bg-[#F5EEFF] flex items-center justify-center shadow-md border border-purple-200/80 p-2">
          <img src="./icon.jpg" alt="BINGO" className="w-14 h-14 rounded-2xl object-cover" />
        </div>

        <h1 className="mt-4 text-3xl sm:text-4xl font-black font-heading tracking-[0.2em] text-slate-800">
          B I N G O
        </h1>

        <span className="text-[11px] font-extrabold tracking-[0.2em] text-slate-400 mt-1 uppercase">
          M U L T I P L A Y E R
        </span>
      </div>

      {/* Clean Light Auth Container Card */}
      <div className="w-full bg-white border border-slate-200/90 rounded-3xl p-6 shadow-xl">
        {isLoading ? (
          <div className="py-12 flex flex-col items-center justify-center text-center">
            <div className="w-10 h-10 border-3 border-[#7C3AED] border-t-transparent rounded-full animate-spin mb-4" />
            <p className="text-sm font-semibold text-slate-600">{loadingMessage}</p>
          </div>
        ) : (
          <form onSubmit={handleGuestLogin} className="space-y-4">
            {errorMessage && (
              <div className="p-3 rounded-xl bg-rose-50 border border-rose-200 flex items-center gap-2 text-rose-600 text-xs font-semibold">
                <AlertCircle className="w-4 h-4 flex-shrink-0" />
                <span>{errorMessage}</span>
              </div>
            )}

            {/* Google Sign In Button */}
            <button
              type="button"
              onClick={handleGoogleSignIn}
              className="w-full h-12 rounded-2xl bg-white hover:bg-slate-50 border border-slate-300 text-slate-700 font-semibold text-sm flex items-center justify-center gap-3 transition-all active:scale-[0.98] shadow-sm cursor-pointer"
            >
              <svg className="w-5 h-5" viewBox="0 0 24 24">
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
                <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z" />
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z" />
              </svg>
              <span>Continue with Google</span>
            </button>

            {/* Divider with OR */}
            <div className="flex items-center gap-3 my-3">
              <div className="flex-1 h-[1px] bg-slate-200" />
              <span className="text-[11px] font-bold tracking-widest text-slate-400 uppercase">OR</span>
              <div className="flex-1 h-[1px] bg-slate-200" />
            </div>

            {/* Display Name Input */}
            <div>
              <label className="text-xs font-bold text-slate-600 block mb-1">
                Player Nickname
              </label>
              <input
                type="text"
                maxLength={16}
                value={displayName}
                onChange={e => setDisplayName(e.target.value)}
                placeholder="e.g. Player One"
                className="w-full px-4 py-3 rounded-2xl bg-[#F8FAFC] border border-slate-300 text-slate-800 font-semibold focus:outline-none focus:border-[#7C3AED] focus:bg-white transition-colors"
                required
              />
            </div>

            {/* Unique Username Input (@username) */}
            <div>
              <label className="text-xs font-bold text-slate-600 flex items-center justify-between mb-1">
                <span>Player ID / Username</span>
                <span className="text-[10px] text-purple-600 font-medium">Searched by friends</span>
              </label>
              <div className="relative flex items-center">
                <span className="absolute left-4 font-bold text-purple-600 select-none">@</span>
                <input
                  type="text"
                  maxLength={18}
                  value={username}
                  onChange={e => {
                    setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, ''));
                    setErrorMessage(null);
                  }}
                  placeholder="e.g. rahul_gamer"
                  className="w-full pl-8 pr-4 py-3 rounded-2xl bg-[#F8FAFC] border border-slate-300 text-slate-800 font-semibold focus:outline-none focus:border-[#7C3AED] focus:bg-white transition-colors"
                  required
                />
              </div>
              <p className="mt-1 text-[11px] text-slate-500">
                Your friend will search for you on Android using this ID.
              </p>
            </div>

            {/* Play as Guest Button */}
            <button
              type="submit"
              className="w-full h-12 rounded-2xl bg-[#7C3AED] hover:bg-[#6D28D9] active:scale-[0.98] text-white font-bold text-sm flex items-center justify-center gap-2 transition-all shadow-md cursor-pointer mt-2"
            >
              <User className="w-4 h-4 text-white" />
              <span>Play as Guest</span>
            </button>

            <p className="text-[11px] text-center text-slate-400 pt-1">
              Instant play • No account required • Universally visible
            </p>
          </form>
        )}
      </div>
    </div>
  );
};
