import React, { useState } from 'react';
import { User, Info, AlertCircle, ArrowRight, X, Mail } from 'lucide-react';
import { Player } from '../types/models';
import { CloudRegistry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { useTheme } from '../theme/theme';

interface Props {
  onLoginSuccess: (player: Player) => void;
  onOpenDeveloperNote?: () => void;
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

  // Link / Google account restore modal
  const [showRestoreModal, setShowRestoreModal] = useState(false);
  const [restoreIdentifier, setRestoreIdentifier] = useState('');
  const [showSha1Modal, setShowSha1Modal] = useState(false);

  // First-time username prompt if creating brand new account
  const [showFirstTimeNameDialog, setShowFirstTimeNameDialog] = useState(false);
  const [pendingGoogleData, setPendingGoogleData] = useState<{ id: string; email: string; name: string } | null>(null);
  const [newPlayerIdInput, setNewPlayerIdInput] = useState('');
  const [usernameError, setUsernameError] = useState<string | null>(null);

  // Handle Guest Login
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

    CloudRegistry.claimAndRegisterUser(guestPlayer).catch(() => {});
    setIsLoading(false);
    onLoginSuccess(guestPlayer);
  };

  // Handle Google / Cloud Account Restore
  const handleRestoreAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = restoreIdentifier.trim().toLowerCase().replace(/^@/, '');
    if (!clean || clean.length < 3) {
      setErrorMessage('Please enter a valid username or email.');
      return;
    }

    setIsLoading(true);
    setLoadingMessage(`Restoring cloud account @${clean}...`);
    setShowRestoreModal(false);

    try {
      let backup = await CloudRegistry.fetchUserDataBackup(clean);
      if (!backup) backup = await CloudRegistry.fetchUserBackupByUsername(clean);
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
        const restoredPlayer: Player = {
          id: backup.profile.uid,
          displayName: backup.profile.displayName,
          username: backup.profile.username,
          isHost: false,
          avatarUrl: backup.profile.avatarUrl,
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

        setIsLoading(false);
        onLoginSuccess(restoredPlayer);
        return;
      }

      setIsLoading(false);
      setErrorMessage(`No account found for '${clean}'. Try another or play as Guest.`);
    } catch {
      setIsLoading(false);
      setErrorMessage('Failed to connect to cloud service. Try again or play as Guest.');
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
                onClick={() => setShowRestoreModal(true)}
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
                <span>Continue with Google / Cloud Account</span>
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

                {/* 3. Play as Guest Button (Soft plain purple tint, NO BORDER, bare icon) */}
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

      {/* ── Restore / Link Cloud Account Modal ── */}
      {showRestoreModal && (
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
                Restore Google / Cloud Account
              </span>
              <button
                type="button"
                onClick={() => setShowRestoreModal(false)}
                className="w-7 h-7 rounded-full flex items-center justify-center opacity-70 hover:opacity-100"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleRestoreAccount} className="mt-4 space-y-4">
              <p style={{ color: tokens.textSecondary }} className="text-xs leading-relaxed">
                Enter your Google account email or unique Player ID (e.g. <code>@username</code>) to sync all your stats, level, and wins from Android:
              </p>

              <div>
                <input
                  type="text"
                  value={restoreIdentifier}
                  onChange={(e) => setRestoreIdentifier(e.target.value)}
                  placeholder="e.g. user@gmail.com or @player"
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
                  onClick={() => setShowRestoreModal(false)}
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
                  disabled={!restoreIdentifier.trim()}
                  style={{
                    backgroundColor: tokens.primaryButtonBg,
                    color: tokens.primaryButtonText
                  }}
                  className="flex-1 h-10 rounded-xl font-bold text-xs flex items-center justify-center gap-1 cursor-pointer disabled:opacity-50"
                >
                  <span>Restore Now</span>
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
