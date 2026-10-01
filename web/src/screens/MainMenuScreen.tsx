import React, { useState, useEffect } from 'react';
import { Users, Bot, KeyRound, Sparkles, Trophy, Flame, Edit3, Check } from 'lucide-react';
import { Player } from '../types/models';
import { soundEffects } from '../audio/sounds';

interface Props {
  localPlayer: Player;
  onUpdatePlayer: (updated: Player) => void;
  onCreateRoom: () => void;
  onJoinRoom: (code: string) => void;
  onPlayAi: () => void;
  initialRoomCode?: string | null;
}

const AVATARS = ['🧑', '😎', '🐱', '🦊', '🦁', '🐼', '🤖', '👾', '🚀', '⭐', '🔥', '👑'];

export const MainMenuScreen: React.FC<Props> = ({
  localPlayer,
  onUpdatePlayer,
  onCreateRoom,
  onJoinRoom,
  onPlayAi,
  initialRoomCode
}) => {
  const [joinCodeInput, setJoinCodeInput] = useState<string>(initialRoomCode || '');
  const [isEditingProfile, setIsEditingProfile] = useState<boolean>(false);
  const [editName, setEditName] = useState<string>(localPlayer.displayName);
  const [selectedAvatar, setSelectedAvatar] = useState<string>(localPlayer.avatarUrl || '🧑');

  useEffect(() => {
    if (initialRoomCode) {
      setJoinCodeInput(initialRoomCode);
    }
  }, [initialRoomCode]);

  const handleSaveProfile = () => {
    const trimmed = editName.trim() || 'Player';
    const updated: Player = {
      ...localPlayer,
      displayName: trimmed,
      username: trimmed.toLowerCase().replace(/\s+/g, '_'),
      avatarUrl: selectedAvatar
    };
    onUpdatePlayer(updated);
    setIsEditingProfile(false);
    soundEffects.playTap();
  };

  const handleJoinClick = (e: React.FormEvent) => {
    e.preventDefault();
    const clean = joinCodeInput.trim().toUpperCase();
    if (clean.length >= 4) {
      soundEffects.playTap();
      onJoinRoom(clean);
    } else {
      alert('Please enter a valid room code (at least 4-6 digits).');
    }
  };

  return (
    <div className="min-h-screen flex flex-col justify-between max-w-lg mx-auto p-4 select-none">
      {/* Profile Edit Modal */}
      {isEditingProfile && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
          <div className="w-full max-w-sm bg-slate-900 border border-slate-700 rounded-3xl p-6 shadow-2xl">
            <h3 className="text-lg font-bold text-white text-center">Edit Player Profile</h3>

            <div className="mt-4">
              <label className="text-xs font-bold text-slate-400 uppercase tracking-wider block mb-1">
                Your Nickname
              </label>
              <input
                type="text"
                maxLength={18}
                value={editName}
                onChange={e => setEditName(e.target.value)}
                className="w-full px-4 py-2.5 rounded-xl bg-slate-800 border border-slate-700 text-white font-semibold focus:outline-none focus:border-blue-500"
              />
            </div>

            <div className="mt-4">
              <label className="text-xs font-bold text-slate-400 uppercase tracking-wider block mb-2">
                Choose Avatar
              </label>
              <div className="grid grid-cols-4 gap-2">
                {AVATARS.map(avatar => (
                  <button
                    key={avatar}
                    type="button"
                    onClick={() => setSelectedAvatar(avatar)}
                    className={`h-12 rounded-xl text-2xl flex items-center justify-center transition-all ${
                      selectedAvatar === avatar
                        ? 'bg-blue-600 border-2 border-blue-400 shadow-md scale-105'
                        : 'bg-slate-800/80 border border-slate-700/60 hover:bg-slate-700'
                    }`}
                  >
                    {avatar}
                  </button>
                ))}
              </div>
            </div>

            <div className="mt-6 flex gap-2">
              <button
                type="button"
                onClick={() => setIsEditingProfile(false)}
                className="flex-1 py-2.5 rounded-xl bg-slate-800 text-slate-300 font-semibold hover:bg-slate-700 cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveProfile}
                className="flex-1 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-bold flex items-center justify-center gap-1 shadow-lg shadow-blue-500/30 cursor-pointer"
              >
                <Check className="w-4 h-4" />
                Save
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Header Logo */}
      <header className="pt-6 pb-2 text-center">
        <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-blue-500/10 border border-blue-500/30 text-blue-400 text-[11px] font-extrabold uppercase tracking-widest mb-3">
          <Sparkles className="w-3.5 h-3.5" />
          <span>Cross-Platform Multiplayer</span>
        </div>

        <h1 className="text-5xl sm:text-6xl font-black font-heading tracking-wider bg-gradient-to-r from-blue-400 via-indigo-300 to-amber-300 bg-clip-text text-transparent">
          B I N G O
        </h1>
        <p className="mt-1 text-xs sm:text-sm font-medium text-slate-400">
          Play in real-time against friends on Android, PC, or iPhone
        </p>
      </header>

      {/* Player Stats & Profile Banner */}
      <section className="my-4">
        <div className="flex items-center justify-between p-4 bg-gradient-to-r from-slate-900 to-slate-900/90 border border-slate-800 rounded-3xl shadow-xl">
          <div className="flex items-center gap-3">
            <div className="w-14 h-14 rounded-2xl bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center text-3xl shadow-md">
              {localPlayer.avatarUrl || '🧑'}
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="text-base font-extrabold text-white">{localPlayer.displayName}</span>
                <button
                  type="button"
                  onClick={() => setIsEditingProfile(true)}
                  className="text-slate-400 hover:text-blue-400 transition-colors cursor-pointer"
                >
                  <Edit3 className="w-3.5 h-3.5" />
                </button>
              </div>
              <span className="text-xs font-semibold text-blue-400">
                Level {localPlayer.level} Player
              </span>
            </div>
          </div>

          {/* Win / Streak pill */}
          <div className="flex items-center gap-3 border-l border-slate-800 pl-4">
            <div className="flex flex-col items-center">
              <span className="text-[10px] font-bold text-slate-400 uppercase flex items-center gap-0.5">
                <Trophy className="w-3 h-3 text-amber-400" /> Wins
              </span>
              <span className="text-sm font-black text-amber-400">{localPlayer.gamesWon}</span>
            </div>
            <div className="flex flex-col items-center">
              <span className="text-[10px] font-bold text-slate-400 uppercase flex items-center gap-0.5">
                <Flame className="w-3 h-3 text-rose-400" /> Streak
              </span>
              <span className="text-sm font-black text-rose-400">{localPlayer.currentStreak}</span>
            </div>
          </div>
        </div>
      </section>

      {/* Main Action Buttons */}
      <main className="space-y-3.5 my-auto py-2">
        {/* Create Online Room */}
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            onCreateRoom();
          }}
          className="w-full p-4 rounded-3xl bg-gradient-to-r from-blue-600 via-indigo-600 to-blue-500 hover:from-blue-500 hover:to-indigo-500 text-white shadow-xl shadow-blue-500/25 border border-blue-400/30 flex items-center justify-between transition-all active:scale-[0.98] cursor-pointer group"
        >
          <div className="flex items-center gap-3.5">
            <div className="w-12 h-12 rounded-2xl bg-white/10 flex items-center justify-center text-white">
              <Users className="w-6 h-6" />
            </div>
            <div className="text-left">
              <h2 className="text-base font-black tracking-wide">Create Online Room</h2>
              <p className="text-xs text-blue-100/80 font-medium">
                Host a room & get a 6-digit code for friends
              </p>
            </div>
          </div>
          <span className="text-xl group-hover:translate-x-1 transition-transform">➔</span>
        </button>

        {/* Join Online Room Form */}
        <div className="p-4 rounded-3xl bg-slate-900 border border-slate-800 shadow-xl">
          <div className="flex items-center gap-2 mb-3">
            <KeyRound className="w-4 h-4 text-amber-400" />
            <h3 className="text-xs font-extrabold uppercase tracking-wider text-slate-300">
              Join with 6-Digit Code
            </h3>
          </div>

          <form onSubmit={handleJoinClick} className="flex gap-2">
            <input
              type="text"
              maxLength={8}
              placeholder="e.g. 482915"
              value={joinCodeInput}
              onChange={e => setJoinCodeInput(e.target.value.toUpperCase())}
              className="flex-1 px-4 py-3 rounded-2xl bg-slate-800 border border-slate-700 text-white font-black tracking-widest text-center uppercase placeholder:text-slate-500 placeholder:font-normal placeholder:tracking-normal focus:outline-none focus:border-amber-400"
            />
            <button
              type="submit"
              className="px-6 py-3 rounded-2xl bg-gradient-to-r from-amber-500 to-yellow-400 hover:from-amber-400 hover:to-yellow-300 text-slate-950 font-black text-sm tracking-wider uppercase shadow-lg shadow-amber-500/20 active:scale-95 transition-all cursor-pointer"
            >
              JOIN
            </button>
          </form>
        </div>

        {/* Play with AI */}
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            onPlayAi();
          }}
          className="w-full p-4 rounded-3xl bg-slate-900/90 hover:bg-slate-850 text-slate-300 hover:text-white border border-slate-800 flex items-center justify-between transition-all active:scale-[0.98] cursor-pointer"
        >
          <div className="flex items-center gap-3.5">
            <div className="w-12 h-12 rounded-2xl bg-slate-800 flex items-center justify-center text-slate-400">
              <Bot className="w-6 h-6" />
            </div>
            <div className="text-left">
              <h2 className="text-sm font-bold">Play with AI Bot</h2>
              <p className="text-xs text-slate-500">
                Single-player offline practice match
              </p>
            </div>
          </div>
          <span className="text-slate-500 text-lg">➔</span>
        </button>
      </main>

      {/* Footer Info */}
      <footer className="pt-4 pb-2 text-center text-[11px] text-slate-600 font-medium">
        <span>BINGO Cross-Play v1.0.1 • Connected to EMQX Edge Broker</span>
      </footer>
    </div>
  );
};
