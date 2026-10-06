import React, { useState, useEffect, useRef } from 'react';
import { User, Info, AlertCircle, ArrowRight, ArrowLeft, X, Plus, ChevronRight, Check, Trash2, CheckCircle2, Copy, ShieldCheck } from 'lucide-react';
import { Player, CloudUserDataBackup } from '../types/models';
import { CloudRegistry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { useTheme } from '../theme/theme';

interface Props {
  onLoginSuccess: (player: Player) => void;
  onOpenDeveloperNote?: () => void;
}

interface SavedGoogleAccount {
  googleId: string;
  email: string;
  displayName: string;
  username: string;
  avatarUrl?: string | null;
  level?: number;
  lastLogin?: number;
}

const STORAGE_KEY_SAVED_GOOGLE_ACCOUNTS = 'bingo_saved_google_accounts_v2';

function parseJwt(token: string) {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    return JSON.parse(jsonPayload);
  } catch {
    return null;
  }
}

// Retrieve ONLY authentic accounts previously signed into on THIS specific browser/phone
function getSavedGoogleAccounts(): SavedGoogleAccount[] {
  try {
    localStorage.removeItem('bingo_saved_google_accounts_v1');

    const raw = localStorage.getItem(STORAGE_KEY_SAVED_GOOGLE_ACCOUNTS);
    if (raw) {
      const list = JSON.parse(raw);
      if (Array.isArray(list)) {
        const clean = list.filter(
          (a) =>
            a &&
            typeof a.email === 'string' &&
            a.email.toLowerCase() !== 'sherlock7528@gmail.com'
        );
        if (clean.length !== list.length) {
          localStorage.setItem(STORAGE_KEY_SAVED_GOOGLE_ACCOUNTS, JSON.stringify(clean));
        }
        return clean;
      }
    }
  } catch {}
  return [];
}

function saveGoogleAccountToStorage(acc: SavedGoogleAccount) {
  try {
    if (acc.email.toLowerCase() === 'sherlock7528@gmail.com') return;
    const existing = getSavedGoogleAccounts().filter(
      (a) => a.email.toLowerCase() !== acc.email.toLowerCase()
    );
    existing.unshift(acc);
    localStorage.setItem(STORAGE_KEY_SAVED_GOOGLE_ACCOUNTS, JSON.stringify(existing.slice(0, 5)));
  } catch {}
}

function removeSavedGoogleAccountFromStorage(email: string): SavedGoogleAccount[] {
  try {
    const clean = getSavedGoogleAccounts().filter(
      (a) => a.email.toLowerCase() !== email.toLowerCase()
    );
    localStorage.setItem(STORAGE_KEY_SAVED_GOOGLE_ACCOUNTS, JSON.stringify(clean));
    return clean;
  } catch {}
  return [];
}

