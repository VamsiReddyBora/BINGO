import React, { useState } from 'react';
import { ThemeProvider } from './theme/theme';
import { AppContent } from './screens/AppContent';
import { MultiDeviceTestMatrix } from './screens/MultiDeviceTestMatrix';

export { AppContent };

export const App: React.FC = () => {
  const [viewMode, setViewMode] = useState<'matrix' | 'single'>(() => {
    try {
      const params = new URLSearchParams(window.location.search);
      if (params.get('mode') === 'single') return 'single';
    } catch {}
    return 'matrix'; // Default to the requested 4-device testing matrix!
  });

  return (
    <ThemeProvider>
      <div className="w-full min-h-[100dvh] flex flex-col bg-slate-950">
        {/* View Switcher Top Bar */}
        <div className="w-full bg-slate-900 border-b border-slate-800 px-4 py-2 flex items-center justify-between text-xs z-50">
          <div className="flex items-center gap-2">
            <span className="font-black text-purple-400 tracking-wider">BINGO MULTIPLAYER</span>
            <span className="text-slate-500">•</span>
            <span className="text-slate-400 font-medium">Testing Mode:</span>
          </div>

          <div className="flex items-center gap-1.5 bg-slate-800 p-1 rounded-xl border border-slate-700">
            <button
              type="button"
              onClick={() => setViewMode('matrix')}
              className={`px-3 py-1 rounded-lg font-bold text-xs transition-all cursor-pointer ${
                viewMode === 'matrix'
                  ? 'bg-purple-600 text-white shadow-sm'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              🖥️ 4-Device Test Matrix
            </button>
            <button
              type="button"
              onClick={() => setViewMode('single')}
              className={`px-3 py-1 rounded-lg font-bold text-xs transition-all cursor-pointer ${
                viewMode === 'single'
                  ? 'bg-purple-600 text-white shadow-sm'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              📱 Single Phone View
            </button>
          </div>
        </div>

        {viewMode === 'matrix' ? (
          <MultiDeviceTestMatrix />
        ) : (
          <AppContent />
        )}
      </div>
    </ThemeProvider>
  );
};
