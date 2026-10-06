import React, { useState } from 'react';
import { Home, BarChart2, Settings as SettingsIcon } from 'lucide-react';
import { Player } from '../types/models';
import { useTheme } from '../theme/theme';
import { soundEffects } from '../audio/sounds';
import { MainMenuScreen } from './MainMenuScreen';
import { DashboardAndFriendsScreen } from './DashboardAndFriendsScreen';
import { SettingsScreen } from './SettingsScreen';

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
  onInvitePlayerToMatch: (friendUsername: string) => void;
  onUpdatePlayer: (updated: Player) => void;
  onSignOut: () => void;
}

const NAV_ITEMS = [
  { id: 0, title: 'Home', icon: Home },
  { id: 1, title: 'Dashboard', icon: BarChart2 },
  { id: 2, title: 'Settings', icon: SettingsIcon }
];

export const MainContainer: React.FC<Props> = ({
  localPlayer,
  pingMs,
  ongoingMatch,
  onDismissOngoingMatch,
  onRejoinOngoingMatch,
  onPlayAi,
  onPlayOnline,
  onInvitePlayerToMatch,
  onUpdatePlayer,
  onSignOut
}) => {
  const { tokens, isDark } = useTheme();
  const [selectedTab, setSelectedTab] = useState<number>(0);

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="min-h-[100dvh] w-full flex flex-col relative transition-colors duration-300"
    >
      {/* ── Active Tab Page ── */}
      <div className="flex-1 flex flex-col">
        {selectedTab === 0 && (
          <MainMenuScreen
            localPlayer={localPlayer}
            pingMs={pingMs}
            ongoingMatch={ongoingMatch}
            onDismissOngoingMatch={onDismissOngoingMatch}
            onRejoinOngoingMatch={onRejoinOngoingMatch}
            onPlayAi={onPlayAi}
            onPlayOnline={onPlayOnline}
            onNavigateToSettings={() => setSelectedTab(2)}
          />
        )}

        {selectedTab === 1 && (
          <DashboardAndFriendsScreen
            localPlayer={localPlayer}
            onBack={() => setSelectedTab(0)}
            onInvitePlayerToMatch={onInvitePlayerToMatch}
          />
        )}

        {selectedTab === 2 && (
          <SettingsScreen
            localPlayer={localPlayer}
            onUpdatePlayer={onUpdatePlayer}
            onSignOut={onSignOut}
            onBack={() => setSelectedTab(0)}
          />
        )}
      </div>

      {/* ── Floating Bottom Navigation Pill matching Android MainContainerScreen.kt ── */}
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-40 select-none">
        <div
          style={{
            backgroundColor: isDark ? '#141414' : '#FFFFFF',
            borderColor: isDark ? '#282828' : '#E2E8F0',
            boxShadow: isDark
              ? '0 16px 32px rgba(255, 255, 255, 0.08), 0 4px 16px rgba(0, 0, 0, 0.5)'
              : '0 10px 25px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.06)'
          }}
          className="p-1.5 rounded-full border flex items-center gap-1 backdrop-blur-md"
        >
          {NAV_ITEMS.map((item) => {
            const isSelected = selectedTab === item.id;
            const IconComponent = item.icon;

            const activeBg = isDark
              ? '#262626'
              : `${tokens.accentBrand}18`;
            const activeColor = isDark ? '#FFFFFF' : tokens.accentBrand;
            const inactiveColor = tokens.textMuted;

            return (
              <button
                key={item.id}
                type="button"
                onClick={() => {
                  soundEffects.playTap();
                  setSelectedTab(item.id);
                }}
                style={{
                  backgroundColor: isSelected ? activeBg : 'transparent',
                  color: isSelected ? activeColor : inactiveColor
                }}
                className="px-4 py-2 rounded-full flex items-center gap-1.5 transition-all duration-200 cursor-pointer active:scale-95"
              >
                <IconComponent className="w-4 h-4" />
                <span
                  className={`text-xs ${
                    isSelected ? 'font-bold' : 'font-medium'
                  }`}
                >
                  {item.title}
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};