export const LoginScreen: React.FC<Props> = ({
  onLoginSuccess,
  onOpenDeveloperNote
}) => {
  const { tokens, isDark } = useTheme();

  const [guestNickname, setGuestNickname] = useState('Player');
  const [isLoading, setIsLoading] = useState(false);
  const [loadingMessage, setLoadingMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // ── Authentic Google Sign-In Sheet / Modal State ──
  const [showGoogleModal, setShowGoogleModal] = useState(false);
  const [showGoogleSetupModal, setShowGoogleSetupModal] = useState(false);
  const [savedAccounts, setSavedAccounts] = useState<SavedGoogleAccount[]>([]);
  const [selectedAccountEmail, setSelectedAccountEmail] = useState<string | null>(null);

  const DEFAULT_GOOGLE_CLIENT_ID = '957593338442-ol5p4icr1a8e15m8lb1ln5mab5n1r5lp.apps.googleusercontent.com';

  // Google OAuth 2.0 Client ID for Google Identity Services
  const [googleClientId, setGoogleClientId] = useState<string>(() => {
    return (
      (import.meta as any).env?.VITE_GOOGLE_CLIENT_ID ||
      localStorage.getItem('bingo_google_client_id') ||
      DEFAULT_GOOGLE_CLIENT_ID
    );
  });
  const [clientIdInput, setClientIdInput] = useState('');
  const [copySuccess, setCopySuccess] = useState(false);
  const googleBtnContainerRef = useRef<HTMLDivElement>(null);

  // ── First-time username prompt (matching Android LoginScreen) ──
  const [showFirstTimeNameDialog, setShowFirstTimeNameDialog] = useState(false);
  const [pendingGoogleData, setPendingGoogleData] = useState<{ id: string; email: string; name: string; avatarUrl?: string | null } | null>(null);
  const [newPlayerIdInput, setNewPlayerIdInput] = useState('');
  const [usernameError, setUsernameError] = useState<string | null>(null);
  const [isCheckingUsername, setIsCheckingUsername] = useState(false);

  // ── Secondary Manual Player ID Recovery Modal ──
  const [showManualIdModal, setShowManualIdModal] = useState(false);
  const [manualIdInput, setManualIdInput] = useState('');

  useEffect(() => {
    try {
      localStorage.removeItem('bingo_saved_google_accounts_v1');
    } catch {}
    setSavedAccounts(getSavedGoogleAccounts());

    // Check if returning from Google OAuth redirect with #access_token=...
    if (window.location.hash.includes('access_token')) {
      const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ''));
      const token = hashParams.get('access_token');
      if (token) {
        setIsLoading(true);
        setLoadingMessage('Completing Google Sign-In...');
        window.history.replaceState(null, '', window.location.pathname);
        fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
          headers: { Authorization: `Bearer ${token}` }
        })
          .then((res) => res.json())
          .then((data) => {
            if (data && data.email) {
              const email = data.email.toLowerCase();
              const name = data.name || email.split('@')[0];
              const sub = data.sub || `google_${Date.now()}`;
              const pic = data.picture;
              executeGoogleSignInProcess(email, sub, name, pic);
            }
          })
          .catch(() => {
            setIsLoading(false);
          });
      }
    }
  }, []);

  // ── Handle GIS Credential Response (Google Identity Services) ──
  const handleGisCredentialResponse = async (response: any) => {
    if (!response || !response.credential) return;
    try {
      setIsLoading(true);
      setLoadingMessage('Signing in with Google...');
      const payload = parseJwt(response.credential);
      if (payload && payload.email) {
        const email = payload.email.toLowerCase();
        const name = payload.name || email.split('@')[0];
        const sub = payload.sub || `google_${Date.now()}`;
        const pic = payload.picture;
        await executeGoogleSignInProcess(email, sub, name, pic);
      }
    } catch {
      setIsLoading(false);
      setErrorMessage('Google Sign-In failed. Please try again.');
    }
  };

  // ── Initialize Google Identity Services (GIS) & Render Official Button ──
  useEffect(() => {
    const initGis = () => {
      const g = (window as any).google;
      const cid = googleClientId || DEFAULT_GOOGLE_CLIENT_ID;
      if (g?.accounts?.id && cid) {
        try {
          g.accounts.id.initialize({
            client_id: cid,
            callback: handleGisCredentialResponse,
            auto_select: false,
            cancel_on_tap_outside: true,
            context: 'signin'
          });

          if (googleBtnContainerRef.current) {
            googleBtnContainerRef.current.innerHTML = '';
            g.accounts.id.renderButton(googleBtnContainerRef.current, {
              type: 'standard',
              theme: isDark ? 'filled_black' : 'outline',
              size: 'large',
              shape: 'pill',
              text: 'continue_with',
              logo_alignment: 'left',
              width: 280
            });
          }
        } catch (e) {
          console.warn('GIS init error:', e);
        }
      }
    };

    initGis();
    const timer = setTimeout(initGis, 800);
    return () => clearTimeout(timer);
  }, [googleClientId, isDark, showGoogleModal]);

  // ── Universal Google Authentication Trigger ──
  // Works across Chrome, Brave, Samsung Internet, Safari, etc.
  const triggerGoogleAuth = () => {
    soundEffects.playTap();
    setErrorMessage(null);

    const g = (window as any).google;
    const cid = googleClientId || DEFAULT_GOOGLE_CLIENT_ID;

    // Method 1: Google OAuth 2.0 Token Client (Opens Google account chooser popup in all browsers)
    if (g?.accounts?.oauth2) {
      try {
        const tokenClient = g.accounts.oauth2.initTokenClient({
          client_id: cid,
          scope: 'email profile openid',
          callback: async (tokenResponse: any) => {
            if (tokenResponse?.error) {
              console.warn('Google OAuth response error:', tokenResponse.error);
              setIsLoading(false);
              return;
            }
            if (tokenResponse?.access_token) {
              try {
                setIsLoading(true);
                setLoadingMessage('Verifying Google account...');
                const res = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
                  headers: { Authorization: `Bearer ${tokenResponse.access_token}` }
                });
                if (res.ok) {
                  const data = await res.json();
                  const email = data.email.toLowerCase();
                  const name = data.name || email.split('@')[0];
                  const sub = data.sub || `google_${Date.now()}`;
                  const pic = data.picture;
                  await executeGoogleSignInProcess(email, sub, name, pic);
                } else {
                  throw new Error('Userinfo fetch failed');
                }
              } catch {
                setIsLoading(false);
                setErrorMessage('Failed to fetch Google profile. Please try again.');
              }
            }
          }
        });

        tokenClient.requestAccessToken({ prompt: 'select_account' });
        return;
      } catch (err) {
        console.warn('OAuth token client error:', err);
      }
    }

    // Method 2: Google Identity Services One Tap prompt
    if (g?.accounts?.id) {
      try {
        g.accounts.id.prompt((notification: any) => {
          if (notification.isNotDisplayed() || notification.isSkippedMoment()) {
            console.log('GIS prompt not displayed:', notification.getNotDisplayedReason?.());
            setShowGoogleModal(true);
          }
        });
        return;
      } catch (e) {
        console.warn('GIS prompt error:', e);
      }
    }

    // Method 3: Direct OAuth Redirect to accounts.google.com
    const origin = window.location.origin;
    const redirectUri = origin + window.location.pathname;
    const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?client_id=${cid}&redirect_uri=${encodeURIComponent(redirectUri)}&response_type=token&scope=openid%20email%20profile&prompt=select_account`;
    window.location.href = authUrl;
  };

  // ── Open Google Sign-In Sheet / Prompt native phone chooser ──
  const handleOpenGoogleSignIn = () => {
    soundEffects.playTap();
    setErrorMessage(null);

    const accounts = getSavedGoogleAccounts();
    setSavedAccounts(accounts);

    // If user has saved authentic account(s) on this phone, show 1-tap chooser
    if (accounts.length > 0) {
      setShowGoogleModal(true);
      return;
    }

    // Launch Google's authentic account picker directly!
    triggerGoogleAuth();
  };

  // ── Handle Triggering Google's Native Account Chooser on Phone ──
  const handleTriggerGooglePrompt = () => {
    soundEffects.playTap();
    triggerGoogleAuth();
  };

  // ── Save Developer / Admin Web Client ID ──
  const handleSaveCustomClientId = (e: React.FormEvent) => {
    e.preventDefault();
    const clean = clientIdInput.trim();
    if (!clean) return;
    try {
      localStorage.setItem('bingo_google_client_id', clean);
      setGoogleClientId(clean);
      setShowGoogleSetupModal(false);
      setErrorMessage(null);
      setTimeout(() => {
        handleOpenGoogleSignIn();
      }, 150);
    } catch (err) {
      console.warn('Failed to save client ID:', err);
    }
  };

  // ── Universal Google Sign-In Execution ──
  const executeGoogleSignInProcess = async (
    email: string,
    googleId: string,
    displayName: string,
    picture?: string | null
  ) => {
    setIsLoading(true);
    setLoadingMessage(`Restoring cloud account ${email}...`);

    try {
      const cleanUser = email.split('@')[0].replace(/[^a-z0-9_]/g, '');
      let backup = await CloudRegistry.fetchUserDataBackup(googleId);
      if (!backup) backup = await CloudRegistry.fetchUserDataBackup(email);
      if (!backup) backup = await CloudRegistry.fetchUserBackupByUsername(cleanUser);
      if (!backup) {
        const reg = await CloudRegistry.searchPlayerByUsername(cleanUser);
        if (reg) {
          backup = {
            profile: {
              uid: reg.uid,
              username: reg.username,
              displayName: reg.displayName,
              avatarUrl: reg.avatarUrl,
              gamesPlayed: reg.gamesPlayed,
              gamesWon: reg.gamesWon,
              currentStreak: reg.currentStreak,
              level: reg.level,
              xp: 0,
              email: email
            }
          };
        }
      }

      if (backup && backup.profile) {
        let avatar = picture || backup.profile.avatarUrl;
        if (backup.profile.avatarBase64) {
          avatar = backup.profile.avatarBase64.startsWith('data:')
            ? backup.profile.avatarBase64
            : `data:image/jpeg;base64,${backup.profile.avatarBase64}`;
        }

        const player: Player = {
          id: backup.profile.uid || `google_${googleId}`,
          displayName: backup.profile.displayName || displayName,
          username: backup.profile.username || cleanUser,
          isHost: false,
          avatarUrl: avatar || null,
          score: 0,
          completedLinesCount: 0,
          gamesPlayed: backup.profile.gamesPlayed || 0,
          gamesWon: backup.profile.gamesWon || 0,
          currentStreak: backup.profile.currentStreak || 0,
          level: backup.profile.level || 1,
          lastSeenTimestamp: Date.now(),
          lobbyReadyStatus: 'NOT_READY',
          readyVersion: 0,
          email: email,
          googleId: googleId,
          authProvider: 'GOOGLE',
          matchHistory: backup.matchHistory || []
        };

        saveGoogleAccountToStorage({
          googleId: googleId,
          email: email,
          displayName: player.displayName,
          username: player.username,
          avatarUrl: player.avatarUrl,
          level: player.level,
          lastLogin: Date.now()
        });

        // Authoritatively claim and register user in cloud registry, user_directory, and ExtendsClass
        await CloudRegistry.claimAndRegisterUser(player);

        setShowGoogleModal(false);
        setIsLoading(false);
        onLoginSuccess(player);
        return;
      }

      // First time user: prompt for unique nickname (matches Android LoginScreen)
      setPendingGoogleData({
        id: `google_${googleId}`,
        email: email,
        name: displayName
      });
      setNewPlayerIdInput(cleanUser);
      setShowGoogleModal(false);
      setShowFirstTimeNameDialog(true);
      setIsLoading(false);
    } catch {
      setIsLoading(false);
      setErrorMessage('Failed to connect to cloud service. Check connection.');
    }
  };

  // ── Handle Selecting a Previously Saved Google Account from Chooser ──
  const handleSelectGoogleAccount = async (account: SavedGoogleAccount) => {
    soundEffects.playTap();
    setSelectedAccountEmail(account.email);
    await executeGoogleSignInProcess(
      account.email,
      account.googleId,
      account.displayName,
      account.avatarUrl
    );
  };

  // ── Handle Removing a Saved Account from Chooser ──
  const handleRemoveAccount = (e: React.MouseEvent, email: string) => {
    e.stopPropagation();
    soundEffects.playTap();
    const updated = removeSavedGoogleAccountFromStorage(email);
    setSavedAccounts(updated);
    if (updated.length === 0) {
      setShowGoogleModal(false);
    }
  };

  // ── Handle Confirm First-Time Nickname (Matches Android LoginScreen) ──
  const handleConfirmFirstTimeNickname = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = newPlayerIdInput.trim().toLowerCase().replace(/^@/, '');
    if (clean.length < 3) {
      setUsernameError('Username must be at least 3 characters.');
      return;
    }

    setIsCheckingUsername(true);
    setUsernameError(null);

    try {
      const gUid = pendingGoogleData?.id || `google_${Date.now()}`;
      const isAvailable = await CloudRegistry.checkUsernameAvailable(clean, gUid);
      if (!isAvailable) {
        setUsernameError(`Username '@${clean}' is already taken. Please choose another.`);
        setIsCheckingUsername(false);
        return;
      }

      const gData = pendingGoogleData || {
        id: `google_${Date.now()}`,
        email: `${clean}@gmail.com`,
        name: clean
      };

      const newPlayer: Player = {
        id: gData.id,
        displayName: gData.name || clean,
        username: clean,
        isHost: false,
        avatarUrl: null,
        score: 0,
        completedLinesCount: 0,
        gamesPlayed: 0,
        gamesWon: 0,
        currentStreak: 0,
        level: 1,
        lastSeenTimestamp: Date.now(),
        lobbyReadyStatus: 'NOT_READY',
        readyVersion: 0,
        email: gData.email,
        authProvider: 'GOOGLE'
      };

      await CloudRegistry.claimAndRegisterUser(newPlayer);

      saveGoogleAccountToStorage({
        googleId: gData.id.replace(/^google_/, ''),
        email: gData.email,
        displayName: newPlayer.displayName,
        username: newPlayer.username,
        level: 1,
        lastLogin: Date.now()
      });

      setIsCheckingUsername(false);
      setShowFirstTimeNameDialog(false);
      onLoginSuccess(newPlayer);
    } catch {
      setIsCheckingUsername(false);
      setUsernameError('Network error checking username.');
    }
  };

  // ── Handle Guest Login ──
  const handleGuestLogin = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    soundEffects.playTap();

    const trimmed = guestNickname.trim() || 'Player';
    const randomUser = `player_${Math.floor(1000 + Math.random() * 9000)}`;

    setIsLoading(true);
    setLoadingMessage('Entering as Guest...');
    setErrorMessage(null);

    const uid = `guest_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`;

    const guestPlayer: Player = {
      id: uid,
      displayName: trimmed,
      username: randomUser,
      isHost: false,
      avatarUrl: null,
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

    // Register guest player in cloud user directory and KeyValue
    await CloudRegistry.claimAndRegisterUser(guestPlayer);
    setIsLoading(false);
    onLoginSuccess(guestPlayer);
  };

  // ── Handle Manual Player ID Recovery (Subtle secondary fallback) ──
  const handleManualIdRestore = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = manualIdInput.trim().toLowerCase().replace(/^@/, '');
    if (!clean || clean.length < 3) {
      setErrorMessage('Please enter a valid username or Player ID.');
      return;
    }

    setIsLoading(true);
    setLoadingMessage(`Restoring account @${clean}...`);
    setShowManualIdModal(false);

    try {
      let backup = await CloudRegistry.fetchUserBackupByUsername(clean);
      if (!backup) backup = await CloudRegistry.fetchUserDataBackup(clean);
      if (!backup) {
        const searchResult = await CloudRegistry.searchPlayerByUsername(clean);
        if (searchResult) {
          backup = {
            profile: {
              uid: searchResult.uid,
              username: searchResult.username,
              displayName: searchResult.displayName,
              avatarUrl: searchResult.avatarUrl,
              gamesPlayed: searchResult.gamesPlayed,
              gamesWon: searchResult.gamesWon,
              currentStreak: searchResult.currentStreak,
              level: searchResult.level,
              xp: 0
            }
          };
        }
      }

      if (backup && backup.profile) {
        let avatar = backup.profile.avatarUrl;
        if (backup.profile.avatarBase64) {
          avatar = backup.profile.avatarBase64.startsWith('data:')
            ? backup.profile.avatarBase64
            : `data:image/jpeg;base64,${backup.profile.avatarBase64}`;
        }

        const restoredPlayer: Player = {
          id: backup.profile.uid,
          displayName: backup.profile.displayName,
          username: backup.profile.username,
          isHost: false,
          avatarUrl: avatar || null,
          score: 0,
          completedLinesCount: 0,
          gamesPlayed: backup.profile.gamesPlayed || 0,
          gamesWon: backup.profile.gamesWon || 0,
          currentStreak: backup.profile.currentStreak || 0,
          level: backup.profile.level || 1,
          lastSeenTimestamp: Date.now(),
          lobbyReadyStatus: 'NOT_READY',
          readyVersion: 0,
          email: backup.profile.email,
          authProvider: 'GOOGLE'
        };

        // Ensure claimed and registered in cloud user directory and KeyValue
        await CloudRegistry.claimAndRegisterUser(restoredPlayer);

        setIsLoading(false);
        onLoginSuccess(restoredPlayer);
        return;
      }

      setIsLoading(false);
      setErrorMessage(`No account found for '@${clean}'.`);
    } catch {
      setIsLoading(false);
      setErrorMessage('Failed to connect to cloud service.');
    }
  };

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="min-h-[100dvh] w-full flex flex-col justify-between items-center p-5 max-w-md mx-auto select-none transition-colors duration-300"
    >
      <div className="w-full flex-1 flex flex-col justify-center items-center">
        {/* ── Royal Bingo Crown Logo Emblem ── */}
        <div className="flex flex-col items-center mb-6">
          <img
            src="./logo.png"
            alt="Royal Bingo Logo"
            className="w-24 h-24 object-contain drop-shadow-md select-none pointer-events-none"
          />

          <h1
            style={{ color: tokens.cellNeutralText }}
            className="mt-3.5 text-3xl font-black font-heading tracking-[0.25em] leading-none"
          >
            B I N G O
          </h1>

          <span
            style={{ color: tokens.textMuted }}
            className="text-[11px] font-bold tracking-[0.25em] uppercase mt-1 opacity-70"
          >
            M U L T I P L A Y E R
          </span>
        </div>

        {/* ── Clean Minimalist Auth Container ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-3xl p-6 border shadow-sm transition-colors duration-300"
        >
          {isLoading ? (
            <div className="py-10 flex flex-col items-center justify-center text-center">
              <div
                style={{ borderColor: tokens.accentBrand, borderTopColor: 'transparent' }}
                className="w-9 h-9 border-3 rounded-full animate-spin mb-4"
              />
              <p style={{ color: tokens.cellNeutralText }} className="text-sm font-semibold">
                {loadingMessage}
              </p>
            </div>
          ) : (
            <div className="flex flex-col gap-4">
              {errorMessage && (
                <div className="p-3 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-center gap-2 text-rose-500 text-xs font-semibold">
                  <AlertCircle className="w-4 h-4 flex-shrink-0" />
                  <span>{errorMessage}</span>
                </div>
              )}

              {/* 1. Continue with Google Button */}
              <button
                type="button"
                onClick={handleOpenGoogleSignIn}
                className="w-full h-12 rounded-2xl bg-white hover:bg-slate-50 border border-slate-300 text-slate-800 font-semibold text-sm flex items-center justify-center gap-3 transition-all active:scale-[0.98] shadow-sm cursor-pointer"
              >
                {/* Official Google G Logo SVG */}
                <svg className="w-5 h-5 flex-shrink-0" viewBox="0 0 512 512">
                  <path
                    fill="#4285F4"
                    d="M482.6,261.4c0,-16.7 -1.5,-32.8 -4.3,-48.3H256v91.3h127c-5.5,29.5 -22.1,54.5 -47.1,71.2v59.2h76.3c44.6,-41.1 70.4,-101.6 70.4,-173.5z"
                  />
                  <path
                    fill="#34A853"
                    d="M256,492c63.7,0 117.1,-21.1 156.2,-57.2l-76.3,-59.2c-21.1,14.2 -48.2,22.5 -79.9,22.5 -61.5,0 -113.5,-41.5 -132.1,-97.3H45.1v61.2c38.8,77.1 118.6,130 210.9,130z"
                  />
                  <path
                    fill="#FBBC05"
                    d="M123.9,300.8c-4.7,-14.2 -7.4,-29.3 -7.4,-44.8s2.7,-30.7 7.4,-44.8V150H45.1C29.1,181.9 20,217.9 20,256c0,38.1 9.1,74.1 25.1,106l78.8,-61.2z"
                  />
                  <path
                    fill="#EA4335"
                    d="M256,113.9c34.7,0 65.8,11.9 90.2,35.3l67.7,-67.7C373,43.4 319.6,20 256,20c-92.3,0 -172.1,52.9 -210.9,130l78.8,61.2c18.6,-55.8 70.6,-97.3 132.1,-97.3z"
                  />
                </svg>
                <span>Continue with Google</span>
              </button>

              {/* OR Divider */}
              <div className="flex items-center gap-3 my-0.5">
                <div style={{ backgroundColor: tokens.surfaceBorder }} className="flex-1 h-[1px]" />
                <span
                  style={{ color: tokens.textMuted }}
                  className="text-[11px] font-bold tracking-widest uppercase opacity-60"
                >
                  OR
                </span>
                <div style={{ backgroundColor: tokens.surfaceBorder }} className="flex-1 h-[1px]" />
              </div>

              {/* 2. Guest Nickname Input */}
              <form onSubmit={handleGuestLogin} className="flex flex-col gap-3">
                <div>
                  <label
                    style={{ color: tokens.textMuted }}
                    className="text-xs font-semibold block mb-1.5"
                  >
                    Player Nickname
                  </label>
                  <input
                    type="text"
                    maxLength={16}
                    value={guestNickname}
                    onChange={(e) => setGuestNickname(e.target.value)}
                    placeholder="e.g. Player One"
                    style={{
                      backgroundColor: tokens.backgroundSecondary,
                      borderColor: tokens.surfaceBorder,
                      color: tokens.cellNeutralText
                    }}
                    className="w-full px-4 py-3 rounded-2xl border text-sm font-semibold focus:outline-none focus:ring-1 focus:ring-purple-500 transition-colors"
                  />
                </div>

                {/* 3. Play as Guest Button */}
                <button
                  type="submit"
                  style={{
                    backgroundColor: isDark ? '#2E1065' : '#F5EEFF',
                    color: isDark ? '#E9D5FF' : '#6B21A8'
                  }}
                  className="w-full h-12 rounded-2xl font-bold text-sm flex items-center justify-center gap-2.5 transition-all active:scale-[0.98] cursor-pointer"
                >
                  <User className="w-5 h-5" />
                  <span>Play as Guest</span>
                </button>

                <p
                  style={{ color: tokens.textMuted }}
                  className="text-[11px] text-center opacity-70 mt-0.5"
                >
                  Instant play • No account required
                </p>
              </form>
            </div>
          )}
        </div>

        {/* ── Secondary Link: Manual ID Link ── */}
        <div className="mt-4 text-center">
          <button
            type="button"
            onClick={() => setShowManualIdModal(true)}
            style={{ color: tokens.textMuted }}
            className="text-[11px] opacity-70 hover:opacity-100 transition-opacity underline cursor-pointer"
          >
            Having trouble? Link account with Player ID
          </button>
        </div>
      </div>

      {/* ── Subtle Bottom Action: Google Setup & Developer Note ── */}
      <div className="pt-4 pb-2 flex items-center justify-center gap-3">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setShowGoogleSetupModal(true);
          }}
          style={{ color: tokens.textMuted }}
          className="flex items-center gap-1.5 text-xs font-medium opacity-70 hover:opacity-100 transition-opacity cursor-pointer"
        >
          <Info className="w-3.5 h-3.5" />
          <span>Google Sign-In Web Setup</span>
        </button>

        <span style={{ color: tokens.textMuted }} className="opacity-30">•</span>

        <button
          type="button"
          onClick={onOpenDeveloperNote}
          style={{ color: tokens.textMuted }}
          className="flex items-center gap-1.5 text-xs font-medium opacity-70 hover:opacity-100 transition-opacity cursor-pointer"
        >
          <span>developer note ☕</span>
        </button>
      </div>

      {/* ───────────────────────────────────────────────────────────── */}
      {/* ── AUTHENTIC GOOGLE SIGN-IN BOTTOM-SHEET / MODAL ── */}
      {/* ───────────────────────────────────────────────────────────── */}
      {showGoogleModal && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/60 backdrop-blur-xs p-0 sm:p-4 animate-in fade-in duration-200">
          <div
            className={`w-full max-w-sm rounded-t-3xl sm:rounded-3xl border shadow-2xl transition-all overflow-hidden ${
              isDark ? 'bg-[#202124] border-[#3c4043] text-[#e8eaed]' : 'bg-white border-[#dadce0] text-[#202124]'
            }`}
          >
            {/* Top Drag Handle (Mobile bottom sheet feel) */}
            <div className="w-10 h-1 bg-slate-300 dark:bg-zinc-600 rounded-full mx-auto mt-2.5 sm:hidden" />

            <div className="p-5 sm:p-6">
              {/* Google Brand Header */}
              <div className="flex items-start justify-between mb-4">
                <div className="flex items-center gap-3">
                  <svg className="w-6 h-6 flex-shrink-0" viewBox="0 0 512 512">
                    <path
                      fill="#4285F4"
                      d="M482.6,261.4c0,-16.7 -1.5,-32.8 -4.3,-48.3H256v91.3h127c-5.5,29.5 -22.1,54.5 -47.1,71.2v59.2h76.3c44.6,-41.1 70.4,-101.6 70.4,-173.5z"
                    />
                    <path
                      fill="#34A853"
                      d="M256,492c63.7,0 117.1,-21.1 156.2,-57.2l-76.3,-59.2c-21.1,14.2 -48.2,22.5 -79.9,22.5 -61.5,0 -113.5,-41.5 -132.1,-97.3H45.1v61.2c38.8,77.1 118.6,130 210.9,130z"
                    />
                    <path
                      fill="#FBBC05"
                      d="M123.9,300.8c-4.7,-14.2 -7.4,-29.3 -7.4,-44.8s2.7,-30.7 7.4,-44.8V150H45.1C29.1,181.9 20,217.9 20,256c0,38.1 9.1,74.1 25.1,106l78.8,-61.2z"
                    />
                    <path
                      fill="#EA4335"
                      d="M256,113.9c34.7,0 65.8,11.9 90.2,35.3l67.7,-67.7C373,43.4 319.6,20 256,20c-92.3,0 -172.1,52.9 -210.9,130l78.8,61.2c18.6,-55.8 70.6,-97.3 132.1,-97.3z"
                    />
                  </svg>
                  <div>
                    <h2 className="text-base font-semibold leading-tight">
                      {savedAccounts.length > 0 ? 'Choose an account' : 'Sign in with Google'}
                    </h2>
                    <p className="text-xs opacity-70">to continue to Bingo</p>
                  </div>
                </div>

                <button
                  type="button"
                  onClick={() => setShowGoogleModal(false)}
                  className="p-1.5 rounded-full hover:bg-black/10 dark:hover:bg-white/10 transition-colors cursor-pointer"
                >
                  <X className="w-4 h-4 opacity-70" />
                </button>
              </div>

              <div className="h-[1px] bg-slate-200 dark:bg-[#3c4043] my-3" />

              {/* View 1: Previously Saved Authentic Accounts on THIS Device */}
              {savedAccounts.length > 0 ? (
                <div className="flex flex-col divide-y divide-slate-100 dark:divide-[#3c4043]/50">
                  {savedAccounts.map((account) => {
                    const isSigningInThis = selectedAccountEmail === account.email && isLoading;
                    const initial = (account.displayName || account.username || 'G').charAt(0).toUpperCase();

                    return (
                      <div
                        key={account.email}
                        className="w-full py-2.5 px-2 flex items-center justify-between gap-3 text-left hover:bg-slate-50 dark:hover:bg-white/5 rounded-2xl transition-all cursor-pointer group"
                        onClick={() => handleSelectGoogleAccount(account)}
                      >
                        <div className="flex items-center gap-3 min-w-0 flex-1">
                          {account.avatarUrl && account.avatarUrl.startsWith('data:') ? (
                            <img
                              src={account.avatarUrl}
                              alt={account.displayName}
                              className="w-10 h-10 rounded-full object-cover border border-slate-200 dark:border-zinc-700 flex-shrink-0"
                            />
                          ) : (
                            <div className="w-10 h-10 rounded-full bg-[#1a73e8] text-white flex items-center justify-center font-bold text-base flex-shrink-0 shadow-xs">
                              {initial}
                            </div>
                          )}

                          <div className="flex flex-col min-w-0">
                            <div className="flex items-center gap-1.5">
                              <span className="text-sm font-semibold truncate leading-tight">
                                {account.displayName}
                              </span>
                              {account.level && (
                                <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-purple-500/10 text-purple-600 dark:text-purple-400 font-bold">
                                  Lvl {account.level}
                                </span>
                              )}
                            </div>
                            <span className="text-xs opacity-65 truncate leading-tight mt-0.5">
                              {account.email}
                            </span>
                          </div>
                        </div>

                        <div className="flex items-center gap-1">
                          {isSigningInThis ? (
                            <div className="w-5 h-5 border-2 border-[#1a73e8] border-t-transparent rounded-full animate-spin flex-shrink-0" />
                          ) : (
                            <>
                              <button
                                type="button"
                                title="Remove from this device"
                                onClick={(e) => handleRemoveAccount(e, account.email)}
                                className="p-1.5 rounded-lg opacity-40 hover:opacity-100 hover:text-rose-500 transition-all cursor-pointer"
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                              </button>
                              <ChevronRight className="w-4 h-4 opacity-40 flex-shrink-0" />
                            </>
                          )}
                        </div>
                      </div>
                    );
                  })}

                  {/* "+ Use another account on your phone" Row */}
                  <button
                    type="button"
                    disabled={isLoading}
                    onClick={handleTriggerGooglePrompt}
                    className="w-full py-3 px-2 flex items-center gap-3 text-left hover:bg-slate-50 dark:hover:bg-white/5 active:bg-slate-100 dark:active:bg-white/10 rounded-2xl transition-all cursor-pointer disabled:opacity-50"
                  >
                    <div className="w-10 h-10 rounded-full border border-slate-300 dark:border-zinc-600 flex items-center justify-center opacity-70 flex-shrink-0">
                      <Plus className="w-5 h-5" />
                    </div>
                    <span className="text-sm font-medium">Use another account on your phone</span>
                  </button>
                </div>
              ) : (
                /* View 2: Prompt Authentic Google Identity Services */
                <div className="py-2 text-center space-y-4">
                  <p className="text-xs opacity-75 leading-relaxed">
                    Choose one of the Google accounts logged into your phone or browser to continue:
                  </p>

                  {/* Container for Google's official rendered button */}
                  <div ref={googleBtnContainerRef} className="flex justify-center min-h-[44px]" />

                  <button
                    type="button"
                    onClick={handleTriggerGooglePrompt}
                    className="w-full py-2.5 px-4 rounded-2xl border border-slate-200 dark:border-zinc-700 hover:bg-slate-50 dark:hover:bg-white/5 active:scale-[0.99] text-xs font-semibold flex items-center justify-center gap-2 transition-all cursor-pointer"
                  >
                    <ShieldCheck className="w-4 h-4 text-[#1a73e8]" />
                    <span>Open Phone Account Chooser</span>
                  </button>
                </div>
              )}

              <div className="h-[1px] bg-slate-200 dark:bg-[#3c4043] mt-3 mb-3" />

              <p className="text-[11px] leading-relaxed opacity-60 text-center px-1">
                To continue, Google will share your verified name, email address, and profile picture with Bingo. Only genuine Google accounts are accepted.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* ───────────────────────────────────────────────────────────── */}
      {/* ── GOOGLE CLOUD CONSOLE WEB SETUP MODAL (Parity with Android SHA-1 Dialog) ── */}
      {/* ───────────────────────────────────────────────────────────── */}
      {showGoogleSetupModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in select-none">
          <div
            style={{
              backgroundColor: tokens.surface,
              borderColor: tokens.surfaceBorder
            }}
            className="w-full max-w-sm sm:max-w-md rounded-3xl border shadow-2xl p-5 sm:p-6 relative animate-slide-up"
          >
            <button
              type="button"
              onClick={() => setShowGoogleSetupModal(false)}
              className="absolute top-4 right-4 w-8 h-8 rounded-full bg-black/5 dark:bg-white/5 hover:bg-black/10 dark:hover:bg-white/10 flex items-center justify-center cursor-pointer transition-colors"
            >
              <X className="w-4 h-4 opacity-70" />
            </button>

            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-2xl bg-purple-50 dark:bg-purple-950/40 flex items-center justify-center border border-purple-200 dark:border-purple-800 flex-shrink-0">
                <svg className="w-5 h-5" viewBox="0 0 512 512">
                  <path fill="#4285F4" d="M482.6,261.4c0,-16.7 -1.5,-32.8 -4.3,-48.3H256v91.3h127c-5.5,29.5 -22.1,54.5 -47.1,71.2v59.2h76.3c44.6,-41.1 70.4,-101.6 70.4,-173.5z"/>
                  <path fill="#34A853" d="M256,492c63.7,0 117.1,-21.1 156.2,-57.2l-76.3,-59.2c-21.1,14.2 -48.2,22.5 -79.9,22.5 -61.5,0 -113.5,-41.5 -132.1,-97.3H45.1v61.2c38.8,77.1 118.6,130 210.9,130z"/>
                  <path fill="#FBBC05" d="M123.9,300.8c-4.7,-14.2 -7.4,-29.3 -7.4,-44.8s2.7,-30.7 7.4,-44.8V150H45.1C29.1,181.9 20,217.9 20,256c0,38.1 9.1,74.1 25.1,106l78.8,-61.2z"/>
                  <path fill="#EA4335" d="M256,113.9c34.7,0 65.8,11.9 90.2,35.3l67.7,-67.7C373,43.4 319.6,20 256,20c-92.3,0 -172.1,52.9 -210.9,130l78.8,61.2c18.6,-55.8 70.6,-97.3 132.1,-97.3z"/>
                </svg>
              </div>
              <div>
                <h3 style={{ color: tokens.cellNeutralText }} className="text-base font-bold leading-tight">
                  Google Sign-In Web Setup
                </h3>
                <p style={{ color: tokens.textMuted }} className="text-xs">
                  Authentic account chooser for phone & web
                </p>
              </div>
            </div>

            <div className="space-y-3.5 text-xs">
              <p style={{ color: tokens.textSecondary }} className="leading-relaxed">
                To fetch the real Google accounts stored on players&apos; phones on the web, Google requires an OAuth 2.0 Web Client ID registered in Google Cloud Console with this authorized origin:
              </p>

              {/* Authorized Origin Box */}
              <div
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder
                }}
                className="p-3 rounded-2xl border flex items-center justify-between gap-2"
              >
                <div className="min-w-0">
                  <span className="text-[10px] uppercase font-bold text-purple-500 block mb-0.5 tracking-wider">
                    Authorized JavaScript Origin
                  </span>
                  <code className="text-[11px] font-mono font-bold truncate block">
                    https://vamsireddybora.github.io
                  </code>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    navigator.clipboard.writeText('https://vamsireddybora.github.io');
                    setCopySuccess(true);
                    setTimeout(() => setCopySuccess(false), 2000);
                  }}
                  className="px-2.5 py-1.5 rounded-xl bg-purple-500/10 hover:bg-purple-500/20 text-purple-600 dark:text-purple-400 font-semibold text-[11px] flex items-center gap-1 cursor-pointer transition-colors flex-shrink-0"
                >
                  {copySuccess ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{copySuccess ? 'Copied' : 'Copy'}</span>
                </button>
              </div>

              {/* Paste Web Client ID */}
              <form onSubmit={handleSaveCustomClientId} className="space-y-2 pt-1">
                <label style={{ color: tokens.textMuted }} className="block text-[11px] font-semibold">
                  Google OAuth 2.0 Web Client ID:
                </label>
                <input
                  type="text"
                  value={clientIdInput}
                  onChange={(e) => setClientIdInput(e.target.value)}
                  placeholder="e.g. 123456789-xyz.apps.googleusercontent.com"
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    borderColor: tokens.surfaceBorder,
                    color: tokens.cellNeutralText
                  }}
                  className="w-full px-3.5 py-2.5 rounded-xl border text-xs font-mono focus:outline-none focus:ring-1 focus:ring-purple-500"
                />

                <button
                  type="submit"
                  disabled={!clientIdInput.trim()}
                  style={{
                    backgroundColor: tokens.primaryButtonBg,
                    color: tokens.primaryButtonText
                  }}
                  className="w-full py-2.5 rounded-xl font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer disabled:opacity-50 transition-all active:scale-95 shadow-sm"
                >
                  <Check className="w-3.5 h-3.5" />
                  <span>Save & Activate Google Sign-In</span>
                </button>
              </form>

              <div className="pt-2 border-t border-inherit flex items-center justify-between text-[11px]">
                <span style={{ color: tokens.textMuted }}>No account required?</span>
                <button
                  type="button"
                  onClick={() => {
                    setShowGoogleSetupModal(false);
                  }}
                  className="font-bold text-purple-600 dark:text-purple-400 hover:underline cursor-pointer"
                >
                  Play as Guest below →
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ───────────────────────────────────────────────────────────── */}
      {/* ── FIRST-TIME USERNAME MODAL (Matches Android LoginScreen) ── */}
      {/* ───────────────────────────────────────────────────────────── */}
      {showFirstTimeNameDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{
              backgroundColor: tokens.surface,
              borderColor: tokens.surfaceBorder
            }}
            className="w-full max-w-sm rounded-3xl border shadow-2xl p-5 sm:p-6"
          >
            <h3 style={{ color: tokens.cellNeutralText }} className="text-base font-bold mb-1">
              Choose Unique Username
            </h3>
            <p style={{ color: tokens.textSecondary }} className="text-xs leading-relaxed mb-4">
              Welcome to Bingo! Set a unique username for your Google account so opponents can challenge you:
            </p>

            <form onSubmit={handleConfirmFirstTimeNickname} className="space-y-3">
              <div>
                <input
                  type="text"
                  maxLength={16}
                  value={newPlayerIdInput}
                  onChange={(e) => {
                    setNewPlayerIdInput(e.target.value);
                    setUsernameError(null);
                  }}
                  placeholder="e.g. champion"
                  autoFocus
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    borderColor: tokens.surfaceBorder,
                    color: tokens.cellNeutralText
                  }}
                  className="w-full px-3.5 py-2.5 rounded-xl border text-sm font-semibold focus:outline-none focus:ring-1 focus:ring-purple-500"
                />
              </div>

              {usernameError && (
                <p className="text-xs font-medium text-rose-500">{usernameError}</p>
              )}

              <div className="flex items-center gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowFirstTimeNameDialog(false)}
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    color: tokens.textSecondary
                  }}
                  className="flex-1 h-10 rounded-xl font-semibold text-xs cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={newPlayerIdInput.trim().length < 3 || isCheckingUsername}
                  style={{
                    backgroundColor: tokens.primaryButtonBg,
                    color: tokens.primaryButtonText
                  }}
                  className="flex-1 h-10 rounded-xl font-bold text-xs flex items-center justify-center gap-1 cursor-pointer disabled:opacity-50"
                >
                  <span>{isCheckingUsername ? 'Checking...' : 'Confirm & Start'}</span>
                  <Check className="w-3.5 h-3.5" />
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ───────────────────────────────────────────────────────────── */}
      {/* ── SECONDARY MANUAL PLAYER ID RECOVERY MODAL ── */}
      {/* ───────────────────────────────────────────────────────────── */}
      {showManualIdModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{
              backgroundColor: tokens.surface,
              borderColor: tokens.surfaceBorder
            }}
            className="w-full max-w-sm rounded-3xl border shadow-2xl p-5 sm:p-6"
          >
            <div className="flex items-center justify-between pb-3 border-b border-inherit">
              <span style={{ color: tokens.cellNeutralText }} className="font-bold text-sm">
                Link Existing Player ID
              </span>
              <button
                type="button"
                onClick={() => setShowManualIdModal(false)}
                className="w-7 h-7 rounded-full flex items-center justify-center opacity-70 hover:opacity-100"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleManualIdRestore} className="mt-4 space-y-4">
              <p style={{ color: tokens.textSecondary }} className="text-xs leading-relaxed">
                Enter your unique Player ID or username (e.g. <code>@player</code>) to sync your stats and progress:
              </p>

              <div>
                <input
                  type="text"
                  value={manualIdInput}
                  onChange={(e) => setManualIdInput(e.target.value)}
                  placeholder="e.g. @bob or bob"
                  autoFocus
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    borderColor: tokens.surfaceBorder,
                    color: tokens.cellNeutralText
                  }}
                  className="w-full px-3.5 py-2.5 rounded-xl border text-sm font-mono focus:outline-none focus:ring-1 focus:ring-purple-500"
                />
              </div>

              <div className="flex items-center gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setShowManualIdModal(false)}
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    color: tokens.textSecondary
                  }}
                  className="flex-1 h-10 rounded-xl font-semibold text-xs cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={!manualIdInput.trim()}
                  style={{
                    backgroundColor: tokens.primaryButtonBg,
                    color: tokens.primaryButtonText
                  }}
                  className="flex-1 h-10 rounded-xl font-bold text-xs flex items-center justify-center gap-1 cursor-pointer disabled:opacity-50"
                >
                  <span>Link Account</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
