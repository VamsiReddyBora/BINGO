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
    <div className="min-h-screen flex flex-col justify-between max-w-md mx-auto p-4 select-none bg-[#FAFAFC] text-slate-800">
      {/* ── Minimal Compact Top Bar matching Android MainMenuScreen.kt ── */}
      <header className="flex items-center justify-between py-3">
        <div>
          <h1 className="text-2xl font-black font-heading tracking-widest text-slate-800 leading-none">
            B I N G O
          </h1>
          <span className="text-[10px] font-extrabold tracking-widest text-[#7C3AED] uppercase">
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
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white border border-slate-200 hover:bg-slate-50 text-slate-700 text-xs font-bold transition-all active:scale-95 cursor-pointer shadow-sm"
          >
            <BarChart2 className="w-3.5 h-3.5 text-[#7C3AED]" />
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
            className="w-8 h-8 rounded-full bg-white border-2 border-[#7C3AED] flex items-center justify-center text-sm shadow-sm cursor-pointer"
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
          className="flex items-center justify-between p-3.5 rounded-2xl bg-white border border-slate-200 hover:border-purple-300 hover:bg-purple-50/20 active:scale-[0.98] transition-all cursor-pointer shadow-sm"
        >
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-[#F5EEFF] flex items-center justify-center text-[#7C3AED]">
              <Users className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-xs font-bold text-slate-800">Friends & Social Hub</h2>
              <p className="text-[11px] text-slate-500">Online status, friends list & 1-tap invites</p>
            </div>
          </div>
          <ArrowRight className="w-4 h-4 text-slate-400" />
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
        <div className="p-4 rounded-2xl bg-white border border-slate-200 shadow-sm">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-[#F5EEFF] flex items-center justify-center text-[#7C3AED]">
                <Bot className="w-5 h-5" />
              </div>
              <div>
                <h3 className="text-sm font-bold text-slate-800">Play vs AI Bot</h3>
                <p className="text-[11px] text-slate-500">Sharpen your skills offline.</p>
              </div>
            </div>

            <button
              type="button"
              onClick={() => {
                soundEffects.playTap();
                onPlayAi(selectedDifficulty);
              }}
              className="py-1.5 px-4 rounded-xl bg-[#7C3AED] hover:bg-[#6D28D9] active:scale-95 text-white font-extrabold text-xs uppercase tracking-wider shadow transition-all cursor-pointer"
            >
              Play
            </button>
          </div>

          {/* Difficulty Chips */}
          <div className="mt-3 pt-3 border-t border-slate-100 flex items-center gap-2">
            <span className="text-[10px] font-bold text-slate-400 uppercase mr-1">Difficulty:</span>
            {(['EASY', 'MEDIUM', 'HARD'] as const).map(diff => (
              <button
                key={diff}
                type="button"
                onClick={() => setSelectedDifficulty(diff)}
                className={`py-1 px-2.5 rounded-lg text-[10px] font-bold transition-all cursor-pointer ${
                  selectedDifficulty === diff
                    ? 'bg-[#F5EEFF] text-[#7C3AED] border border-purple-300 font-extrabold'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
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
          className="flex items-center justify-between p-4 rounded-2xl bg-white border border-slate-200 hover:border-purple-300 hover:bg-purple-50/20 active:scale-[0.98] transition-all cursor-pointer shadow-sm group"
        >
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-blue-50 flex items-center justify-center text-blue-600">
              <Cast className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-800 group-hover:text-[#7C3AED] transition-colors">
                Online Match
              </h3>
              <p className="text-[11px] text-slate-500">
                Host or join multiplayer rooms with friends.
              </p>
            </div>
          </div>
          <ArrowRight className="w-4 h-4 text-slate-400 group-hover:translate-x-1 group-hover:text-[#7C3AED] transition-all" />
        </div>

        {/* 3. Nearby Network Card (Compact) matching Android */}
        <div
          onClick={() => {
            soundEffects.playTap();
            alert('Nearby Network (LAN Wi-Fi / Hotspot discovery) requires raw UDP multicast packets, which is native to the Android APK version. For browser play across PC and phones, please use Online Match!');
          }}
          className="flex items-center justify-between p-4 rounded-2xl bg-white border border-slate-200 hover:bg-slate-50 active:scale-[0.98] transition-all cursor-pointer shadow-sm opacity-90"
        >
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-amber-50 flex items-center justify-center text-amber-600">
              <Wifi className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-800">Nearby Network</h3>
              <p className="text-[11px] text-slate-500">
                Zero-latency Wi-Fi & Hotspot peer discovery.
              </p>
            </div>
          </div>
          <span className="text-[10px] font-bold text-slate-500 uppercase px-2 py-0.5 rounded bg-slate-100">
            APK
          </span>
        </div>
      </main>

      {/* ── Minimal Bottom Footer ── */}
      <footer className="py-2 text-center flex items-center justify-between text-[11px] text-slate-500 border-t border-slate-200 mt-4">
        <span>Logged in as <strong className="text-slate-700">@{localPlayer.username}</strong></span>
        <button
          type="button"
          onClick={() => {
            soundEffects.playTap();
            if (confirm('Switch account or sign out?')) onSignOut();
          }}
          className="text-slate-500 hover:text-rose-600 underline cursor-pointer"
        >
          Sign Out
        </button>
      </footer>
    </div>
  );
};
