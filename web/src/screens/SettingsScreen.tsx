import React, { useState, useEffect } from 'react';
import {
  ArrowLeft,
  Moon,
  Sun,
  Volume2,
  VolumeX,
  Download,
  LogOut,
  Edit2,
  Check,
  X,
  Sparkles
} from 'lucide-react';
import { Player } from '../types/models';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';
import { PlayerAvatar } from '../components/PlayerAvatar';

interface Props {
  localPlayer: Player;
  onUpdatePlayer: (updated: Player) => void;
  onSignOut: () => void;
  onBack: () => void;
}

export const SettingsScreen: React.FC<Props> = ({
  localPlayer,
  onUpdatePlayer,
  onSignOut,
  onBack
}) => {
  const { tokens, isDark, toggleTheme } = useTheme();

  const [soundEnabled, setSoundEnabled] = useState(soundEffects.isEnabled());
  const [showEditNameDialog, setShowEditNameDialog] = useState(false);
  const [newDisplayName, setNewDisplayName] = useState(localPlayer.displayName);

  // PWA install prompt handler
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [isInstallable, setIsInstallable] = useState(false);
  const [isInstalled, setIsInstalled] = useState(false);

  useEffect(() => {
    // Check if already in standalone display mode
    if (window.matchMedia('(display-mode: standalone)').matches) {
      setIsInstalled(true);
    }

    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e);
      setIsInstallable(true);
    };

    window.addEventListener('beforeinstallprompt', handler);
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  const handleInstallPwa = async () => {
    soundEffects.playTap();
    if (!deferredPrompt) {
      alert(
        'To install Bingo on your device, tap "Install App" in your browser menu (or Share -> Add to Home Screen on iOS Safari).'
      );
      return;
    }

    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    if (outcome === 'accepted') {
      setIsInstalled(true);
      setIsInstallable(false);
    }
    setDeferredPrompt(null);
  };

  const handleToggleSound = () => {
    const next = !soundEnabled;
    setSoundEnabled(next);
    soundEffects.setSoundEnabled(next);
  };

  const handleSaveNickname = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = newDisplayName.trim();
    if (!trimmed) return;
    soundEffects.playTap();

    const updated: Player = {
      ...localPlayer,
      displayName: trimmed
    };
    onUpdatePlayer(updated);
    setShowEditNameDialog(false);
  };

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="min-h-[100dvh] w-full flex flex-col max-w-md mx-auto p-4 sm:p-5 select-none transition-colors duration-300 pb-24"
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
          Settings
        </h1>
      </div>

      <div className="flex flex-col gap-4 mt-4">
        {/* ── Profile Section Card ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-4 sm:p-5 border shadow-sm flex items-center justify-between"
        >
          <div className="flex items-center gap-3.5">
            <PlayerAvatar
              avatarUrl={localPlayer.avatarUrl}
              displayName={localPlayer.displayName}
              username={localPlayer.username}
              size={48}
              borderWidth={1.5}
              borderColor={tokens.accentBrand}
            />
            <div className="flex flex-col">
              <span
                style={{ color: tokens.cellNeutralText }}
                className="text-base font-bold"
              >
                {localPlayer.displayName}
              </span>
              <span
                style={{ color: tokens.textMuted }}
                className="text-xs font-mono"
              >
                @{localPlayer.username}
              </span>
            </div>
          </div>

          <button
            type="button"
            onClick={() => {
              soundEffects.playTap();
              setNewDisplayName(localPlayer.displayName);
              setShowEditNameDialog(true);
            }}
            style={{
              backgroundColor: tokens.backgroundSecondary,
              color: tokens.cellNeutralText
            }}
            className="w-8 h-8 rounded-xl flex items-center justify-center cursor-pointer active:scale-95 hover:opacity-80"
          >
            <Edit2 className="w-4 h-4" />
          </button>
        </div>

        {/* ── Appearance Section ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3"
        >
          <span
            style={{ color: tokens.textMuted }}
            className="text-[11px] font-bold uppercase tracking-wider"
          >
            APPEARANCE
          </span>

          <div className="flex items-center justify-between py-1">
            <div className="flex items-center gap-3">
              {isDark ? (
                <Moon className="w-5 h-5 text-purple-400" />
              ) : (
                <Sun className="w-5 h-5 text-amber-500" />
              )}
              <div className="flex flex-col">
                <span
                  style={{ color: tokens.cellNeutralText }}
                  className="text-sm font-semibold"
                >
                  Theme
                </span>
                <span style={{ color: tokens.textMuted }} className="text-xs">
                  {isDark ? 'AMOLED Pure Black (#000000)' : 'Clean Light Mode'}
                </span>
              </div>
            </div>

            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                toggleTheme();
              }}
              style={{
                backgroundColor: tokens.backgroundSecondary,
                borderColor: tokens.surfaceBorder,
                color: tokens.cellNeutralText
              }}
              className="px-3 py-1.5 rounded-xl border text-xs font-bold cursor-pointer active:scale-95 transition-all shadow-xs"
            >
              Switch to {isDark ? 'Light' : 'AMOLED'}
            </button>
          </div>
        </div>

        {/* ── Audio Section ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3"
        >
          <span
            style={{ color: tokens.textMuted }}
            className="text-[11px] font-bold uppercase tracking-wider"
          >
            AUDIO & HAPTICS
          </span>

          <div className="flex items-center justify-between py-1">
            <div className="flex items-center gap-3">
              {soundEnabled ? (
                <Volume2 className="w-5 h-5 text-emerald-500" />
              ) : (
                <VolumeX className="w-5 h-5 opacity-40" />
              )}
              <div className="flex flex-col">
                <span
                  style={{ color: tokens.cellNeutralText }}
                  className="text-sm font-semibold"
                >
                  Sound Effects
                </span>
                <span style={{ color: tokens.textMuted }} className="text-xs">
                  Tile clicks, wins, and countdown audio
                </span>
              </div>
            </div>

            <button
              type="button"
              onClick={handleToggleSound}
              style={{
                backgroundColor: soundEnabled
                  ? tokens.primaryButtonBg
                  : tokens.backgroundSecondary,
                color: soundEnabled
                  ? tokens.primaryButtonText
                  : tokens.textMuted
              }}
              className="px-3.5 py-1.5 rounded-xl text-xs font-bold cursor-pointer active:scale-95 transition-all shadow-xs"
            >
              {soundEnabled ? 'Enabled' : 'Muted'}
            </button>
          </div>
        </div>

        {/* ── Progressive Web App (PWA) Section ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3"
        >
          <span
            style={{ color: tokens.textMuted }}
            className="text-[11px] font-bold uppercase tracking-wider"
          >
            PROGRESSIVE WEB APP (PWA)
          </span>

          <div className="flex items-center justify-between py-1">
            <div className="flex items-center gap-3">
              <Download className="w-5 h-5 text-blue-500" />
              <div className="flex flex-col">
                <span
                  style={{ color: tokens.cellNeutralText }}
                  className="text-sm font-semibold"
                >
                  Install Web App
                </span>
                <span style={{ color: tokens.textMuted }} className="text-xs">
                  {isInstalled
                    ? 'Running as standalone application'
                    : 'Install on Home Screen / Desktop'}
                </span>
              </div>
            </div>

            <button
              type="button"
              onClick={handleInstallPwa}
              disabled={isInstalled}
              style={{
                backgroundColor: isInstalled
                  ? tokens.backgroundSecondary
                  : tokens.primaryButtonBg,
                color: isInstalled
                  ? tokens.textMuted
                  : tokens.primaryButtonText
              }}
              className="px-3.5 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1 cursor-pointer active:scale-95 disabled:cursor-default"
            >
              {isInstalled ? (
                <>
                  <Check className="w-3.5 h-3.5" />
                  <span>Installed</span>
                </>
              ) : (
                <>
                  <Download className="w-3.5 h-3.5" />
                  <span>Install App</span>
                </>
              )}
            </button>
          </div>
        </div>

        {/* ── App Version / Info ── */}
        <div className="py-2 flex flex-col items-center justify-center text-center">
          <span
            style={{ color: tokens.cellNeutralText }}
            className="text-xs font-bold tracking-wide"
          >
            Bingo Multiplayer v2.4.0
          </span>
          <span
            style={{ color: tokens.textMuted }}
            className="text-[11px] mt-0.5 opacity-70"
          >
            100% Native Parity • Android & Web Cross-Platform
          </span>
        </div>

        {/* ── Danger Zone: Sign Out ── */}
        <div className="pt-2">
          <button
            type="button"
            onClick={() => {
              if (confirm('Are you sure you want to sign out?')) {
                onSignOut();
              }
            }}
            className="w-full h-11 rounded-2xl bg-rose-500/10 hover:bg-rose-500/20 text-rose-500 font-bold text-xs flex items-center justify-center gap-2 border border-rose-500/30 transition-all cursor-pointer active:scale-98"
          >
            <LogOut className="w-4 h-4" />
            <span>Sign Out</span>
          </button>
        </div>
      </div>

      {/* Edit Nickname Modal */}
      {showEditNameDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{
              backgroundColor: tokens.surface,
              borderColor: tokens.surfaceBorder
            }}
            className="w-full max-w-sm rounded-3xl border shadow-2xl p-5"
          >
            <div className="flex items-center justify-between pb-3 border-b border-inherit">
              <span
                style={{ color: tokens.cellNeutralText }}
                className="font-bold text-sm"
              >
                Change Player Nickname
              </span>
              <button
                type="button"
                onClick={() => setShowEditNameDialog(false)}
                className="w-7 h-7 rounded-full flex items-center justify-center opacity-70 hover:opacity-100"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSaveNickname} className="mt-4 space-y-4">
              <div>
                <label
                  style={{ color: tokens.textMuted }}
                  className="text-xs font-semibold block mb-1.5"
                >
                  Display Name
                </label>
                <input
                  type="text"
                  maxLength={16}
                  value={newDisplayName}
                  onChange={(e) => setNewDisplayName(e.target.value)}
                  autoFocus
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    borderColor: tokens.surfaceBorder,
                    color: tokens.cellNeutralText
                  }}
                  className="w-full px-3.5 py-2.5 rounded-xl border text-sm font-semibold focus:outline-none focus:ring-1 focus:ring-purple-500"
                />
              </div>

              <div className="flex items-center gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setShowEditNameDialog(false)}
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
                  disabled={!newDisplayName.trim()}
                  style={{
                    backgroundColor: tokens.primaryButtonBg,
                    color: tokens.primaryButtonText
                  }}
                  className="flex-1 h-10 rounded-xl font-bold text-xs flex items-center justify-center gap-1 cursor-pointer disabled:opacity-50"
                >
                  <span>Save Changes</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
