import React, { useState, useEffect } from 'react';
import { ArrowLeft, Search, UserPlus, Users, Trophy, Flame, Play, Check, Clock, UserCheck } from 'lucide-react';
import { Player } from '../types/models';
import { CloudRegistry, PlayerRegistryEntry } from '../network/cloudRegistry';
import { soundEffects } from '../audio/sounds';

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
    <div className="min-h-screen flex flex-col justify-between max-w-lg mx-auto p-4 select-none">
      {/* Header */}
      <header className="flex items-center gap-3 py-2">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            onBack();
          }}
          className="w-10 h-10 rounded-full bg-slate-900 border border-slate-800 text-slate-300 hover:text-white flex items-center justify-center transition-all cursor-pointer"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div>
          <h1 className="text-xl font-black font-heading tracking-wide text-white">
            Dashboard & Friends
          </h1>
          <p className="text-xs text-slate-400">Stats, player search & match invites</p>
        </div>
      </header>

      {/* Tab Switcher */}
      <div className="grid grid-cols-2 gap-2 p-1.5 bg-slate-900 border border-slate-800 rounded-2xl my-3">
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setActiveTab('FRIENDS');
          }}
          className={`py-2 px-3 rounded-xl text-xs font-bold transition-all cursor-pointer ${
            activeTab === 'FRIENDS'
              ? 'bg-blue-600 text-white shadow-md'
              : 'text-slate-400 hover:text-white'
          }`}
        >
          Friends & Search
        </button>

        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            setActiveTab('STATS');
          }}
          className={`py-2 px-3 rounded-xl text-xs font-bold transition-all cursor-pointer ${
            activeTab === 'STATS'
              ? 'bg-blue-600 text-white shadow-md'
              : 'text-slate-400 hover:text-white'
          }`}
        >
          Stats & Overview
        </button>
      </div>

      {/* Main Tab Content */}
      <main className="flex-1 overflow-y-auto space-y-4 py-2">
        {activeTab === 'FRIENDS' ? (
          <>
            {/* Search Input Box */}
            <form onSubmit={handleSearch} className="flex gap-2">
              <div className="relative flex-1 flex items-center">
                <Search className="absolute left-3.5 w-4 h-4 text-slate-500" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  placeholder="Search by @username..."
                  className="w-full pl-10 pr-4 py-3 rounded-2xl bg-slate-900 border border-slate-800 text-white text-sm font-semibold placeholder:text-slate-500 placeholder:font-normal focus:outline-none focus:border-blue-500 transition-colors"
                />
              </div>
              <button
                type="submit"
                disabled={isSearching}
                className="px-5 py-3 rounded-2xl bg-blue-600 hover:bg-blue-500 active:scale-95 text-white font-bold text-xs uppercase tracking-wider transition-all shadow-md cursor-pointer"
              >
                {isSearching ? '...' : 'Search'}
              </button>
            </form>

            {/* Search Results Display */}
            {searchResult && (
              <div className="p-4 rounded-3xl bg-slate-900 border border-blue-500/40 shadow-xl animate-fade-in">
                <span className="text-[10px] font-extrabold uppercase tracking-widest text-blue-400 block mb-2">
                  Player Found
                </span>

                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-12 h-12 rounded-2xl bg-gradient-to-tr from-purple-600 to-indigo-600 flex items-center justify-center text-2xl shadow">
                      {searchResult.avatarUrl || '👤'}
                    </div>
                    <div>
                      <h3 className="text-sm font-bold text-white">{searchResult.displayName}</h3>
                      <span className="text-xs font-semibold text-blue-400">@{searchResult.username}</span>
                      <div className="flex items-center gap-2 mt-0.5 text-[11px] text-slate-400">
                        <span>Level {searchResult.level}</span>
                        <span>•</span>
                        <span>{searchResult.gamesWon} Wins</span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => handleAddFriend(searchResult)}
                      title="Add to Friends"
                      className="p-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 hover:text-white transition-all cursor-pointer"
                    >
                      {friendsList.some(f => f.username === searchResult.username) ? (
                        <Check className="w-4 h-4 text-emerald-400" />
                      ) : (
                        <UserPlus className="w-4 h-4" />
                      )}
                    </button>

                    <button
                      type="button"
                      onClick={() => onInvitePlayerToMatch(searchResult.username)}
                      className="py-2 px-3 rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 text-white font-bold text-xs flex items-center gap-1.5 shadow-md active:scale-95 transition-all cursor-pointer"
                    >
                      <Play className="w-3.5 h-3.5 fill-current" />
                      <span>Challenge</span>
                    </button>
                  </div>
                </div>
              </div>
            )}

            {searchMessage && (
              <p className="text-xs text-center text-slate-500 py-2">{searchMessage}</p>
            )}

            {/* Friends List */}
            <div className="pt-2 space-y-2">
              <h2 className="text-xs font-extrabold uppercase tracking-wider text-slate-400 px-1">
                Your Friends ({friendsList.length})
              </h2>

              {friendsList.length > 0 ? (
                friendsList.map(friend => (
                  <div
                    key={friend.username}
                    className="flex items-center justify-between p-3 rounded-2xl bg-slate-900 border border-slate-800 shadow"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-full bg-slate-800 flex items-center justify-center text-xl">
                        {friend.avatarUrl || '👤'}
                      </div>
                      <div>
                        <h4 className="text-xs font-bold text-white">{friend.displayName}</h4>
                        <span className="text-[11px] text-slate-400">@{friend.username}</span>
                      </div>
                    </div>

                    <button
                      type="button"
                      onClick={() => onInvitePlayerToMatch(friend.username)}
                      className="py-1.5 px-3 rounded-xl bg-blue-600/20 hover:bg-blue-600/30 text-blue-300 font-bold text-xs flex items-center gap-1 border border-blue-500/30 active:scale-95 transition-all cursor-pointer"
                    >
                      <Play className="w-3 h-3 fill-current" />
                      <span>Invite</span>
                    </button>
                  </div>
                ))
              ) : (
                <div className="p-6 rounded-2xl border border-dashed border-slate-800 text-center text-slate-500 text-xs">
                  No friends added yet. Search players by username above to challenge them!
                </div>
              )}
            </div>
          </>
        ) : (
          /* Stats & Overview Tab matching Android */
          <div className="space-y-4">
            {/* Rank Card */}
            <div className="p-5 rounded-3xl bg-gradient-to-tr from-slate-900 via-slate-900 to-blue-950 border border-blue-500/30 shadow-xl">
              <div className="flex items-center justify-between">
                <div>
                  <span className="text-[10px] font-extrabold uppercase tracking-widest text-blue-400">
                    CURRENT RANK
                  </span>
                  <h3 className="text-2xl font-black font-heading text-white mt-1">{rankTitle}</h3>
                  <span className="text-xs text-slate-400 font-semibold">Level {localPlayer.level}</span>
                </div>
                <div className="w-16 h-16 rounded-2xl bg-gradient-to-tr from-amber-500 to-yellow-300 flex items-center justify-center text-3xl shadow-lg">
                  🏆
                </div>
              </div>
            </div>

            {/* Stats Grid */}
            <div className="grid grid-cols-2 gap-3">
              <div className="p-4 rounded-2xl bg-slate-900 border border-slate-800 shadow">
                <span className="text-xs font-bold text-slate-400 flex items-center gap-1.5">
                  <Trophy className="w-4 h-4 text-amber-400" /> Games Won
                </span>
                <span className="text-2xl font-black text-white block mt-1">
                  {localPlayer.gamesWon} / {localPlayer.gamesPlayed}
                </span>
              </div>

              <div className="p-4 rounded-2xl bg-slate-900 border border-slate-800 shadow">
                <span className="text-xs font-bold text-slate-400 flex items-center gap-1.5">
                  <Flame className="w-4 h-4 text-rose-400" /> Win Streak
                </span>
                <span className="text-2xl font-black text-rose-400 block mt-1">
                  {localPlayer.currentStreak} 🔥
                </span>
              </div>

              <div className="p-4 rounded-2xl bg-slate-900 border border-slate-800 shadow">
                <span className="text-xs font-bold text-slate-400">Win Rate</span>
                <span className="text-2xl font-black text-blue-400 block mt-1">{winRate}%</span>
              </div>

              <div className="p-4 rounded-2xl bg-slate-900 border border-slate-800 shadow">
                <span className="text-xs font-bold text-slate-400">Universal ID</span>
                <span className="text-xs font-bold text-emerald-400 block mt-2 truncate">
                  @{localPlayer.username}
                </span>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
};
