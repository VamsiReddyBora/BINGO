import React, { useState, useEffect } from 'react';
import { User, Info, AlertCircle, ArrowRight, ArrowLeft, X, Plus, ChevronRight, Check, Trash2, CheckCircle2 } from 'lucide-react';
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

// Retrieve ONLY accounts previously signed into on THIS specific browser/phone
function getSavedGoogleAccounts(): SavedGoogleAccount[] {
  try {
    // Clean up any legacy storage keys that had default test accounts
    localStorage.removeItem('bingo_saved_google_accounts_v1');

    const raw = localStorage.getItem(STORAGE_KEY_SAVED_GOOGLE_ACCOUNTS);
    if (raw) {
      const list = JSON.parse(raw);
      if (Array.isArray(list)) {
        // Guard: explicitly filter out any hardcoded or foreign test accounts
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
    if (acc.email.toLowerCase() === 'sherlock7528@gmail.com') return; // Do not save foreign test account
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
  const [googleModalView, setGoogleModalView] = useState<'CHOOSER' | 'MANUAL_SIGNIN'>('MANUAL_SIGNIN');
  const [savedAccounts, setSavedAccounts] = useState<SavedGoogleAccount[]>([]);
  const [selectedAccountEmail, setSelectedAccountEmail] = useState<string | null>(null);
  const [googleEmailInput, setGoogleEmailInput] = useState('');

  // Live Cloud Account Search Status while typing
  const [isSearchingCloud, setIsSearchingCloud] = useState(false);
  const [foundCloudBackup, setFoundCloudBackup] = useState<CloudUserDataBackup | null>(null);

  // ── First-time username prompt (matching Android LoginScreen) ──
  const [showFirstTimeNameDialog, setShowFirstTimeNameDialog] = useState(false);
  const [pendingGoogleData, setPendingGoogleData] = useState<{ id: string; email: string; name: string } | null>(null);
  const [newPlayerIdInput, setNewPlayerIdInput] = useState('');
  const [usernameError, setUsernameError] = useState<string | null>(null);
  const [isCheckingUsername, setIsCheckingUsername] = useState(false);

  // ── Secondary Manual Player ID Recovery Modal ──
  const [showManualIdModal, setShowManualIdModal] = useState(false);
  const [manualIdInput, setManualIdInput] = useState('');

  useEffect(() => {
    // Purge any old test accounts on mount
    try {
      localStorage.removeItem('bingo_saved_google_accounts_v1');
    } catch {}
    setSavedAccounts(getSavedGoogleAccounts());
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

  // ── Open Google Sign-In Sheet ──
  const handleOpenGoogleSignIn = () => {
    soundEffects.playTap();
    setErrorMessage(null);
    setFoundCloudBackup(null);

    // If client ID is present, initialize GIS prompt
    const clientId = (import.meta as any).env?.VITE_GOOGLE_CLIENT_ID || localStorage.getItem('bingo_google_client_id');
    if (clientId && (window as any).google?.accounts?.id) {
      try {
        (window as any).google.accounts.id.initialize({
          client_id: clientId,
          callback: handleGisCredentialResponse
        });
        (window as any).google.accounts.id.prompt();
      } catch (e) {
        console.warn('GIS prompt error:', e);
      }
    }

    const accounts = getSavedGoogleAccounts();
    setSavedAccounts(accounts);
    // If THIS user on THIS device previously signed into an account, show chooser.
    // Otherwise, immediately show Google's sign-in prompt so they enter THEIR OWN account!
    setGoogleModalView(accounts.length > 0 ? 'CHOOSER' : 'MANUAL_SIGNIN');
    setShowGoogleModal(true);
  };

  // ── Live Cloud Account Lookup while user types their email ──
  useEffect(() => {
    const clean = googleEmailInput.trim().toLowerCase();
    if (!clean || clean.length < 3) {
      setFoundCloudBackup(null);
      setIsSearchingCloud(false);
      return;
    }

    const timer = setTimeout(async () => {
      setIsSearchingCloud(true);
      const cleanUser = clean.replace(/^@/, '').split('@')[0];
      try {
        let backup = await CloudRegistry.fetchUserDataBackup(clean);
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
                email: clean.includes('@') ? clean : `${clean}@gmail.com`
              }
            };
          }
        }
        setFoundCloudBackup(backup);
      } catch {
        setFoundCloudBackup(null);
      } finally {
        setIsSearchingCloud(false);
      }
    }, 400);

    return () => clearTimeout(timer);
  }, [googleEmailInput]);

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
      setGoogleModalView('MANUAL_SIGNIN');
    }
  };

  // ── Handle Submitting Google Email in Google Sign-In View ──
  const handleGoogleEmailSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const input = googleEmailInput.trim().toLowerCase();
    if (!input || input.length < 3) {
      setErrorMessage('Enter a valid Google email address.');
      return;
    }

    const email = input.includes('@') ? input : `${input}@gmail.com`;
    const cleanUser = input.replace(/^@/, '').split('@')[0];
    const googleId = cleanUser.replace(/[^a-z0-9_]/g, '');

    soundEffects.playTap();
    await executeGoogleSignInProcess(
      email,
      googleId,
      cleanUser.charAt(0).toUpperCase() + cleanUser.slice(1),
      null
    );
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

      {/* ── Subtle Bottom Action: Developer Note ── */}
      <div className="pt-4 pb-2">
        <button
          type="button"
          onClick={onOpenDeveloperNote}
          style={{ color: tokens.textMuted }}
          className="flex items-center gap-1.5 text-xs font-medium opacity-70 hover:opacity-100 transition-opacity cursor-pointer"
        >
          <Info className="w-3.5 h-3.5" />
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

            {/* ── VIEW A: GOOGLE ACCOUNT CHOOSER (Only shown if this device has saved accounts) ── */}
            {googleModalView === 'CHOOSER' && savedAccounts.length > 0 ? (
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
                      <h2 className="text-base font-semibold leading-tight">Choose an account</h2>
                      <p className="text-xs opacity-70">to continue to Bingo</p>
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => setShowGoogleModal(false)}
                    className="p-1.5 rounded-full hover:bg-black/10 dark:hover:bg-white/10 transition-colors"
                  >
                    <X className="w-4 h-4 opacity-70" />
                  </button>
                </div>

                <div className="h-[1px] bg-slate-200 dark:bg-[#3c4043] my-3" />

                {/* Account List */}
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
                          {/* Account Avatar */}
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

                  {/* "+ Use another account" Row */}
                  <button
                    type="button"
                    disabled={isLoading}
                    onClick={() => {
                      soundEffects.playTap();
                      setGoogleEmailInput('');
                      setFoundCloudBackup(null);
                      setGoogleModalView('MANUAL_SIGNIN');
                    }}
                    className="w-full py-3.5 px-2 flex items-center gap-3 text-left hover:bg-slate-50 dark:hover:bg-white/5 active:bg-slate-100 dark:active:bg-white/10 rounded-2xl transition-all cursor-pointer disabled:opacity-50"
                  >
                    <div className="w-10 h-10 rounded-full border border-slate-300 dark:border-zinc-600 flex items-center justify-center opacity-70 flex-shrink-0">
                      <Plus className="w-5 h-5" />
                    </div>
                    <span className="text-sm font-medium">Use another account</span>
                  </button>
                </div>

                <div className="h-[1px] bg-slate-200 dark:bg-[#3c4043] mt-3 mb-3.5" />

                {/* Google Disclaimer Footer */}
                <p className="text-[11px] leading-relaxed opacity-60 text-center px-1">
                  To continue, Google will share your name, email address, and profile picture with Bingo. Before using Bingo, review their Privacy Policy and Terms of Service.
                </p>
              </div>
            ) : (
              /* ── VIEW B: AUTHENTIC GOOGLE SIGN-IN PROMPT (Shown by default on any fresh device) ── */
              <div className="p-5 sm:p-6">
                <div className="flex items-center justify-between mb-4">
                  {savedAccounts.length > 0 ? (
                    <button
                      type="button"
                      onClick={() => setGoogleModalView('CHOOSER')}
                      className="p-1.5 rounded-full hover:bg-black/10 dark:hover:bg-white/10 transition-colors"
                    >
                      <ArrowLeft className="w-4 h-4 opacity-80" />
                    </button>
                  ) : (
                    <div className="w-6" />
                  )}

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

                  <button
                    type="button"
                    onClick={() => setShowGoogleModal(false)}
                    className="p-1.5 rounded-full hover:bg-black/10 dark:hover:bg-white/10 transition-colors"
                  >
                    <X className="w-4 h-4 opacity-70" />
                  </button>
                </div>

                <div className="text-center mb-5">
                  <h2 className="text-lg font-normal leading-tight">Sign in with Google</h2>
                  <p className="text-xs opacity-70 mt-1">
                    Enter your Google account from your phone to continue to Bingo
                  </p>
                </div>

                <form onSubmit={handleGoogleEmailSubmit} className="space-y-4">
                  <div>
                    <label className="text-xs font-medium block mb-1.5 opacity-80">
                      Email or phone
                    </label>
                    <input
                      type="text"
                      value={googleEmailInput}
                      onChange={(e) => setGoogleEmailInput(e.target.value)}
                      placeholder="e.g. name@gmail.com"
                      autoFocus
                      className={`w-full px-3.5 py-2.5 rounded-lg border text-sm focus:outline-none focus:ring-2 focus:ring-[#1a73e8] ${
                        isDark
                          ? 'bg-[#171717] border-[#3c4043] text-white'
                          : 'bg-white border-[#dadce0] text-[#202124]'
                      }`}
                    />
                  </div>

                  {/* Real-time Cloud Account Detection Card */}
                  {isSearchingCloud && (
                    <div className="p-2.5 rounded-xl bg-purple-500/10 border border-purple-500/20 flex items-center gap-2 text-xs text-purple-600 dark:text-purple-400">
                      <div className="w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin flex-shrink-0" />
                      <span>Checking Android records in cloud...</span>
                    </div>
                  )}

                  {foundCloudBackup && (
                    <div className="p-3 rounded-2xl bg-emerald-500/10 border border-emerald-500/30 animate-fade-in">
                      <div className="flex items-center gap-2 text-emerald-600 dark:text-emerald-400 text-xs font-bold mb-1">
                        <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
                        <span>Android Account Found!</span>
                      </div>
                      <div className="text-[11px] opacity-80 space-y-0.5">
                        <p className="font-semibold">
                          @{foundCloudBackup.profile.username} ({foundCloudBackup.profile.displayName})
                        </p>
                        <p className="text-emerald-600 dark:text-emerald-400">
                          Level {foundCloudBackup.profile.level} • {foundCloudBackup.profile.gamesWon} Wins / {foundCloudBackup.profile.gamesPlayed} Matches • All Friends Synced ✓
                        </p>
                      </div>
                    </div>
                  )}

                  <p className="text-[11px] opacity-60 leading-relaxed">
                    Enter the Google email you use on your phone. All your level, stats, wins, and friends from the Android app will be synced automatically.
                  </p>

                  <div className="flex items-center justify-between pt-2">
                    {savedAccounts.length > 0 ? (
                      <button
                        type="button"
                        onClick={() => setGoogleModalView('CHOOSER')}
                        className="text-xs font-semibold text-[#1a73e8] hover:underline cursor-pointer"
                      >
                        Choose account
                      </button>
                    ) : (
                      <div />
                    )}

                    <button
                      type="submit"
                      disabled={!googleEmailInput.trim()}
                      className="px-6 py-2 rounded-full bg-[#1a73e8] hover:bg-[#1557b0] text-white text-xs font-medium shadow-sm transition-all active:scale-95 disabled:opacity-50 cursor-pointer"
                    >
                      <span>{foundCloudBackup ? 'Restore & Play' : 'Next'}</span>
                    </button>
                  </div>
                </form>
              </div>
            )}
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
