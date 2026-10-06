import React, { useState, useEffect } from 'react';
import {
  ArrowLeft,
  Search,
  UserPlus,
  Trophy,
  Flame,
  Award,
  Clock,
  CheckCircle2,
  Share2
} from 'lucide-react';
import { Player, MatchRecord } from '../types/models';
import { CloudRegistry, PlayerRegistryEntry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { useTheme } from '../theme/theme';
import { PlayerAvatar } from '../components/PlayerAvatar';

interface Props {
  localPlayer: Player;
  onBack: () => void;
  onInvitePlayerToMatch: (friendUsername: string) => void;
}

const STORAGE_KEY_FRIENDS = 'bingo_web_friends_v1';
const STORAGE_KEY_MATCHES = 'bingo_web_match_history_v1';

export const DashboardAndFriendsScreen: React.FC<Props> = ({
  localPlayer,
  onBack,
  onInvitePlayerToMatch
}) => {
  const { tokens, isDark } = useTheme();

  const [activeTab, setActiveTab] = useState<'DASHBOARD' | 'FRIENDS'>('DASHBOARD');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [isSearching, setIsSearching] = useState<boolean>(false);
  const [searchResult, setSearchResult] = useState<PlayerRegistryEntry | null>(null);
  const [searchMessage, setSearchMessage] = useState<string | null>(null);

  // Match history
  const [matchHistory, setMatchHistory] = useState<MatchRecord[]>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_MATCHES);
      if (raw) return JSON.parse(raw);
    } catch {}
    return [];
  });

  // Saved friends
  const [friendsList, setFriendsList] = useState<PlayerRegistryEntry[]>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_FRIENDS);
      if (raw) return JSON.parse(raw);
    } catch {}
    return [];
  });

  // Sync friends with cloud registry
  useEffect(() => {
    CloudRegistry.fetchCloudFriends(localPlayer.username).then((cloudFriends) => {
      if (cloudFriends && cloudFriends.length > 0) {
        setFriendsList((prev) => {
          const merged = [...cloudFriends];
          prev.forEach((p) => {
            if (!merged.some((m) => m.username === p.username)) {
              merged.push(p);
            }
          });
          try {
            localStorage.setItem(STORAGE_KEY_FRIENDS, JSON.stringify(merged));
          } catch {}
          return merged;
        });
      }
    });
  }, [localPlayer.username]);

  const handleSearch = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    soundEffects.playTap();
    const clean = searchQuery.trim().toLowerCase().replace(/^@/, '');
    if (!clean) return;

    setIsSearching(true);
    setSearchMessage(null);
    setSearchResult(null);

    const result = await CloudRegistry.searchPlayerByUsername(clean);
    setIsSearching(false);

    if (result) {
      setSearchResult(result);
    } else {
      setSearchMessage(`No player found with username '@${clean}'.`);
    }
  };

  const handleAddFriend = async (entry: PlayerRegistryEntry) => {
    soundEffects.playTap();
    if (friendsList.some((f) => f.username === entry.username)) return;
    const updated = [entry, ...friendsList];
    setFriendsList(updated);
    try {
      localStorage.setItem(STORAGE_KEY_FRIENDS, JSON.stringify(updated));
    } catch {}
    CloudRegistry.addFriendToCloudList(localPlayer.username, entry).catch(() => {});
  };

  const winRate =
    localPlayer.gamesPlayed > 0
      ? Math.round((localPlayer.gamesWon / localPlayer.gamesPlayed) * 100)
      : 0;

  const rankTitle =
    localPlayer.level >= 8
      ? 'Grandmaster'
      : localPlayer.level >= 5
      ? 'Champion'
      : localPlayer.level >= 3
      ? 'Pro'
      : 'Novice';

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
          Dashboard & Friends
        </h1>
      </div>

      {/* ── Profile Banner Card ── */}
      <div
        style={{
          backgroundColor: tokens.surface,
          borderColor: tokens.surfaceBorder
        }}
        className="w-full rounded-2xl p-5 border shadow-sm flex items-center gap-4 mt-3 transition-colors"
      >
        <PlayerAvatar
          avatarUrl={localPlayer.avatarUrl}
          displayName={localPlayer.displayName}
          username={localPlayer.username}
          size={52}
          borderWidth={2}
          borderColor={tokens.accentBrand}
        />

        <div className="flex flex-col flex-1 min-w-0">
          <div className="flex items-center justify-between">
            <span
              style={{ color: tokens.cellNeutralText }}
              className="text-base font-bold truncate"
            >
              {localPlayer.displayName}
            </span>
            <span
              style={{
                backgroundColor: `${tokens.accentBrand}22`,
                color: tokens.accentBrand
              }}
              className="text-[10px] font-extrabold uppercase px-2 py-0.5 rounded-full"
            >
              {rankTitle}
            </span>
          </div>

          <span style={{ color: tokens.textMuted }} className="text-xs font-mono">
            @{localPlayer.username}
          </span>

          {/* Level & XP */}
          <div className="mt-2 flex items-center gap-2">
            <span
              style={{ color: tokens.textSecondary }}
              className="text-[11px] font-bold"
            >
              Lvl {localPlayer.level}
            </span>
            <div
              style={{ backgroundColor: tokens.backgroundSecondary }}
              className="flex-1 h-2 rounded-full overflow-hidden border border-inherit"
            >
              <div
                style={{
                  width: `${Math.min(100, (localPlayer.gamesWon % 5) * 20 + 20)}%`,
                  backgroundColor: tokens.accentBrand
                }}
                className="h-full rounded-full transition-all duration-500"
              />
            </div>
          </div>
        </div>
      </div>

      {/* ── Segmented Navigation Tabs ── */}
      <div
        style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
        className="w-full p-1 rounded-xl flex items-center border mt-4 mb-3"
      >
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setActiveTab('DASHBOARD');
          }}
          style={{
            backgroundColor:
              activeTab === 'DASHBOARD'
                ? isDark
                  ? '#222222'
                  : tokens.backgroundSecondary
                : 'transparent',
            color:
              activeTab === 'DASHBOARD'
                ? tokens.cellNeutralText
                : tokens.textMuted
          }}
          className="flex-1 py-2 rounded-lg text-xs font-bold transition-all cursor-pointer text-center"
        >
          Stats & History
        </button>

        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setActiveTab('FRIENDS');
          }}
          style={{
            backgroundColor:
              activeTab === 'FRIENDS'
                ? isDark
                  ? '#222222'
                  : tokens.backgroundSecondary
                : 'transparent',
            color:
              activeTab === 'FRIENDS'
                ? tokens.cellNeutralText
                : tokens.textMuted
          }}
          className="flex-1 py-2 rounded-lg text-xs font-bold transition-all cursor-pointer text-center"
        >
          Friends ({friendsList.length})
        </button>
      </div>

      {/* ── TAB 1: Stats & History ── */}
      {activeTab === 'DASHBOARD' ? (
        <div className="flex flex-col gap-3">
          {/* Stats Grid */}
          <div className="grid grid-cols-2 gap-2.5">
            <div
              style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
              className="p-3.5 rounded-2xl border shadow-xs flex flex-col"
            >
              <span style={{ color: tokens.textMuted }} className="text-[11px] font-bold">
                Games Played
              </span>
              <span
                style={{ color: tokens.cellNeutralText }}
                className="text-2xl font-black font-mono mt-1"
              >
                {localPlayer.gamesPlayed}
              </span>
            </div>

            <div
              style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
              className="p-3.5 rounded-2xl border shadow-xs flex flex-col"
            >
              <div className="flex items-center justify-between">
                <span style={{ color: tokens.textMuted }} className="text-[11px] font-bold">
                  Wins
                </span>
                <Trophy className="w-3.5 h-3.5 text-amber-500" />
              </div>
              <span
                style={{ color: tokens.cellNeutralText }}
                className="text-2xl font-black font-mono mt-1"
              >
                {localPlayer.gamesWon}
              </span>
            </div>

            <div
              style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
              className="p-3.5 rounded-2xl border shadow-xs flex flex-col"
            >
              <span style={{ color: tokens.textMuted }} className="text-[11px] font-bold">
                Win Rate
              </span>
              <span
                style={{ color: tokens.cellNeutralText }}
                className="text-2xl font-black font-mono mt-1"
              >
                {winRate}%
              </span>
            </div>

            <div
              style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
              className="p-3.5 rounded-2xl border shadow-xs flex flex-col"
            >
              <div className="flex items-center justify-between">
                <span style={{ color: tokens.textMuted }} className="text-[11px] font-bold">
                  Streak
                </span>
                <Flame className="w-3.5 h-3.5 text-orange-500" />
              </div>
              <span
                style={{ color: tokens.cellNeutralText }}
                className="text-2xl font-black font-mono mt-1"
              >
                {localPlayer.currentStreak || 0}
              </span>
            </div>
          </div>

          {/* Match History */}
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full rounded-2xl p-4 border shadow-sm flex flex-col mt-2"
          >
            <span
              style={{ color: tokens.textMuted }}
              className="text-[11px] font-bold uppercase tracking-wider mb-2"
            >
              Recent Matches
            </span>

            {matchHistory.length === 0 ? (
              <div className="py-6 text-center">
                <p style={{ color: tokens.textMuted }} className="text-xs">
                  No matches recorded yet. Play a match to start your record!
                </p>
              </div>
            ) : (
              <div className="divide-y divide-inherit">
                {matchHistory.slice(0, 10).map((record) => (
                  <div key={record.id} className="py-2.5 flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span
                        className={`text-[10px] font-black px-2 py-0.5 rounded-md ${
                          record.didWin
                            ? 'bg-emerald-500/20 text-emerald-500'
                            : 'bg-rose-500/20 text-rose-500'
                        }`}
                      >
                        {record.didWin ? 'WIN' : 'LOSS'}
                      </span>
                      <span style={{ color: tokens.cellNeutralText }} className="text-xs font-bold">
                        vs {record.opponentName}
                      </span>
                    </div>
                    <span style={{ color: tokens.textMuted }} className="text-[10px] font-mono">
                      {new Date(record.timestamp).toLocaleDateString()}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      ) : (
        /* ── TAB 2: Friends & Search ── */
        <div className="flex flex-col gap-3">
          {/* Search Bar */}
          <form onSubmit={handleSearch} className="flex gap-2">
            <div className="relative flex-1">
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search by Player ID (@username)..."
                style={{
                  backgroundColor: tokens.surface,
                  borderColor: tokens.surfaceBorder,
                  color: tokens.cellNeutralText
                }}
                className="w-full pl-3.5 pr-9 py-2.5 rounded-xl border text-xs focus:outline-none focus:ring-1 focus:ring-purple-500 transition-colors"
              />
              <Search className="w-4 h-4 text-slate-400 absolute right-3 top-3 pointer-events-none" />
            </div>

            <button
              type="submit"
              disabled={isSearching || !searchQuery.trim()}
              style={{
                backgroundColor: tokens.primaryButtonBg,
                color: tokens.primaryButtonText
              }}
              className="px-4 py-2.5 rounded-xl text-xs font-bold flex items-center justify-center cursor-pointer active:scale-95 disabled:opacity-50"
            >
              {isSearching ? '...' : 'Search'}
            </button>
          </form>

          {/* Search Result Card */}
          {searchResult && (
            <div
              style={{
                backgroundColor: tokens.surface,
                borderColor: tokens.accentBrand
              }}
              className="p-3.5 rounded-2xl border shadow-sm flex items-center justify-between animate-fade-in"
            >
              <div className="flex items-center gap-3">
                <PlayerAvatar
                  avatarUrl={searchResult.avatarUrl}
                  displayName={searchResult.displayName}
                  username={searchResult.username}
                  size={36}
                />
                <div className="flex flex-col">
                  <span style={{ color: tokens.cellNeutralText }} className="text-xs font-bold">
                    {searchResult.displayName}
                  </span>
                  <span style={{ color: tokens.textMuted }} className="text-[10px] font-mono">
                    @{searchResult.username}
                  </span>
                </div>
              </div>

              <button
                type="button"
                onClick={() => handleAddFriend(searchResult)}
                style={{
                  backgroundColor: tokens.backgroundSecondary,
                  borderColor: tokens.surfaceBorder,
                  color: tokens.cellNeutralText
                }}
                className="px-3 py-1.5 rounded-xl border text-xs font-bold flex items-center gap-1.5 cursor-pointer active:scale-95 hover:border-purple-400"
              >
                <UserPlus className="w-3.5 h-3.5" />
                <span>Add Friend</span>
              </button>
            </div>
          )}

          {searchMessage && (
            <p style={{ color: tokens.textMuted }} className="text-xs text-center py-2">
              {searchMessage}
            </p>
          )}

          {/* Friends List */}
          <div
            style={{ backgroundColor: tokens.surface, borderColor: tokens.surfaceBorder }}
            className="w-full rounded-2xl p-4 border shadow-sm flex flex-col mt-1"
          >
            <span
              style={{ color: tokens.textMuted }}
              className="text-[11px] font-bold uppercase tracking-wider mb-2"
            >
              All Friends ({friendsList.length})
            </span>

            {friendsList.length === 0 ? (
              <div className="py-6 text-center">
                <p style={{ color: tokens.textMuted }} className="text-xs">
                  You haven't added any friends yet. Search above to add!
                </p>
              </div>
            ) : (
              <div className="divide-y divide-inherit">
                {friendsList.map((friend) => (
                  <div
                    key={friend.username}
                    className="py-2.5 flex items-center justify-between"
                  >
                    <div className="flex items-center gap-3">
                      <div className="relative">
                        <PlayerAvatar
                          avatarUrl={friend.avatarUrl}
                          displayName={friend.displayName}
                          username={friend.username}
                          size={36}
                        />
                        <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 border-2 border-white dark:border-black absolute -bottom-0.5 -right-0.5" />
                      </div>

                      <div className="flex flex-col">
                        <span
                          style={{ color: tokens.cellNeutralText }}
                          className="text-xs font-bold"
                        >
                          {friend.displayName}
                        </span>
                        <span
                          style={{ color: tokens.textMuted }}
                          className="text-[10px] font-mono"
                        >
                          @{friend.username}
                        </span>
                      </div>
                    </div>

                    <button
                      type="button"
                      onClick={() => onInvitePlayerToMatch(friend.username)}
                      style={{
                        backgroundColor: tokens.primaryButtonBg,
                        color: tokens.primaryButtonText
                      }}
                      className="px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 cursor-pointer active:scale-95 shadow-xs"
                    >
                      <span>Invite</span>
                      <Share2 className="w-3 h-3" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
