import React, { useState } from 'react';
import { Cast, Wifi, Bot, Users, BarChart2, ArrowRight } from 'lucide-react';
import { Player } from '../types/models';
import { soundEffects } from '../audio/sounds';

interface Props {
  localPlayer: Player;
  onNavigateToDashboard: () => void;
  onPlayOnline: () => void;
  onPlayAi: (difficulty: 'EASY' | 'MEDIUM' | 'HARD') => void;
  onSignOut: () => void;
}

export const MainMenuScreen: React.FC<Props> = ({
  localPlayer,
  onNavigateToDashboard,
  onPlayOnline,
  onPlayAi,
  onSignOut
}) => {
  const [selectedDifficulty, setSelectedDifficulty] = useState<'EASY' | 'MEDIUM' | 'HARD'>('EASY');

  return (
    <div className="min-h-screen flex flex-col justify-between max-w-md mx-auto p-4 select-none">
      {/* ── Minimal Compact Top Bar matching Android MainMenuScreen.kt ── */}
      <header className="flex items-center justify-between py-3">
        <div>
          <h1 className="text-2xl font-black font-heading tracking-widest text-white leading-none">
            B I N G O
          </h1>
          <span className="text-[10px] font-extrabold tracking-widest text-blue-500 uppercase">
            MULTIPLAYER
          </span>
        </div>

        <div className="flex items-center gap-2">
          {/* Dashboard & Friends Button */}
          <button
            type="button"
            onClick={() => {
              soundEffects.playTap();
              onNavigateToDashboard();
            }}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-slate-900 border border-slate-700/80 hover:bg-slate-800 text-slate-200 text-xs font-bold transition-all active:scale-95 cursor-pointer shadow-sm"
          >
            <BarChart2 className="w-3.5 h-3.5 text-blue-400" />
            <span>Dashboard</span>
          </button>

          {/* Compact Profile Avatar Button */}
          <button
            type="button"
            onClick={() => {
              soundEffects.playTap();
              onNavigateToDashboard();
            }}
            title={`Logged in as @${localPlayer.username}`}
            className="w-8 h-8 rounded-full bg-slate-800 border-2 border-blue-500 flex items-center justify-center text-sm shadow cursor-pointer"
          >
            {localPlayer.avatarUrl || '🧑'}
          </button>
        </div>
      </header>

      {/* ── Social & Friends Quick Hub Card matching Android ── */}
      <section className="my-2">
        <div
          onClick={() => {
            soundEffects.playTap();
            onNavigateToDashboard();
          }}
          className="flex items-center justify-between p-3.5 rounded-2xl bg-slate-900/90 border border-slate-800 hover:border-slate-700 hover:bg-slate-850 active:scale-[0.98] transition-all cursor-pointer shadow-md"
        >
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-blue-500/15 flex items-center justify-center text-blue-400">
              <Users className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-xs font-bold text-white">Friends & Social Hub</h2>
              <p className="text-[11px] text-slate-400">Online status, friends list & 1-tap invites</p>
            </div>
          </div>
          <ArrowRight className="w-4 h-4 text-slate-500" />
        </div>
      </section>

      {/* ── Section Header: SELECT MODE ── */}
      <div className="flex items-center gap-2 mt-3 mb-1 px-1">
        <span className="text-[11px] font-extrabold tracking-widest text-slate-400 uppercase">
          SELECT MODE
        </span>
      </div>

      {/* ── Game Modes List ── */}
      <main className="space-y-3 my-auto py-1">
        {/* 1. Play vs AI Card (Compact) matching Android */}
        <div className="p-4 rounded-2xl bg-slate-900/90 border border-slate-800 shadow-md">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-purple-500/15 flex items-center justify-center text-purple-400">
                <Bot className="w-5 h-5" />
              </div>
              <div>
                <h3 className="text-sm font-bold text-white">Play vs AI Bot</h3>
                <p className="text-[11px] text-slate-400">Sharpen your skills offline.</p>
              </div>
            </div>

            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                onPlayAi(selectedDifficulty);
              }}
              className="py-1.5 px-4 rounded-xl bg-purple-600 hover:bg-purple-500 active:scale-95 text-white font-extrabold text-xs uppercase tracking-wider shadow transition-all cursor-pointer"
            >
              Play
            </button>
          </div>

          {/* Difficulty Chips */}
          <div className="mt-3 pt-3 border-t border-slate-800/80 flex items-center gap-2">
            <span className="text-[10px] font-bold text-slate-500 uppercase mr-1">Difficulty:</span>
            {(['EASY', 'MEDIUM', 'HARD'] as const).map(diff => (
              <button
                key={diff}
                type="button"
                onClick={() => setSelectedDifficulty(diff)}
                className={`py-1 px-2.5 rounded-lg text-[10px] font-bold transition-all cursor-pointer ${
                  selectedDifficulty === diff
                    ? 'bg-purple-600/30 text-purple-300 border border-purple-500/50'
                    : 'bg-slate-800 text-slate-400 hover:text-white'
                }`}
              >
                {diff}
              </button>
            ))}
          </div>
        </div>

        {/* 2. Online Match Card (Compact) matching Android */}
        <div
          onClick={() => {
            soundEffects.playTap();
            onPlayOnline();
          }}
          className="flex items-center justify-between p-4 rounded-2xl bg-slate-900/90 border border-slate-800 hover:border-blue-500/40 hover:bg-slate-850 active:scale-[0.98] transition-all cursor-pointer shadow-md group"
        >
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-blue-500/15 flex items-center justify-center text-blue-400">
              <Cast className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white group-hover:text-blue-300 transition-colors">
                Online Match
              </h3>
              <p className="text-[11px] text-slate-400">
                Host or join multiplayer rooms with friends.
              </p>
            </div>
          </div>
          <ArrowRight className="w-4 h-4 text-slate-500 group-hover:translate-x-1 group-hover:text-blue-400 transition-all" />
        </div>

        {/* 3. Nearby Network Card (Compact) matching Android */}
        <div
          onClick={() => {
            soundEffects.playTap();
            alert('Nearby Network (LAN Wi-Fi / Hotspot discovery) requires raw UDP multicast packets, which is native to the Android APK version. For browser play across PC and phones, please use Online Match!');
          }}
          className="flex items-center justify-between p-4 rounded-2xl bg-slate-900/90 border border-slate-800 hover:bg-slate-850 active:scale-[0.98] transition-all cursor-pointer shadow-md opacity-85"
        >
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-amber-500/15 flex items-center justify-center text-amber-400">
              <Wifi className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">Nearby Network</h3>
              <p className="text-[11px] text-slate-400">
                Zero-latency Wi-Fi & Hotspot peer discovery.
              </p>
            </div>
          </div>
          <span className="text-[10px] font-bold text-slate-500 uppercase px-2 py-0.5 rounded bg-slate-800">
            APK
          </span>
        </div>
      </main>

      {/* ── Minimal Bottom Footer ── */}
      <footer className="py-2 text-center flex items-center justify-between text-[11px] text-slate-500 border-t border-slate-800/80 mt-4">
        <span>Logged in as <strong className="text-slate-300">@{localPlayer.username}</strong></span>
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            if (confirm('Switch account or sign out?')) onSignOut();
          }}
          className="text-slate-400 hover:text-rose-400 underline cursor-pointer"
        >
          Sign Out
        </button>
      </footer>
    </div>
  );
};
