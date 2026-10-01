import React, { useState } from 'react';
import { ArrowLeft, Search, UserPlus, Users, Trophy, Flame, Play, Check, Clock, UserCheck } from 'lucide-react';
import { Player } from '../types/models';
import { CloudRegistry, PlayerRegistryEntry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';
import { PlayerAvatar } from '../components/PlayerAvatar';

interface Props {
  localPlayer: Player;
  onBack: () => void;
  onInvitePlayerToMatch: (friendUsername: string) => void;
}

const STORAGE_KEY_FRIENDS = 'bingo_web_friends_v1';

export const DashboardAndFriendsScreen: React.FC<Props> = ({
  localPlayer,
  onBack,
  onInvitePlayerToMatch
}) => {
  const [activeTab, setActiveTab] = useState<'STATS' | 'FRIENDS'>('FRIENDS');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [isSearching, setIsSearching] = useState<boolean>(false);
  const [searchResult, setSearchResult] = useState<PlayerRegistryEntry | null>(null);
  const [searchMessage, setSearchMessage] = useState<string | null>(null);

  // Saved friends
  const [friendsList, setFriendsList] = useState<PlayerRegistryEntry[]>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_FRIENDS);
      if (raw) return JSON.parse(raw);
    } catch {}
    return [];
  });

  const saveFriends = (newList: PlayerRegistryEntry[]) => {
    setFriendsList(newList);
    try {
      localStorage.setItem(STORAGE_KEY_FRIENDS, JSON.stringify(newList));
    } catch {}
  };

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

  const handleAddFriend = (entry: PlayerRegistryEntry) => {
    soundEffects.playTap();
    if (friendsList.some(f => f.username === entry.username)) return;
    saveFriends([entry, ...friendsList]);
  };

  // Win rate calculation
  const winRate = localPlayer.gamesPlayed > 0
    ? Math.round((localPlayer.gamesWon / localPlayer.gamesPlayed) * 100)
    : 0;

  const rankTitle =
    localPlayer.level >= 8 ? 'Grandmaster' :
    localPlayer.level >= 5 ? 'Gold Master' :
    localPlayer.level >= 3 ? 'Silver Competitor' : 'Bronze Player';

  return (
    <div className="min-h-[100dvh] flex flex-col justify-between max-w-lg md:max-w-2xl lg:max-w-3xl mx-auto p-4 sm:p-6 select-none bg-[#FAFAFC] text-slate-800">
      {/* Header */}
      <header className="flex items-center gap-3 py-2">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            onBack();
          }}
          className="w-10 h-10 rounded-full bg-white border border-slate-200 text-slate-600 hover:text-slate-900 hover:bg-slate-50 flex items-center justify-center transition-all cursor-pointer shadow-sm active:scale-95"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div>
          <h1 className="text-xl sm:text-2xl font-black font-heading tracking-wide text-slate-800">
            Dashboard & Friends
          </h1>
          <p className="text-xs sm:text-sm text-slate-500">Stats, player search & match invites</p>
        </div>
      </header>

      {/* Tab Switcher */}
      <div className="my-3 flex items-center p-1 bg-slate-200/70 rounded-2xl">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setActiveTab('FRIENDS');
          }}
          className={`flex-1 py-2 text-xs font-bold rounded-xl transition-all cursor-pointer ${
            activeTab === 'FRIENDS'
              ? 'bg-white text-[#7C3AED] shadow-sm font-extrabold'
              : 'text-slate-600 hover:text-slate-900'
          }`}
        >
          Friends & Players
        </button>
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setActiveTab('STATS');
          }}
          className={`flex-1 py-2 text-xs font-bold rounded-xl transition-all cursor-pointer ${
            activeTab === 'STATS'
              ? 'bg-white text-[#7C3AED] shadow-sm font-extrabold'
              : 'text-slate-600 hover:text-slate-900'
          }`}
        >
          Overview & Stats
        </button>
      </div>

      {/* Tab Content */}
      <main className="my-auto space-y-4 py-2 flex-1">
        {activeTab === 'STATS' ? (
          <div className="space-y-4 animate-fade-in">
            {/* Player Profile Card */}
            <div className="p-5 rounded-3xl bg-white border border-slate-200 shadow-sm flex items-center gap-4">
              <PlayerAvatar
                avatarUrl={localPlayer.avatarUrl}
                displayName={localPlayer.displayName}
                sizeClassName="w-16 h-16 text-3xl"
                fallbackIcon="🧑"
                className="border-2 border-purple-200 bg-[#F5EEFF] shadow-sm"
              />
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <h2 className="text-lg font-black text-slate-800">{localPlayer.displayName}</h2>
                  <span className="text-[10px] font-extrabold px-2 py-0.5 rounded-full bg-[#F5EEFF] text-[#7C3AED] border border-purple-200">
                    Lvl {localPlayer.level}
                  </span>
                </div>
                <p className="text-xs text-[#7C3AED] font-bold">@{localPlayer.username}</p>
                <p className="text-xs text-slate-500 mt-1 font-medium">{rankTitle}</p>
              </div>
            </div>

            {/* Stats Grid */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <div className="p-4 rounded-2xl bg-white border border-slate-200 shadow-sm">
                <span className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
                  Matches Won
                </span>
                <div className="mt-1 flex items-baseline gap-2">
                  <span className="text-2xl font-black text-slate-800">{localPlayer.gamesWon}</span>
                  <span className="text-xs text-slate-500">/ {localPlayer.gamesPlayed}</span>
                </div>
              </div>

              <div className="p-4 rounded-2xl bg-white border border-slate-200 shadow-sm">
                <span className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
                  Win Rate
                </span>
                <div className="mt-1 flex items-baseline gap-2">
                  <span className="text-2xl font-black text-emerald-600">{winRate}%</span>
                </div>
              </div>

              <div className="p-4 rounded-2xl bg-white border border-slate-200 shadow-sm">
                <span className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
                  Current Streak
                </span>
                <div className="mt-1 flex items-baseline gap-2 text-amber-600">
                  <Flame className="w-5 h-5 fill-current" />
                  <span className="text-2xl font-black text-slate-800">{localPlayer.currentStreak}</span>
                </div>
              </div>

              <div className="p-4 rounded-2xl bg-white border border-slate-200 shadow-sm">
                <span className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
                  Total Lines
                </span>
                <div className="mt-1 flex items-baseline gap-2">
                  <span className="text-2xl font-black text-[#7C3AED]">{localPlayer.completedLinesCount}</span>
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-4 animate-fade-in">
            {/* Search Bar Form */}
            <form onSubmit={handleSearch} className="relative flex items-center">
              <input
                type="text"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                placeholder="Search friend by @username..."
                className="w-full pl-10 pr-24 py-3 rounded-2xl bg-white border border-slate-300 text-slate-800 text-sm font-semibold focus:outline-none focus:border-[#7C3AED] shadow-sm transition-colors"
              />
              <Search className="w-4 h-4 text-slate-400 absolute left-3.5" />
              <button
                type="submit"
                disabled={isSearching}
                className="absolute right-1.5 px-4 py-2 rounded-xl bg-[#7C3AED] hover:bg-[#6D28D9] text-white text-xs font-bold transition-all active:scale-95 cursor-pointer disabled:opacity-50"
              >
                {isSearching ? 'Finding...' : 'Search'}
              </button>
            </form>

            {searchMessage && (
              <p className="text-xs text-center text-slate-500 py-1">{searchMessage}</p>
            )}

            {/* Search Result Card */}
            {searchResult && (
              <div className="p-4 rounded-2xl bg-white border-2 border-purple-200 shadow-sm animate-fade-in">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <PlayerAvatar
                      avatarUrl={searchResult.avatarUrl}
                      displayName={searchResult.displayName}
                      sizeClassName="w-11 h-11 text-xl"
                      fallbackIcon="🧑"
                      className="border border-purple-200 bg-[#F5EEFF]"
                    />
                    <div>
                      <h3 className="text-sm font-black text-slate-800">{searchResult.displayName}</h3>
                      <p className="text-xs text-[#7C3AED] font-bold">@{searchResult.username}</p>
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                        <span className="text-[10px] text-slate-500 font-semibold">
                          Online • Lvl {searchResult.level}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => handleAddFriend(searchResult)}
                      title="Add to friends list"
                      className="p-2 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-700 transition-all cursor-pointer"
                    >
                      <UserPlus className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => onInvitePlayerToMatch(searchResult.username)}
                      className="px-3 py-1.5 rounded-xl bg-[#7C3AED] hover:bg-[#6D28D9] text-white text-xs font-extrabold flex items-center gap-1.5 shadow transition-all active:scale-95 cursor-pointer"
                    >
                      <Play className="w-3.5 h-3.5 fill-current" />
                      <span>Invite</span>
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Saved Friends List */}
            <div className="space-y-2">
              <div className="flex items-center justify-between px-1">
                <span className="text-xs font-extrabold uppercase tracking-wider text-slate-400">
                  Saved Friends ({friendsList.length})
                </span>
              </div>

              {friendsList.length === 0 ? (
                <div className="p-8 rounded-3xl bg-white border border-slate-200 text-center text-slate-400">
                  <Users className="w-8 h-8 mx-auto mb-2 text-slate-300" />
                  <p className="text-xs font-semibold">No friends added yet.</p>
                  <p className="text-[11px] text-slate-400 mt-1">
                    Search your friend's @username above to add them and invite to matches!
                  </p>
                </div>
              ) : (
                friendsList.map(friend => (
                  <div
                    key={friend.username}
                    className="p-3.5 rounded-2xl bg-white border border-slate-200 shadow-sm flex items-center justify-between"
                  >
                    <div className="flex items-center gap-3">
                      <PlayerAvatar
                        avatarUrl={friend.avatarUrl}
                        displayName={friend.displayName}
                        sizeClassName="w-10 h-10 text-lg"
                        fallbackIcon="🧑"
                        className="bg-slate-100"
                      />
                      <div>
                        <h4 className="text-xs font-bold text-slate-800">{friend.displayName}</h4>
                        <p className="text-[11px] text-[#7C3AED] font-semibold">@{friend.username}</p>
                      </div>
                    </div>

                    <button
                      type="button"
                      onClick={() => onInvitePlayerToMatch(friend.username)}
                      className="px-3 py-1.5 rounded-xl bg-[#7C3AED] hover:bg-[#6D28D9] text-white text-xs font-bold flex items-center gap-1 shadow-sm transition-all active:scale-95 cursor-pointer"
                    >
                      <Play className="w-3 h-3 fill-current" />
                      <span>Invite</span>
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        )}
      </main>

      <footer className="py-2 text-center text-[11px] text-slate-400">
        Universal Cross-Play with Android App
      </footer>
    </div>
  );
};
