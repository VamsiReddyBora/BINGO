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
  Palette,
  Camera,
  Plus,
  Trash2,
  RotateCcw,
  Sparkles,
  User,
  Shield,
  BarChart2
} from 'lucide-react';
import { Player, CloudUserDataBackup } from '../types/models';
import { useTheme, PALETTES, BORDER_COLOR_PRESETS } from '../theme/theme';
import { soundEffects } from '../audio/sounds';
import { EmojiPreferences, ALL_REACTION_EMOJIS, MAX_FAVORITES } from '../services/emojiPreferences';
import { CloudRegistry } from '../network/cloudRegistry';
import { PlayerAvatar } from '../components/PlayerAvatar';

interface Props {
  localPlayer: Player;
  onUpdatePlayer: (updated: Player) => void;
  onSignOut: () => void;
  onBack: () => void;
  onNavigateToDashboard?: () => void;
}

const AVATAR_PRESETS = ['👑', '🤖', '🥷', '🧙', '😎', '🌟', '🎮', '🔥', '💀', '👻', '👽', '🦁'];

export const SettingsScreen: React.FC<Props> = ({
  localPlayer,
  onUpdatePlayer,
  onSignOut,
  onBack,
  onNavigateToDashboard
}) => {
  const {
    tokens,
    isDark,
    toggleTheme,
    setDarkTheme,
    accentPaletteId,
    setAccentPalette,
    boardColors,
    updateBoardColors,
    resetBoardColors
  } = useTheme();

  const [soundEnabled, setSoundEnabled] = useState(soundEffects.isEnabled());

  // Profile Dialogs
  const [showEditNameDialog, setShowEditNameDialog] = useState(false);
  const [newDisplayName, setNewDisplayName] = useState(localPlayer.displayName);
  const [showAvatarDialog, setShowAvatarDialog] = useState(false);
  const [customAvatarUrl, setCustomAvatarUrl] = useState(localPlayer.avatarUrl || '');

  // Favorite Emojis
  const [favoriteEmojis, setFavoriteEmojis] = useState<string[]>(() =>
    EmojiPreferences.getFavoriteEmojis()
  );
  const [showAddEmojiModal, setShowAddEmojiModal] = useState(false);

  // Sign out modal
  const [showSignOutDialog, setShowSignOutDialog] = useState(false);

  // PWA install handler
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [isInstallable, setIsInstallable] = useState(false);
  const [isInstalled, setIsInstalled] = useState(false);

  useEffect(() => {
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

  // Sync updated profile to cloud
  const saveAndSyncPlayer = (updated: Player) => {
    onUpdatePlayer(updated);

    // Save full cloud backup (matching Android AccountSessionManager)
    const backup: CloudUserDataBackup = {
      profile: {
        uid: updated.id,
        username: updated.username,
        displayName: updated.displayName,
        avatarUrl: updated.avatarUrl,
        gamesPlayed: updated.gamesPlayed || 0,
        gamesWon: updated.gamesWon || 0,
        currentStreak: updated.currentStreak || 0,
        bestStreak: updated.gamesWon || 0,
        level: updated.level || 1,
        xp: 0,
        email: updated.email
      },
      lastBackupTimestamp: Date.now()
    };
    const targetId = updated.googleId || updated.username;
    CloudRegistry.saveUserDataBackup(targetId, backup).catch(() => {});
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
    saveAndSyncPlayer(updated);
    setShowEditNameDialog(false);
  };

  const handleSelectAvatarPreset = (preset: string) => {
    soundEffects.playTap();
    const updated: Player = {
      ...localPlayer,
      avatarUrl: preset
    };
    saveAndSyncPlayer(updated);
    setShowAvatarDialog(false);
  };

  const handleSaveCustomAvatarUrl = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = customAvatarUrl.trim();
    if (!trimmed) return;
    soundEffects.playTap();

    const updated: Player = {
      ...localPlayer,
      avatarUrl: trimmed
    };
    saveAndSyncPlayer(updated);
    setShowAvatarDialog(false);
  };

  // Emoji handlers
  const handleRemoveFavoriteEmoji = (emoji: string) => {
    soundEffects.playTap();
    if (EmojiPreferences.removeFavoriteEmoji(emoji)) {
      setFavoriteEmojis(EmojiPreferences.getFavoriteEmojis());
    }
  };

  const handleAddFavoriteEmoji = (emoji: string) => {
    soundEffects.playTap();
    if (EmojiPreferences.addFavoriteEmoji(emoji)) {
      setFavoriteEmojis(EmojiPreferences.getFavoriteEmojis());
      setShowAddEmojiModal(false);
    }
  };

  const handleResetEmojis = () => {
    soundEffects.playTap();
    const reset = EmojiPreferences.resetFavoritesToDefault();
    setFavoriteEmojis(reset);
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
        <h1 style={{ color: tokens.cellNeutralText }} className="text-xl font-bold font-heading">
          Settings
        </h1>
      </div>

      <div className="flex flex-col gap-4 mt-2">
        {/* ── 1. Profile Section Card ── */}
        <div
          style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3"
        >
          <span style={{ color: tokens.textMuted }} className="text-[11px] font-bold uppercase tracking-wider">
            Player Profile
          </span>

          <div className="flex items-center gap-3.5">
            <div className="relative group">
              <PlayerAvatar
                avatarUrl={localPlayer.avatarUrl}
                displayName={localPlayer.displayName}
                username={localPlayer.username}
                size={56}
                borderColor={tokens.accentBrand}
                borderWidth={2}
              />
              <button
                type="button"
                onClick={() => setShowAvatarDialog(true)}
                className="absolute inset-0 rounded-full bg-black/40 opacity-0 group-hover:opacity-100 flex items-center justify-center text-white transition-opacity cursor-pointer"
                title="Change Avatar"
              >
                <Camera className="w-4 h-4" />
              </button>
            </div>

            <div className="flex flex-col flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <span style={{ color: tokens.cellNeutralText }} className="text-base font-bold truncate">
                  {localPlayer.displayName}
                </span>
                <button
                  type="button"
                  onClick={() => setShowEditNameDialog(true)}
                  style={{ color: tokens.accentBrand }}
                  className="cursor-pointer active:scale-90 transition-transform"
                  title="Edit Nickname"
                >
                  <Edit2 className="w-3.5 h-3.5" />
                </button>
              </div>

              <span style={{ color: tokens.textMuted }} className="text-xs font-mono">
                @{localPlayer.username}
              </span>

              <div className="flex items-center gap-2 mt-1">
                <span
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    color: tokens.cellNeutralText
                  }}
                  className="text-[10px] font-bold px-2 py-0.5 rounded-full border border-inherit"
                >
                  Level {localPlayer.level || 1}
                </span>
                {localPlayer.email && (
                  <span style={{ color: tokens.textMuted }} className="text-[10px] truncate max-w-[140px]">
                    {localPlayer.email}
                  </span>
                )}
              </div>
            </div>
          </div>

          {onNavigateToDashboard && (
            <button
              type="button"
              onClick={onNavigateToDashboard}
              style={{
                backgroundColor: tokens.backgroundSecondary,
                borderColor: tokens.surfaceBorder,
                color: tokens.cellNeutralText
              }}
              className="w-full py-2 px-3 rounded-xl border text-xs font-semibold flex items-center justify-center gap-2 cursor-pointer active:scale-98 mt-1"
            >
              <BarChart2 className="w-4 h-4" />
              <span>View Match Stats & Leaderboard</span>
            </button>
          )}
        </div>

        {/* ── 2. Themes & Appearance Section ── */}
        <div
          style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3.5"
        >
          <div className="flex items-center justify-between">
            <span style={{ color: tokens.textMuted }} className="text-[11px] font-bold uppercase tracking-wider">
              Theme & Appearance
            </span>
            <Palette className="w-4 h-4 text-purple-500" />
          </div>

          {/* Mode Switch: Clean Light vs AMOLED Pure Dark */}
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                setDarkTheme(false);
              }}
              style={{
                backgroundColor: !isDark ? '#F5EEFF' : tokens.backgroundSecondary,
                borderColor: !isDark ? '#7E22CE' : tokens.surfaceBorder,
                color: !isDark ? '#7E22CE' : tokens.textMuted
              }}
              className="flex-1 py-2.5 px-3 rounded-xl border text-xs font-bold flex items-center justify-center gap-2 cursor-pointer active:scale-98 transition-all"
            >
              <Sun className="w-4 h-4" />
              <span>Clean Light</span>
            </button>

            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                setDarkTheme(true);
              }}
              style={{
                backgroundColor: isDark ? '#1F1F23' : tokens.backgroundSecondary,
                borderColor: isDark ? tokens.accentBrand : tokens.surfaceBorder,
                color: isDark ? '#FFFFFF' : tokens.textMuted
              }}
              className="flex-1 py-2.5 px-3 rounded-xl border text-xs font-bold flex items-center justify-center gap-2 cursor-pointer active:scale-98 transition-all"
            >
              <Moon className="w-4 h-4 text-purple-400" />
              <span>AMOLED Dark</span>
            </button>
          </div>

          {/* Accent Color Palettes (All 12 from Android ThemePreferences) */}
          <div className="flex flex-col gap-2 mt-1">
            <span style={{ color: tokens.cellNeutralText }} className="text-xs font-bold">
              Accent Color Palette
            </span>
            <div className="grid grid-cols-4 sm:grid-cols-6 gap-2">
              {PALETTES.map((pal) => {
                const isSelected = accentPaletteId === pal.id;
                return (
                  <button
                    key={pal.id}
                    type="button"
                    onClick={() => {
                      soundEffects.playTap();
                      setAccentPalette(pal.id);
                    }}
                    style={{
                      borderColor: isSelected ? tokens.accentBrand : 'transparent',
                      backgroundColor: tokens.backgroundSecondary
                    }}
                    className={`p-2 rounded-xl border flex flex-col items-center gap-1.5 cursor-pointer active:scale-95 transition-all ${
                      isSelected ? 'ring-2 ring-purple-500' : ''
                    }`}
                    title={pal.name}
                  >
                    <div
                      style={{ backgroundColor: pal.previewColor }}
                      className="w-5 h-5 rounded-full shadow-xs flex items-center justify-center text-white"
                    >
                      {isSelected && <Check className="w-3 h-3" />}
                    </div>
                    <span
                      style={{ color: tokens.cellNeutralText }}
                      className="text-[10px] font-semibold text-center truncate w-full"
                    >
                      {pal.name.split(' ')[0]}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Board & Cell Colors Customization */}
          <div className="flex flex-col gap-2.5 pt-2 border-t border-inherit">
            <div className="flex items-center justify-between">
              <span style={{ color: tokens.cellNeutralText }} className="text-xs font-bold">
                Board Colors
              </span>
              <button
                type="button"
                onClick={resetBoardColors}
                style={{ color: tokens.textMuted }}
                className="text-[11px] font-semibold flex items-center gap-1 hover:underline cursor-pointer"
              >
                <RotateCcw className="w-3 h-3" />
                <span>Reset Colors</span>
              </button>
            </div>

            <div className="grid grid-cols-2 gap-2 text-xs">
              {/* My Pick */}
              <div
                style={{ backgroundColor: tokens.backgroundSecondary }}
                className="p-2.5 rounded-xl flex items-center justify-between"
              >
                <span style={{ color: tokens.cellNeutralText }}>My Pick</span>
                <input
                  type="color"
                  value={boardColors.myPickHex || (isDark ? '#7E22CE' : '#7E22CE')}
                  onChange={(e) => updateBoardColors({ myPickHex: e.target.value })}
                  className="w-7 h-7 rounded-lg border-0 cursor-pointer bg-transparent"
                />
              </div>

              {/* Opponent Pick */}
              <div
                style={{ backgroundColor: tokens.backgroundSecondary }}
                className="p-2.5 rounded-xl flex items-center justify-between"
              >
                <span style={{ color: tokens.cellNeutralText }}>Opponent</span>
                <input
                  type="color"
                  value={boardColors.opponentPickHex || '#C2410C'}
                  onChange={(e) => updateBoardColors({ opponentPickHex: e.target.value })}
                  className="w-7 h-7 rounded-lg border-0 cursor-pointer bg-transparent"
                />
              </div>

              {/* Recent Pick */}
              <div
                style={{ backgroundColor: tokens.backgroundSecondary }}
                className="p-2.5 rounded-xl flex items-center justify-between"
              >
                <span style={{ color: tokens.cellNeutralText }}>Recent Pick</span>
                <input
                  type="color"
                  value={boardColors.recentPickHex || (isDark ? '#FFFFFF' : '#D9B13D')}
                  onChange={(e) => updateBoardColors({ recentPickHex: e.target.value })}
                  className="w-7 h-7 rounded-lg border-0 cursor-pointer bg-transparent"
                />
              </div>

              {/* Completed Line */}
              <div
                style={{ backgroundColor: tokens.backgroundSecondary }}
                className="p-2.5 rounded-xl flex items-center justify-between"
              >
                <span style={{ color: tokens.cellNeutralText }}>Line Clear</span>
                <input
                  type="color"
                  value={boardColors.completedLineHex || '#64748B'}
                  onChange={(e) => updateBoardColors({ completedLineHex: e.target.value })}
                  className="w-7 h-7 rounded-lg border-0 cursor-pointer bg-transparent"
                />
              </div>
            </div>

            {/* Cell Border Checkbox & Presets */}
            <div className="flex flex-col gap-2 mt-1">
              <label className="flex items-center gap-2 cursor-pointer text-xs font-semibold">
                <input
                  type="checkbox"
                  checked={boardColors.cellBorderEnabled}
                  onChange={(e) => updateBoardColors({ cellBorderEnabled: e.target.checked })}
                  className="w-4 h-4 rounded text-purple-600 focus:ring-purple-500 cursor-pointer"
                />
                <span style={{ color: tokens.cellNeutralText }}>Enable 3D Cell Border</span>
              </label>

              {boardColors.cellBorderEnabled && (
                <div className="flex items-center gap-1.5 pl-6">
                  {BORDER_COLOR_PRESETS.map((bp) => (
                    <button
                      key={bp.hex}
                      type="button"
                      onClick={() => updateBoardColors({ cellBorderHex: bp.hex })}
                      style={{
                        backgroundColor: bp.hex,
                        borderColor: boardColors.cellBorderHex === bp.hex ? '#FFFFFF' : 'transparent'
                      }}
                      className={`w-6 h-6 rounded-lg border-2 shadow-xs cursor-pointer active:scale-95`}
                      title={bp.name}
                    />
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* ── 3. Favorite Emojis Section ── */}
        <div
          style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3"
        >
          <div className="flex items-center justify-between">
            <span style={{ color: tokens.textMuted }} className="text-[11px] font-bold uppercase tracking-wider">
              Favorite In-Game Emojis ({favoriteEmojis.length}/{MAX_FAVORITES})
            </span>
            <button
              type="button"
              onClick={handleResetEmojis}
              style={{ color: tokens.textMuted }}
              className="text-[11px] font-semibold flex items-center gap-1 hover:underline cursor-pointer"
            >
              <RotateCcw className="w-3 h-3" />
              <span>Defaults</span>
            </button>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            {favoriteEmojis.map((emoji) => (
              <div
                key={emoji}
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder
                }}
                className="px-2.5 py-1.5 rounded-xl border flex items-center gap-1.5 text-base shadow-xs"
              >
                <span>{emoji}</span>
                {favoriteEmojis.length > 1 && (
                  <button
                    type="button"
                    onClick={() => handleRemoveFavoriteEmoji(emoji)}
                    className="text-slate-400 hover:text-red-500 cursor-pointer active:scale-90 transition-colors"
                  >
                    <X className="w-3 h-3" />
                  </button>
                )}
              </div>
            ))}

            {favoriteEmojis.length < MAX_FAVORITES && (
              <button
                type="button"
                onClick={() => setShowAddEmojiModal(true)}
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder,
                  color: tokens.cellNeutralText
                }}
                className="px-3 py-1.5 rounded-xl border text-xs font-bold flex items-center gap-1.5 cursor-pointer active:scale-95 hover:border-purple-500"
              >
                <Plus className="w-3.5 h-3.5" />
                <span>Add</span>
              </button>
            )}
          </div>
        </div>

        {/* ── 4. Sound & PWA Controls ── */}
        <div
          style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3"
        >
          {/* Sound Toggle */}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {soundEnabled ? (
                <Volume2 className="w-5 h-5 text-purple-500" />
              ) : (
                <VolumeX className="w-5 h-5 text-slate-400" />
              )}
              <div className="flex flex-col">
                <span style={{ color: tokens.cellNeutralText }} className="text-xs font-bold">
                  Sound Effects
                </span>
                <span style={{ color: tokens.textMuted }} className="text-[11px]">
                  Taps, picks, turn alerts and fanfare
                </span>
              </div>
            </div>

            <button
              type="button"
              onClick={handleToggleSound}
              style={{
                backgroundColor: soundEnabled ? tokens.primaryButtonBg : tokens.backgroundSecondary,
                borderColor: tokens.surfaceBorder
              }}
              className="w-12 h-6 rounded-full border p-0.5 flex items-center transition-colors cursor-pointer"
            >
              <div
                style={{
                  transform: soundEnabled ? 'translateX(24px)' : 'translateX(0)',
                  backgroundColor: soundEnabled ? '#FFFFFF' : '#94A3B8'
                }}
                className="w-5 h-5 rounded-full shadow-xs transition-transform"
              />
            </button>
          </div>

          {/* PWA Install */}
          {!isInstalled && (
            <div className="flex items-center justify-between pt-3 border-t border-inherit">
              <div className="flex items-center gap-3">
                <Download className="w-5 h-5 text-purple-500" />
                <div className="flex flex-col">
                  <span style={{ color: tokens.cellNeutralText }} className="text-xs font-bold">
                    Install Bingo App (PWA)
                  </span>
                  <span style={{ color: tokens.textMuted }} className="text-[11px]">
                    Install on phone or laptop desktop
                  </span>
                </div>
              </div>

              <button
                type="button"
                onClick={handleInstallPwa}
                style={{
                  backgroundColor: tokens.primaryButtonBg,
                  color: tokens.primaryButtonText
                }}
                className="px-3.5 py-1.5 rounded-xl text-xs font-bold cursor-pointer active:scale-95 shadow-xs"
              >
                Install
              </button>
            </div>
          )}
        </div>

        {/* ── 5. Sign Out ── */}
        <div className="pt-2">
          <button
            type="button"
            onClick={() => setShowSignOutDialog(true)}
            style={{
              backgroundColor: tokens.surface,
              borderColor: tokens.surfaceBorder,
              color: '#EF4444'
            }}
            className="w-full py-3 rounded-2xl border font-bold text-xs flex items-center justify-center gap-2 cursor-pointer active:scale-98 shadow-sm hover:bg-red-500/10"
          >
            <LogOut className="w-4 h-4" />
            <span>Sign Out</span>
          </button>
        </div>
      </div>

      {/* ── Edit Nickname Dialog ── */}
      {showEditNameDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full max-w-xs rounded-3xl p-5 border shadow-2xl flex flex-col"
          >
            <h3 style={{ color: tokens.cellNeutralText }} className="font-bold text-base mb-3">
              Edit Display Name
            </h3>
            <form onSubmit={handleSaveNickname} className="flex flex-col gap-3">
              <input
                type="text"
                maxLength={16}
                value={newDisplayName}
                onChange={(e) => setNewDisplayName(e.target.value)}
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder,
                  color: tokens.cellNeutralText
                }}
                className="w-full px-3.5 py-2.5 rounded-xl border text-sm font-semibold focus:outline-none"
                autoFocus
              />
              <div className="flex items-center justify-end gap-2 mt-1">
                <button
                  type="button"
                  onClick={() => setShowEditNameDialog(false)}
                  style={{ color: tokens.textMuted }}
                  className="px-3 py-1.5 rounded-xl text-xs font-semibold cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  style={{
                    backgroundColor: tokens.primaryButtonBg,
                    color: tokens.primaryButtonText
                  }}
                  className="px-4 py-1.5 rounded-xl text-xs font-bold cursor-pointer active:scale-95"
                >
                  Save
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── Change Avatar Modal ── */}
      {showAvatarDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full max-w-sm rounded-3xl p-5 border shadow-2xl flex flex-col"
          >
            <div className="flex items-center justify-between mb-3">
              <h3 style={{ color: tokens.cellNeutralText }} className="font-bold text-base">
                Choose Avatar
              </h3>
              <button
                type="button"
                onClick={() => setShowAvatarDialog(false)}
                style={{ color: tokens.textMuted }}
                className="cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="grid grid-cols-4 gap-2 mb-4">
              {AVATAR_PRESETS.map((preset) => (
                <button
                  key={preset}
                  type="button"
                  onClick={() => handleSelectAvatarPreset(preset)}
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    borderColor: localPlayer.avatarUrl === preset ? tokens.accentBrand : 'transparent'
                  }}
                  className="p-3 rounded-2xl border text-2xl flex items-center justify-center cursor-pointer active:scale-95 transition-all"
                >
                  {preset}
                </button>
              ))}
            </div>

            <form onSubmit={handleSaveCustomAvatarUrl} className="flex flex-col gap-2">
              <span style={{ color: tokens.textMuted }} className="text-[11px] font-semibold">
                Or Photo URL:
              </span>
              <div className="flex gap-2">
                <input
                  type="url"
                  placeholder="https://..."
                  value={customAvatarUrl}
                  onChange={(e) => setCustomAvatarUrl(e.target.value)}
                  style={{
                    backgroundColor: tokens.backgroundSecondary,
                    borderColor: tokens.surfaceBorder,
                    color: tokens.cellNeutralText
                  }}
                  className="flex-1 px-3 py-2 rounded-xl border text-xs focus:outline-none"
                />
                <button
                  type="submit"
                  style={{
                    backgroundColor: tokens.primaryButtonBg,
                    color: tokens.primaryButtonText
                  }}
                  className="px-3 py-2 rounded-xl text-xs font-bold cursor-pointer active:scale-95"
                >
                  Save
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── Add Emoji Modal ── */}
      {showAddEmojiModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full max-w-sm rounded-3xl p-5 border shadow-2xl flex flex-col"
          >
            <div className="flex items-center justify-between mb-3">
              <h3 style={{ color: tokens.cellNeutralText }} className="font-bold text-base">
                Select Reaction Emoji
              </h3>
              <button
                type="button"
                onClick={() => setShowAddEmojiModal(false)}
                style={{ color: tokens.textMuted }}
                className="cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="grid grid-cols-6 gap-2 max-h-60 overflow-y-auto no-scrollbar py-2">
              {ALL_REACTION_EMOJIS.map((emoji) => {
                const isAlready = favoriteEmojis.includes(emoji);
                return (
                  <button
                    key={emoji}
                    type="button"
                    disabled={isAlready}
                    onClick={() => handleAddFavoriteEmoji(emoji)}
                    style={{
                      backgroundColor: isAlready ? 'transparent' : tokens.backgroundSecondary,
                      opacity: isAlready ? 0.3 : 1
                    }}
                    className="p-2 rounded-xl text-xl flex items-center justify-center cursor-pointer active:scale-95 disabled:cursor-not-allowed"
                  >
                    {emoji}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* ── Sign Out Dialog ── */}
      {showSignOutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in">
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full max-w-xs rounded-3xl p-5 border shadow-2xl flex flex-col text-left"
          >
            <h3 style={{ color: tokens.textPrimary }} className="font-bold text-base mb-1">
              Sign Out?
            </h3>
            <p style={{ color: tokens.textSecondary }} className="text-xs mb-4">
              Are you sure you want to sign out of your account on this device?
            </p>
            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => setShowSignOutDialog(false)}
                style={{ color: tokens.textMuted }}
                className="px-3 py-1.5 rounded-xl text-xs font-semibold cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowSignOutDialog(false);
                  onSignOut();
                }}
                className="px-3.5 py-1.5 rounded-xl text-xs font-bold bg-rose-600 text-white cursor-pointer active:scale-95"
              >
                Sign Out
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
