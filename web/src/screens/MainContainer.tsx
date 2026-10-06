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
  selectedTab?: number;
  onSelectTab?: (tab: number) => void;
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
  selectedTab: controlledTab,
  onSelectTab,
  onDismissOngoingMatch,
  onRejoinOngoingMatch,
  onPlayAi,
  onPlayOnline,
  onInvitePlayerToMatch,
  onUpdatePlayer,
  onSignOut
}) => {
  const { tokens, isDark } = useTheme();
  const [internalTab, setInternalTab] = useState<number>(0);

  const activeTab = controlledTab !== undefined ? controlledTab : internalTab;
  const setActiveTab = onSelectTab || setInternalTab;

  return (
    <div
      style={{ backgroundColor: tokens.background }}
      className="min-h-[100dvh] w-full flex flex-col relative transition-colors duration-300"
    >
      {/* ── Active Tab Page ── */}
      <div className="flex-1 flex flex-col">
        {activeTab === 0 && (
          <MainMenuScreen
            localPlayer={localPlayer}
            pingMs={pingMs}
            ongoingMatch={ongoingMatch}
            onDismissOngoingMatch={onDismissOngoingMatch}
            onRejoinOngoingMatch={onRejoinOngoingMatch}
            onPlayAi={onPlayAi}
            onPlayOnline={onPlayOnline}
            onNavigateToSettings={() => setActiveTab(2)}
          />
        )}

        {activeTab === 1 && (
          <DashboardAndFriendsScreen
            localPlayer={localPlayer}
            onBack={() => setActiveTab(0)}
            onInvitePlayerToMatch={onInvitePlayerToMatch}
          />
        )}

        {activeTab === 2 && (
          <SettingsScreen
            localPlayer={localPlayer}
            onUpdatePlayer={onUpdatePlayer}
            onSignOut={onSignOut}
            onBack={() => setActiveTab(0)}
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
            const isSelected = activeTab === item.id;
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
                  setActiveTab(item.id);
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
