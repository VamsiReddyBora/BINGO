import React, { useState, useEffect } from 'react';
import { Wifi, Sun, Moon, Bot, Cast, ArrowRight, Download } from 'lucide-react';
import { Player } from '../types/models';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';
import { PlayerAvatar } from '../components/PlayerAvatar';
import { DeveloperNoteModal } from '../components/DeveloperNoteModal';
import { NearbyNetworkModal } from '../components/NearbyNetworkModal';
import { useRealtimePing } from '../hooks/useRealtimePing';

interface OngoingMatch {
  roomCode: string;
  isHost: boolean;
  timestamp: number;
}

interface Props {
  localPlayer: Player;
  pingMs: number;
  ongoingMatch?: OngoingMatch | null;
  onDismissOngoingMatch?: () => void;
  onRejoinOngoingMatch?: () => void;
  onPlayAi: (difficulty: 'EASY' | 'HARD') => void;
  onPlayOnline: () => void;
  onNavigateToSettings: () => void;
}

export const MainMenuScreen: React.FC<Props> = ({
  localPlayer,
  pingMs,
  ongoingMatch,
  onDismissOngoingMatch,
  onRejoinOngoingMatch,
  onPlayAi,
  onPlayOnline,
  onNavigateToSettings
}) => {
  const { tokens, isDark, toggleTheme } = useTheme();

  const [selectedDifficulty, setSelectedDifficulty] = useState<'EASY' | 'HARD'>('EASY');
  const [showDeveloperNote, setShowDeveloperNote] = useState(false);
  const [showNearbyModal, setShowNearbyModal] = useState(false);

  // PWA install prompt handler
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [isInstalled, setIsInstalled] = useState(false);

  useEffect(() => {
    if (window.matchMedia('(display-mode: standalone)').matches) {
      setIsInstalled(true);
    }
    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e);
    };
    window.addEventListener('beforeinstallprompt', handler);
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  const handleInstall = async () => {
    soundEffects.playTap();
    if (deferredPrompt) {
      deferredPrompt.prompt();
      const { outcome } = await deferredPrompt.userChoice;
      if (outcome === 'accepted') {
        setIsInstalled(true);
      }
      setDeferredPrompt(null);
    } else {
      alert(
        'To install Bingo on your device, tap "Install App" in your browser menu (or Share -> Add to Home Screen on iOS Safari).'
      );
    }
  };

  // 1-second real-time network ping monitor directly matching Android NetworkPingMonitor
  const realtimePing = useRealtimePing();
  const displayedPing = realtimePing > 0 ? realtimePing : (pingMs > 0 ? pingMs : 28);

  const pingColor =
    displayedPing <= 250
      ? '#16A34A'
      : displayedPing <= 500
      ? '#EAB308'
      : '#DC2626';

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="w-full flex-1 flex flex-col justify-between p-4 sm:p-5 select-none transition-colors duration-300 pb-24"
    >
      <div>
        {/* ── Top Bar ── */}
        <div className="flex items-center justify-between py-1">
          <div className="flex items-center">
            <h1
              style={{ color: tokens.cellNeutralText }}
              className="text-2xl font-black font-heading tracking-widest leading-none"
            >
              B I N G O
            </h1>
          </div>

          <div className="flex items-center gap-2.5">
            {/* 1-Second Stabilized Ping Indicator */}
            <div className="flex items-center gap-1.5 px-2 py-1">
              <Wifi
                style={{ color: tokens.cellNeutralText }}
                className="w-4 h-4 opacity-80"
              />
              <span
                style={{ color: pingColor }}
                className="text-xs font-bold font-mono"
              >
                {displayedPing > 999 ? '999+ms' : `${displayedPing}ms`}
              </span>
            </div>

            {/* PWA Install Button (shown when not already standalone) */}
            {!isInstalled && (
              <button
                type="button"
                onClick={handleInstall}
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder,
                  color: tokens.accentBrand
                }}
                title="Install Bingo App (PWA)"
                className="w-9 h-9 rounded-full border flex items-center justify-center transition-all cursor-pointer active:scale-95 shadow-xs hover:opacity-90"
              >
                <Download className="w-4 h-4" />
              </button>
            )}

            {/* Quick Theme Toggle */}
            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                toggleTheme();
              }}
              style={{
                backgroundColor: tokens.backgroundSecondary,
                borderColor: tokens.surfaceBorder
              }}
              title={isDark ? 'Switch to Light Theme' : 'Switch to Dark Theme'}
              className="w-9 h-9 rounded-full border flex items-center justify-center transition-all cursor-pointer active:scale-95 shadow-xs"
            >
              {isDark ? (
                <Sun className="w-4 h-4 text-amber-400" />
              ) : (
                <Moon className="w-4 h-4 text-purple-600" />
              )}
            </button>

            {/* Profile Avatar Button -> Navigates to Settings */}
            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                onNavigateToSettings();
              }}
              title="Profile & Settings"
              className="cursor-pointer active:scale-95 transition-transform"
            >
              <PlayerAvatar
                avatarUrl={localPlayer.avatarUrl}
                displayName={localPlayer.displayName}
                username={localPlayer.username}
                size={36}
                borderWidth={1.5}
                borderColor={tokens.accentBrand}
              />
            </button>
          </div>
        </div>

        {/* ── Ongoing Match Card (Rejoin) ── */}
        {ongoingMatch && (
          <div
            style={{
              backgroundColor: isDark ? '#1E242B' : '#EFF6FF',
              borderColor: isDark ? 'rgba(37, 99, 235, 0.6)' : '#93C5FD'
            }}
            className="w-full mt-4 p-3.5 rounded-2xl border shadow-sm flex flex-col gap-3 animate-fade-in"
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                <span className="text-[11px] font-bold uppercase tracking-wider text-emerald-500">
                  MATCH IN PROGRESS
                </span>
              </div>
              <span
                style={{ color: tokens.textPrimary }}
                className="text-sm font-bold font-mono"
              >
                Room {ongoingMatch.roomCode}
              </span>
            </div>

            <div className="flex items-center gap-2.5">
              <button
                type="button"
                onClick={onDismissOngoingMatch}
                style={{
                  borderColor: tokens.surfaceBorder,
                  color: tokens.textSecondary
                }}
                className="flex-1 h-9 rounded-xl border text-xs font-semibold hover:opacity-80 active:scale-98 transition-all cursor-pointer"
              >
                Dismiss
              </button>
              <button
                type="button"
                onClick={onRejoinOngoingMatch}
                className="flex-1 h-9 rounded-xl bg-blue-600 hover:bg-blue-700 text-white text-xs font-bold shadow-sm active:scale-98 transition-all cursor-pointer"
              >
                Rejoin
              </button>
            </div>
          </div>
        )}

        {/* ── Section Header ── */}
        <div className="mt-5 mb-2.5">
          <span
            style={{ color: tokens.textMuted }}
            className="text-[11px] font-bold tracking-wider uppercase opacity-80"
          >
            SELECT MODE
          </span>
        </div>

        {/* ── 1. Play vs AI Card ── */}
        <div
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-4 border shadow-sm flex flex-col gap-3 mb-3 transition-colors duration-300"
        >
          <div className="flex items-center gap-3.5">
            <Bot
              style={{ color: isDark ? '#FFFFFF' : tokens.accentBrand }}
              className="w-7 h-7 flex-shrink-0"
            />
            <div className="flex flex-col">
              <span
                style={{ color: tokens.cellNeutralText }}
                className="text-sm sm:text-base font-bold"
              >
                Play vs AI
              </span>
              <span style={{ color: tokens.textMuted }} className="text-xs">
                Solo practice match with bot
              </span>
            </div>
          </div>

          {/* Segmented Difficulty Toggle */}
          <div
            style={{ backgroundColor: tokens.backgroundSecondary }}
            className="w-full p-1 rounded-xl flex items-center gap-1 border border-inherit"
          >
            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                setSelectedDifficulty('EASY');
              }}
              style={{
                backgroundColor:
                  selectedDifficulty === 'EASY'
                    ? isDark
                      ? '#222222'
                      : tokens.surface
                    : 'transparent',
                borderColor:
                  selectedDifficulty === 'EASY'
                    ? isDark
                      ? '#FFFFFF'
                      : tokens.accentBrand
                    : 'transparent',
                color:
                  selectedDifficulty === 'EASY'
                    ? tokens.cellNeutralText
                    : tokens.textMuted
              }}
              className="flex-1 py-1.5 rounded-lg text-xs font-bold border transition-all cursor-pointer text-center"
            >
              Easy Bot
            </button>
            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                setSelectedDifficulty('HARD');
              }}
              style={{
                backgroundColor:
                  selectedDifficulty === 'HARD'
                    ? isDark
                      ? '#222222'
                      : tokens.surface
                    : 'transparent',
                borderColor:
                  selectedDifficulty === 'HARD'
                    ? isDark
                      ? '#FFFFFF'
                      : tokens.accentBrand
                    : 'transparent',
                color:
                  selectedDifficulty === 'HARD'
                    ? tokens.cellNeutralText
                    : tokens.textMuted
              }}
              className="flex-1 py-1.5 rounded-lg text-xs font-bold border transition-all cursor-pointer text-center"
            >
              Master Bot
            </button>
          </div>

          {/* Start AI Match Button */}
          <button
            type="button"
            onClick={() => {
              soundEffects.playTap();
              onPlayAi(selectedDifficulty);
            }}
            style={{
              backgroundColor: tokens.primaryButtonBg,
              color: tokens.primaryButtonText
            }}
            className="w-full h-10 rounded-xl font-bold text-xs sm:text-sm flex items-center justify-center transition-all cursor-pointer active:scale-98 shadow-sm"
          >
            Start AI Game ({selectedDifficulty === 'EASY' ? 'Easy' : 'Master'})
          </button>
        </div>

        {/* ── 2. Online Match Card ── */}
        <div
          onClick={() => {
            soundEffects.playTap();
            onPlayOnline();
          }}
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-4 border shadow-sm flex items-center gap-3.5 mb-3 cursor-pointer hover:border-purple-400 active:scale-98 transition-all"
        >
          <Cast
            style={{ color: isDark ? '#FFFFFF' : tokens.accentOpponent }}
            className="w-7 h-7 flex-shrink-0"
          />
          <div className="flex flex-col flex-1">
            <span
              style={{ color: tokens.cellNeutralText }}
              className="text-sm sm:text-base font-bold"
            >
              Online Match
            </span>
            <span style={{ color: tokens.textMuted }} className="text-xs">
              Host or join multiplayer rooms with friends.
            </span>
          </div>
        </div>

        {/* ── 3. Nearby Network Card ── */}
        <div
          onClick={() => {
            soundEffects.playTap();
            setShowNearbyModal(true);
          }}
          style={{
            backgroundColor: tokens.surface,
            borderColor: tokens.surfaceBorder
          }}
          className="w-full rounded-2xl p-4 border shadow-sm flex items-center gap-3.5 mb-4 cursor-pointer hover:border-orange-400 active:scale-98 transition-all"
        >
          <Wifi
            style={{ color: isDark ? '#FFFFFF' : tokens.accentOrange }}
            className="w-7 h-7 flex-shrink-0"
          />
          <div className="flex flex-col flex-1">
            <span
              style={{ color: tokens.cellNeutralText }}
              className="text-sm sm:text-base font-bold"
            >
              Nearby Network
            </span>
            <span style={{ color: tokens.textMuted }} className="text-xs">
              Zero-latency Wi-Fi & Hotspot peer discovery.
            </span>
          </div>
        </div>
      </div>

      {/* ── Centered Developer Note Pill Button ── */}
      <div className="flex justify-center my-4">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setShowDeveloperNote(true);
          }}
          style={{
            backgroundColor: isDark ? '#18181B' : '#F1F5F9',
            borderColor: tokens.surfaceBorder,
            color: tokens.cellNeutralText
          }}
          className="px-5 py-2.5 rounded-full border text-xs sm:text-sm font-medium hover:opacity-85 active:scale-95 transition-all cursor-pointer shadow-xs"
        >
          developer note ☕
        </button>
      </div>

      {/* Modals */}
      <DeveloperNoteModal
        isOpen={showDeveloperNote}
        onClose={() => setShowDeveloperNote(false)}
        username={localPlayer.username}
      />

      <NearbyNetworkModal
        isOpen={showNearbyModal}
        onClose={() => setShowNearbyModal(false)}
        onPlayOnline={onPlayOnline}
      />
    </div>
  );
};
