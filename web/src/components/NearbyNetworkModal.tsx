import React from 'react';
import { Wifi, X, Globe, Download } from 'lucide-react';
import { useTheme } from '../theme/theme';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onPlayOnline: () => void;
}

export const NearbyNetworkModal: React.FC<Props> = ({
  isOpen,
  onClose,
  onPlayOnline
}) => {
  const { tokens } = useTheme();

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fade-in select-none">
      <div
        style={{
          backgroundColor: tokens.surface,
          borderColor: tokens.surfaceBorder
        }}
        className="w-full max-w-md rounded-3xl border shadow-2xl p-5 sm:p-6 overflow-hidden flex flex-col"
      >
        {/* Header */}
        <div className="flex items-center justify-between pb-3 border-b border-inherit">
          <div className="flex items-center gap-2">
            <Wifi className="w-5 h-5 text-orange-500" />
            <span
              style={{ color: tokens.cellNeutralText }}
              className="font-bold text-base"
            >
              Nearby Network
            </span>
          </div>
          <button
            type="button"
            onClick={onClose}
            style={{
              backgroundColor: tokens.backgroundSecondary,
              color: tokens.textSecondary
            }}
            className="w-8 h-8 rounded-full flex items-center justify-center cursor-pointer active:scale-95"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Content */}
        <div className="py-4 space-y-3 text-sm">
          <div className="p-3 rounded-2xl bg-orange-500/10 border border-orange-500/30 flex items-start gap-3">
            <Wifi className="w-5 h-5 text-orange-500 flex-shrink-0 mt-0.5" />
            <div>
              <p className="font-bold text-orange-500 text-xs sm:text-sm">
                Native Android Feature
              </p>
              <p
                style={{ color: tokens.cellNeutralText }}
                className="text-xs mt-1 leading-relaxed opacity-85"
              >
                Zero-latency Wi-Fi Direct and Hotspot peer discovery utilizes low-level Android radio APIs not accessible inside web browsers.
              </p>
            </div>
          </div>

          <p
            style={{ color: tokens.cellNeutralText }}
            className="text-xs sm:text-sm leading-relaxed opacity-90"
          >
            You can enjoy instant real-time multiplayer with anyone across mobile and web using our <strong>Online Match</strong> rooms, or install our Android APK for offline Wi-Fi Direct games!
          </p>
        </div>

        {/* Action Buttons */}
        <div className="pt-3 border-t border-inherit flex flex-col gap-2">
          <button
            type="button"
            onClick={() => {
              onClose();
              onPlayOnline();
            }}
            style={{
              backgroundColor: tokens.primaryButtonBg,
              color: tokens.primaryButtonText
            }}
            className="w-full h-11 rounded-xl font-bold text-sm flex items-center justify-center gap-2 transition-all cursor-pointer active:scale-98 shadow-sm"
          >
            <Globe className="w-4 h-4" />
            <span>Play Online Match Instead</span>
          </button>

          <a
            href="https://github.com/VamsiReddyBora/BINGO/releases"
            target="_blank"
            rel="noopener noreferrer"
            style={{
              backgroundColor: tokens.backgroundSecondary,
              borderColor: tokens.surfaceBorder,
              color: tokens.cellNeutralText
            }}
            className="w-full h-10 rounded-xl font-semibold text-xs border flex items-center justify-center gap-2 transition-all cursor-pointer active:scale-98"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Download Android APK for Nearby P2P</span>
          </a>
        </div>
      </div>
    </div>
  );
};
