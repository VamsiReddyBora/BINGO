import React, { useState, useEffect } from 'react';
import { User, AlertCircle, Sparkles, CheckCircle2, ArrowRight, X } from 'lucide-react';
import { Player, CloudUserDataBackup } from '../types/models';
import { CloudRegistry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { PlayerAvatar } from '../components/PlayerAvatar';

interface Props {
  onLoginSuccess: (player: Player) => void;
}

function parseJwt(token: string) {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    return JSON.parse(jsonPayload);
  } catch {
    return null;
  }
}

export const LoginScreen: React.FC<Props> = ({ onLoginSuccess }) => {
  const [displayName, setDisplayName] = useState<string>('Player');
  const [username, setUsername] = useState<string>('');
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [loadingMessage, setLoadingMessage] = useState<string>('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Google Modal State
  const [showGoogleModal, setShowGoogleModal] = useState<boolean>(false);
  const [googleEmail, setGoogleEmail] = useState<string>('');
  const [googleName, setGoogleName] = useState<string>('');
  const [googleUsername, setGoogleUsername] = useState<string>('');
  const [isCheckingCloud, setIsCheckingCloud] = useState<boolean>(false);
  const [cloudFoundProfile, setCloudFoundProfile] = useState<CloudUserDataBackup | null>(null);

  const cleanUsername = username.trim().toLowerCase().replace(/^@/, '');

  // Initialize Google Identity Services if available
  useEffect(() => {
    try {
      const google = (window as any).google;
      if (google && google.accounts && google.accounts.id) {
        google.accounts.id.initialize({
          client_id: (import.meta as any).env?.VITE_GOOGLE_CLIENT_ID || '1083928172917293817-demo.apps.googleusercontent.com',
          callback: handleGoogleCredentialResponse,
          auto_select: false
        });
      }
    } catch {}
  }, []);

  const handleGoogleCredentialResponse = async (response: any) => {
    if (!response || !response.credential) return;
    const payload = parseJwt(response.credential);
    if (!payload) return;

    const gId = payload.sub || `g_${Date.now()}`;
    const email = payload.email || '';
    const name = payload.name || payload.given_name || 'Google Player';
    const pic = payload.picture || null;

    await executeGoogleLogin(gId, email, name, pic);
  };

  // Check cloud backup when email or username is typed in Google Modal
  const checkCloudAccount = async (targetUserOrGid: string) => {
    const clean = targetUserOrGid.trim().toLowerCase().replace(/^@/, '');
    if (!clean || clean.length < 3) return;

    setIsCheckingCloud(true);
    setCloudFoundProfile(null);

    try {
      // 1. Check by googleId or email
      let backup = await CloudRegistry.fetchUserDataBackup(clean);
      if (!backup) {
        // 2. Check by username
        backup = await CloudRegistry.fetchUserBackupByUsername(clean);
      }
      if (!backup) {
        // 3. Check player registry
        const entry = await CloudRegistry.searchPlayerByUsername(clean);
        if (entry) {
          backup = {
            profile: {
              uid: entry.uid,
              username: entry.username,
              displayName: entry.displayName,
              avatarUrl: entry.avatarUrl,
              gamesPlayed: entry.gamesPlayed,
              gamesWon: entry.gamesWon,
              currentStreak: entry.currentStreak,
              level: entry.level,
              xp: 0
            }
          };
        }
      }

      if (backup) {
        setCloudFoundProfile(backup);
        if (!googleName && backup.profile.displayName) {
          setGoogleName(backup.profile.displayName);
        }
      }
    } catch (e) {
      console.warn('Error checking cloud account:', e);
    } finally {
      setIsCheckingCloud(false);
    }
  };

  const executeGoogleLogin = async (
    gId: string,
    email: string,
    name: string,
    avatarUrl: string | null = null,
    customUsername?: string
  ) => {
    setIsLoading(true);
    setLoadingMessage('Syncing Google Profile & Friends with Cloud...');
    soundEffects.playTap();

    try {
      // 1. Try restore from Cloud Backup (gid_ or user_)
      let backup = await CloudRegistry.fetchUserDataBackup(gId);
      const chosenUser = (
        customUsername ||
        backup?.profile.username ||
        email.split('@')[0] ||
        name.toLowerCase().replace(/[^a-z0-9]/g, '_')
      ).toLowerCase().replace(/^@/, '');

      if (!backup && chosenUser) {
        backup = await CloudRegistry.fetchUserBackupByUsername(chosenUser);
      }

      let restoredFriends = await CloudRegistry.fetchCloudFriends(chosenUser);

      const finalPlayer: Player = {
        id: backup?.profile.uid || `google_${gId}`,
        displayName: backup?.profile.displayName || name || 'Google Player',
        username: chosenUser,
        isHost: false,
        avatarUrl: backup?.profile.avatarUrl || avatarUrl || '🧑',
        score: 0,
        completedLinesCount: 0,
        gamesPlayed: backup?.profile.gamesPlayed ?? 0,
        gamesWon: backup?.profile.gamesWon ?? 0,
        currentStreak: backup?.profile.currentStreak ?? 0,
        level: backup?.profile.level ?? 1,
        lastSeenTimestamp: Date.now(),
        lobbyReadyStatus: 'NOT_READY',
        readyVersion: 0,
        email,
        googleId: gId,
        authProvider: 'GOOGLE',
        matchHistory: backup?.matchHistory ?? []
      };

      // 2. Persist back to cloud storage so Android device has latest sync
      await CloudRegistry.saveUserDataBackup(gId, {
        profile: {
          uid: finalPlayer.id,
          username: finalPlayer.username,
          displayName: finalPlayer.displayName,
          email: finalPlayer.email,
          avatarUrl: finalPlayer.avatarUrl,
          gamesPlayed: finalPlayer.gamesPlayed,
          gamesWon: finalPlayer.gamesWon,
          currentStreak: finalPlayer.currentStreak,
          level: finalPlayer.level,
          xp: 0
        },
        matchHistory: finalPlayer.matchHistory
      });

      // 3. Register universally
      await CloudRegistry.claimAndRegisterUser(finalPlayer);

      // 4. Cache friends in localStorage
      if (restoredFriends.length > 0) {
        try {
          localStorage.setItem('bingo_web_friends_v1', JSON.stringify(restoredFriends));
        } catch {}
      }

      setIsLoading(false);
      setShowGoogleModal(false);
      onLoginSuccess(finalPlayer);
    } catch (e: any) {
      console.warn('Google sign-in error:', e);
      setIsLoading(false);
      setErrorMessage(e.message || 'Error syncing Google account.');
    }
  };

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
      readyVersion: 0,
      authProvider: 'GUEST'
    };

    // Register universally in background
    CloudRegistry.claimAndRegisterUser(newPlayer).catch(console.warn);

    setIsLoading(false);
    onLoginSuccess(newPlayer);
  };

  const handleOpenGoogleModal = () => {
    soundEffects.playTap();
    // Try triggering GIS prompt first if supported
    try {
      const google = (window as any).google;
      if (google && google.accounts && google.accounts.id) {
        google.accounts.id.prompt((notification: any) => {
          if (notification.isNotDisplayed() || notification.isSkippedMoment()) {
            setShowGoogleModal(true);
          }
        });
        return;
      }
    } catch {}
    setShowGoogleModal(true);
  };

  return (
    <div className="min-h-[100dvh] w-full flex flex-col justify-center items-center p-3 sm:p-4 max-w-md mx-auto select-none bg-[#FAFAFC] text-slate-800 box-border">
      {/* Aesthetic App Icon & Header matching Android LoginScreen.kt */}
      <div className="flex flex-col items-center mb-5 sm:mb-6">
        <div className="w-16 h-16 sm:w-18 sm:h-18 rounded-[22px] bg-[#F5EEFF] flex items-center justify-center shadow-md border border-purple-200/80 p-2">
          <img src="./icon.jpg" alt="BINGO" className="w-12 h-12 sm:w-14 sm:h-14 rounded-2xl object-cover" />
        </div>

        <h1 className="mt-3.5 text-3xl sm:text-4xl font-black font-heading tracking-[0.2em] text-slate-800">
          B I N G O
        </h1>

        <span className="text-[10px] sm:text-[11px] font-extrabold tracking-[0.2em] text-slate-400 mt-0.5 uppercase">
          M U L T I P L A Y E R
        </span>
      </div>

      {/* Clean Light Auth Container Card */}
      <div className="w-full bg-white border border-slate-200/90 rounded-3xl p-5 sm:p-6 shadow-xl box-border">
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
              onClick={handleOpenGoogleModal}
              className="w-full h-12 rounded-2xl bg-white hover:bg-slate-50 border border-slate-300 text-slate-700 font-semibold text-sm flex items-center justify-center gap-3 transition-all active:scale-[0.98] shadow-sm cursor-pointer"
            >
              <svg className="w-5 h-5 flex-shrink-0" viewBox="0 0 24 24">
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
                className="w-full px-4 py-3 rounded-2xl bg-[#F8FAFC] border border-slate-300 text-slate-800 font-semibold focus:outline-none focus:border-[#7C3AED] focus:bg-white transition-colors box-border"
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
                  className="w-full pl-8 pr-4 py-3 rounded-2xl bg-[#F8FAFC] border border-slate-300 text-slate-800 font-semibold focus:outline-none focus:border-[#7C3AED] focus:bg-white transition-colors box-border"
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

      {/* Google Sign-In & Cloud Sync Modal */}
      {showGoogleModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-4 bg-slate-900/50 backdrop-blur-sm animate-fade-in select-none">
          <div className="w-full max-w-sm sm:max-w-md bg-white border border-slate-200/90 rounded-3xl p-5 sm:p-6 shadow-2xl relative animate-slide-up">
            <button
              type="button"
              onClick={() => setShowGoogleModal(false)}
              className="absolute top-4 right-4 w-8 h-8 rounded-full bg-slate-100 hover:bg-slate-200 text-slate-500 flex items-center justify-center cursor-pointer"
            >
              <X className="w-4 h-4" />
            </button>

            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-2xl bg-purple-50 flex items-center justify-center border border-purple-200">
                <svg className="w-5 h-5" viewBox="0 0 24 24">
                  <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
                  <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
                  <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z" />
                  <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z" />
                </svg>
              </div>
              <div>
                <h3 className="text-base font-bold text-slate-800">Google Account Sign-In</h3>
                <p className="text-xs text-slate-500">Cross-platform profile & friends sync</p>
              </div>
            </div>

            <div className="space-y-3">
              <div>
                <label className="text-xs font-bold text-slate-600 block mb-1">
                  Google Email or Android Player ID
                </label>
                <input
                  type="text"
                  value={googleEmail}
                  onChange={e => {
                    const val = e.target.value;
                    setGoogleEmail(val);
                    checkCloudAccount(val);
                  }}
                  placeholder="e.g. user@gmail.com or username"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-50 border border-slate-200 text-slate-800 text-sm font-semibold focus:outline-none focus:border-purple-500"
                />
              </div>

              <div>
                <label className="text-xs font-bold text-slate-600 block mb-1">
                  Display Name
                </label>
                <input
                  type="text"
                  value={googleName}
                  onChange={e => setGoogleName(e.target.value)}
                  placeholder="Your Name (e.g. Alex)"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-50 border border-slate-200 text-slate-800 text-sm font-semibold focus:outline-none focus:border-purple-500"
                />
              </div>

              {/* Cloud Account Auto-Detection Status */}
              {isCheckingCloud && (
                <div className="p-2.5 rounded-xl bg-purple-50 border border-purple-200 flex items-center gap-2 text-xs text-purple-700">
                  <div className="w-3.5 h-3.5 border-2 border-purple-600 border-t-transparent rounded-full animate-spin flex-shrink-0" />
                  <span>Checking cloud registry for existing Android stats...</span>
                </div>
              )}

              {cloudFoundProfile && (
                <div className="p-3 rounded-2xl bg-emerald-50 border border-emerald-200 animate-fade-in">
                  <div className="flex items-center gap-2 text-emerald-700 text-xs font-bold mb-1">
                    <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
                    <span>Android Account Found in Cloud!</span>
                  </div>
                  <div className="text-[11px] text-emerald-800 space-y-0.5">
                    <p>
                      <strong>@{cloudFoundProfile.profile.username}</strong> ({cloudFoundProfile.profile.displayName})
                    </p>
                    <p className="text-emerald-600">
                      Level {cloudFoundProfile.profile.level} • {cloudFoundProfile.profile.gamesWon} Wins / {cloudFoundProfile.profile.gamesPlayed} Matches • All Friends Synced
                    </p>
                  </div>
                </div>
              )}

              <button
                type="button"
                disabled={!googleEmail.trim()}
                onClick={() => {
                  const gId = googleEmail.replace(/[^a-zA-Z0-9]/g, '_').toLowerCase();
                  executeGoogleLogin(
                    gId,
                    googleEmail,
                    googleName || cloudFoundProfile?.profile.displayName || googleEmail.split('@')[0],
                    cloudFoundProfile?.profile.avatarUrl,
                    cloudFoundProfile?.profile.username || googleUsername || googleEmail.split('@')[0]
                  );
                }}
                className={`w-full py-3 rounded-2xl font-bold text-sm flex items-center justify-center gap-2 shadow-md transition-all cursor-pointer ${
                  googleEmail.trim()
                    ? 'bg-[#7C3AED] hover:bg-[#6D28D9] text-white active:scale-95'
                    : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                }`}
              >
                <span>{cloudFoundProfile ? 'Restore Account & Play' : 'Sign In & Sync'}</span>
                <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
