import React, { useState, useMemo, useEffect, useRef } from 'react';
import {
  Smartphone,
  Wifi,
  WifiOff,
  Minimize2,
  Maximize2,
  LogOut,
  Volume2,
  VolumeX,
  X,
  Plus,
  Zap,
  RotateCcw,
  Copy,
  Check,
  Users,
  ShieldAlert,
  ArrowRight
} from 'lucide-react';
import { Player } from '../types/models';
import { TEST_ACCOUNTS, seedAllTestAccounts } from '../network/seedTestAccounts';
import { MqttRoomManager } from '../network/mqttSync';
import { MqttSyncProvider } from '../network/MqttSyncContext';
import { AppContent } from './AppContent';
import { useTheme } from '../theme/theme';

interface VirtualDeviceState {
  id: string;
  accountKey: 'kevin' | 'stuart' | 'phil' | 'otto';
  player: Player;
  isHostDevice: boolean;
}

export const MultiDeviceTestMatrix: React.FC = () => {
  const { tokens } = useTheme();

  // Active devices in the matrix (up to 4)
  const [devices, setDevices] = useState<VirtualDeviceState[]>([
    {
      id: 'dev_kevin',
      accountKey: 'kevin',
      player: TEST_ACCOUNTS.kevin,
      isHostDevice: true
    },
    {
      id: 'dev_stuart',
      accountKey: 'stuart',
      player: TEST_ACCOUNTS.stuart,
      isHostDevice: false
    }
  ]);

  const [masterRoomCode, setMasterRoomCode] = useState<string>('');
  const [copiedCode, setCopiedCode] = useState<boolean>(false);
  const [isSeeding, setIsSeeding] = useState<boolean>(false);
  const [seedSuccess, setSeedSuccess] = useState<boolean>(false);
  const [automationStatus, setAutomationStatus] = useState<string>('');

  // Independent MQTT managers for each of the 4 potential devices
  const kevinSync = useMemo(() => new MqttRoomManager(), []);
  const stuartSync = useMemo(() => new MqttRoomManager(), []);
  const philSync = useMemo(() => new MqttRoomManager(), []);
  const ottoSync = useMemo(() => new MqttRoomManager(), []);

  const getSyncManagerForAccount = (key: string): MqttRoomManager => {
    switch (key) {
      case 'kevin':
        return kevinSync;
      case 'stuart':
        return stuartSync;
      case 'phil':
        return philSync;
      case 'otto':
        return ottoSync;
      default:
        return kevinSync;
    }
  };

  // Pre-seed accounts on mount
  useEffect(() => {
    seedAllTestAccounts(kevinSync).catch(() => {});
  }, [kevinSync]);

  const handleCopyCode = () => {
    if (!masterRoomCode) return;
    navigator.clipboard.writeText(masterRoomCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  };

  const handleManualReseed = async () => {
    setIsSeeding(true);
    try {
      await seedAllTestAccounts(kevinSync);
      setSeedSuccess(true);
      setTimeout(() => setSeedSuccess(false), 3000);
    } catch (err) {
      console.warn('Manual reseed failed:', err);
    } finally {
      setIsSeeding(false);
    }
  };

  const handleAddDevice = (key: 'stuart' | 'phil' | 'otto') => {
    if (devices.some((d) => d.accountKey === key)) return;
    if (devices.length >= 4) return;

    const newDev: VirtualDeviceState = {
      id: `dev_${key}`,
      accountKey: key,
      player: TEST_ACCOUNTS[key],
      isHostDevice: false
    };

    setDevices((prev) => [...prev, newDev]);
  };

  const handleRemoveDevice = (id: string) => {
    setDevices((prev) => prev.filter((d) => d.id !== id));
  };

  // One-Click 4-Player Match Automation
  const handleQuick4PlayerMatch = async () => {
    setAutomationStatus('Spawning 4 devices...');

    // 1. Ensure all 4 accounts are added
    const allKeys: ('kevin' | 'stuart' | 'phil' | 'otto')[] = ['kevin', 'stuart', 'phil', 'otto'];
    const fullDevices: VirtualDeviceState[] = allKeys.map((k) => ({
      id: `dev_${k}`,
      accountKey: k,
      player: TEST_ACCOUNTS[k],
      isHostDevice: k === 'kevin'
    }));
    setDevices(fullDevices);

    // 2. Generate synchronized room code
    const newRoomCode = Math.floor(100000 + Math.random() * 900000).toString();
    setMasterRoomCode(newRoomCode);
    setAutomationStatus(`Room ${newRoomCode} generated! Ready for live play.`);

    setTimeout(() => {
      setAutomationStatus('');
    }, 5000);
  };

  return (
    <div className="w-full min-h-[100dvh] flex flex-col bg-slate-950 text-slate-100 select-none">
      {/* ── Top Master Control Bar ── */}
      <header className="w-full bg-slate-900/90 border-b border-slate-800 px-4 py-3 flex flex-wrap items-center justify-between gap-3 sticky top-0 z-40 backdrop-blur-md">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-purple-600/20 text-purple-400 border border-purple-500/30 flex items-center justify-center font-black text-lg shadow-sm">
            🎮
          </div>
          <div className="flex flex-col">
            <div className="flex items-center gap-2">
              <h1 className="text-sm sm:text-base font-black tracking-wide text-white">
                Bingo Multiplayer Lab
              </h1>
              <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-purple-500/20 text-purple-300 border border-purple-500/30">
                4-Device Live Matrix
              </span>
            </div>
            <p className="text-[11px] text-slate-400">
              Live MQTT Highway: wss://...:8084/mqtt • Multi-Screen Simulation
            </p>
          </div>
        </div>

        {/* Room Code & Bot Spawning Actions */}
        <div className="flex flex-wrap items-center gap-2">
          {masterRoomCode ? (
            <div className="flex items-center gap-1.5 bg-slate-800 border border-slate-700 px-3 py-1.5 rounded-xl shadow-xs">
              <span className="text-[11px] text-slate-400 font-medium">Active Room:</span>
              <span className="font-mono font-black text-sm tracking-widest text-emerald-400">
                {masterRoomCode}
              </span>
              <button
                type="button"
                onClick={handleCopyCode}
                className="ml-1 p-1 rounded-lg hover:bg-slate-700 text-slate-300 active:scale-95 transition-all"
                title="Copy Room Code"
              >
                {copiedCode ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
            </div>
          ) : null}

          {/* Add Bot Buttons (up to 4) */}
          <div className="flex items-center gap-1.5">
            {!devices.some((d) => d.accountKey === 'stuart') && (
              <button
                type="button"
                onClick={() => handleAddDevice('stuart')}
                className="px-2.5 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-xs font-bold text-slate-200 flex items-center gap-1 active:scale-95 transition-all shadow-xs"
              >
                <Plus className="w-3.5 h-3.5 text-purple-400" />
                <span>+ Stuart 🤖</span>
              </button>
            )}

            {!devices.some((d) => d.accountKey === 'phil') && (
              <button
                type="button"
                onClick={() => handleAddDevice('phil')}
                className="px-2.5 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-xs font-bold text-slate-200 flex items-center gap-1 active:scale-95 transition-all shadow-xs"
              >
                <Plus className="w-3.5 h-3.5 text-purple-400" />
                <span>+ Phil 👾</span>
              </button>
            )}

            {!devices.some((d) => d.accountKey === 'otto') && (
              <button
                type="button"
                onClick={() => handleAddDevice('otto')}
                className="px-2.5 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-xs font-bold text-slate-200 flex items-center gap-1 active:scale-95 transition-all shadow-xs"
              >
                <Plus className="w-3.5 h-3.5 text-purple-400" />
                <span>+ Otto 🦊</span>
              </button>
            )}
          </div>

          {/* Quick Automation Setup */}
          <button
            type="button"
            onClick={handleQuick4PlayerMatch}
            className="px-3 py-1.5 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 text-white font-bold text-xs flex items-center gap-1.5 active:scale-95 transition-all shadow-sm cursor-pointer"
            title="Spawns all 4 bots and creates a shared match"
          >
            <Zap className="w-3.5 h-3.5 text-amber-300" />
            <span>Quick 4-Player Match</span>
          </button>

          {/* Reseed Accounts Button */}
          <button
            type="button"
            onClick={handleManualReseed}
            disabled={isSeeding}
            className="p-2 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 active:scale-95 transition-all cursor-pointer"
            title="Reseed Accounts & Cloud Friends"
          >
            {isSeeding ? (
              <RotateCcw className="w-4 h-4 animate-spin text-purple-400" />
            ) : seedSuccess ? (
              <Check className="w-4 h-4 text-emerald-400" />
            ) : (
              <Users className="w-4 h-4 text-slate-400" />
            )}
          </button>
        </div>
      </header>

      {/* Automation Alert banner if active */}
      {automationStatus && (
        <div className="w-full bg-purple-900/30 border-b border-purple-800/40 px-4 py-2 text-center text-xs font-medium text-purple-200 animate-fade-in flex items-center justify-center gap-2">
          <Zap className="w-3.5 h-3.5 text-amber-300 animate-bounce" />
          <span>{automationStatus}</span>
        </div>
      )}

      {/* ── Virtual Phones Grid (1 to 4 screens side by side) ── */}
      <main className="flex-1 p-3 sm:p-4 overflow-x-auto">
        <div className="min-w-fit flex justify-center items-start gap-4 mx-auto">
          {devices.map((device, index) => (
            <VirtualPhoneFrame
              key={device.id}
              deviceNumber={index + 1}
              player={device.player}
              accountKey={device.accountKey}
              isHostDevice={device.isHostDevice}
              canRemove={!device.isHostDevice}
              onRemove={() => handleRemoveDevice(device.id)}
              syncManager={getSyncManagerForAccount(device.accountKey)}
              masterRoomCode={masterRoomCode}
              onRoomCreated={(code) => setMasterRoomCode(code)}
            />
          ))}
        </div>
      </main>
    </div>
  );
};

interface VirtualPhoneFrameProps {
  deviceNumber: number;
  player: Player;
  accountKey: string;
  isHostDevice: boolean;
  canRemove: boolean;
  onRemove: () => void;
  syncManager: MqttRoomManager;
  masterRoomCode: string;
  onRoomCreated: (code: string) => void;
}

export const VirtualPhoneFrame: React.FC<VirtualPhoneFrameProps> = ({
  deviceNumber,
  player,
  accountKey,
  isHostDevice,
  canRemove,
  onRemove,
  syncManager,
  masterRoomCode,
  onRoomCreated
}) => {
  const [isMinimized, setIsMinimized] = useState<boolean>(false);
  const [isNetworkCut, setIsNetworkCut] = useState<boolean>(false);
  const [isMuted, setIsMuted] = useState<boolean>(!isHostDevice); // Mute bots by default to avoid echo
  const [resumeWelcomeBanner, setResumeWelcomeBanner] = useState<boolean>(false);
  const [pingMs, setPingMs] = useState<number>(24);
  const [isConnected, setIsConnected] = useState<boolean>(true);

  // Subscribe to device's independent MQTT ping & connection
  useEffect(() => {
    syncManager.onPingChanged = (ms) => setPingMs(ms);
    syncManager.onConnectionChanged = (conn) => setIsConnected(conn);
  }, [syncManager]);

  // Minimize / Resume Action (Rule 3A / 3C)
  const handleToggleMinimize = () => {
    if (!isMinimized) {
      // Backgrounding app
      setIsMinimized(true);
    } else {
      // Resuming app from background (Rule 3C: triggers Welcome Back & 30s reset)
      setIsMinimized(false);
      setResumeWelcomeBanner(true);
      setTimeout(() => setResumeWelcomeBanner(false), 3500);
    }
  };

  // Network Drop / Reconnect Action (Rule 6 Disconnect Grace Period)
  const handleToggleNetwork = () => {
    if (!isNetworkCut) {
      // Sever network socket
      setIsNetworkCut(true);
      setIsConnected(false);
      try {
        syncManager.disconnect();
      } catch {}
    } else {
      // Restore network socket
      setIsNetworkCut(false);
      setIsConnected(true);
      try {
        syncManager.initGlobalClient(player);
      } catch {}
    }
  };

  // Exit / Surrender Action (Rule 4A / 4B)
  const handleSimulateExitOrSurrender = () => {
    if (confirm(`Simulate Exit / Surrender on ${player.displayName}?`)) {
      try {
        syncManager.sendPacket({
          type: 'SURRENDER',
          playerId: player.id,
          displayName: player.displayName
        });
      } catch {}
    }
  };

  return (
    <div className="w-[320px] sm:w-[340px] flex-shrink-0 flex flex-col items-center">
      {/* ── Hardware Simulation Toolbar Above Phone ── */}
      <div className="w-full bg-slate-900 border border-slate-800 rounded-2xl px-3 py-2 mb-2 shadow-lg flex items-center justify-between">
        {/* Device Name & Status */}
        <div className="flex items-center gap-2">
          <span className="text-base">{player.avatarUrl || '📱'}</span>
          <div className="flex flex-col">
            <span className="text-xs font-black text-white flex items-center gap-1">
              {player.displayName}
              {isHostDevice && <span className="text-[9px] px-1.5 py-0.2 rounded bg-purple-500/20 text-purple-300 border border-purple-500/30">HOST</span>}
            </span>
            <div className="flex items-center gap-1.5 text-[10px]">
              <span
                className={`w-1.5 h-1.5 rounded-full ${
                  isNetworkCut
                    ? 'bg-rose-500'
                    : isMinimized
                    ? 'bg-amber-400'
                    : 'bg-emerald-400'
                }`}
              />
              <span className="text-slate-400 font-mono">
                {isNetworkCut ? 'OFFLINE' : isMinimized ? 'BG' : `${pingMs}ms`}
              </span>
            </div>
          </div>
        </div>

        {/* Hardware Action Buttons */}
        <div className="flex items-center gap-1">
          {/* Minimize / Resume Button */}
          <button
            type="button"
            onClick={handleToggleMinimize}
            className={`p-1.5 rounded-lg border text-xs cursor-pointer active:scale-95 transition-all ${
              isMinimized
                ? 'bg-amber-500/20 border-amber-500/40 text-amber-300'
                : 'bg-slate-800 border-slate-700 text-slate-300 hover:bg-slate-700'
            }`}
            title={isMinimized ? 'Resume app (Rule 3C)' : 'Minimize to background (Rule 3A)'}
          >
            {isMinimized ? <Maximize2 className="w-3.5 h-3.5" /> : <Minimize2 className="w-3.5 h-3.5" />}
          </button>

          {/* Network Cut / Restore Button */}
          <button
            type="button"
            onClick={handleToggleNetwork}
            className={`p-1.5 rounded-lg border text-xs cursor-pointer active:scale-95 transition-all ${
              isNetworkCut
                ? 'bg-rose-500/20 border-rose-500/40 text-rose-300'
                : 'bg-slate-800 border-slate-700 text-slate-300 hover:bg-slate-700'
            }`}
            title={isNetworkCut ? 'Reconnect network (Restore)' : 'Cut network (Simulate drop / Airplane mode)'}
          >
            {isNetworkCut ? <WifiOff className="w-3.5 h-3.5" /> : <Wifi className="w-3.5 h-3.5" />}
          </button>

          {/* Exit / Surrender Button */}
          <button
            type="button"
            onClick={handleSimulateExitOrSurrender}
            className="p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 active:scale-95 transition-all cursor-pointer"
            title="Surrender / Exit activity (Rule 4A/4B)"
          >
            <LogOut className="w-3.5 h-3.5 text-rose-400" />
          </button>

          {/* Audio Mute Toggle */}
          <button
            type="button"
            onClick={() => setIsMuted(!isMuted)}
            className="p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 active:scale-95 transition-all cursor-pointer"
            title={isMuted ? 'Unmute phone sound' : 'Mute phone sound'}
          >
            {isMuted ? <VolumeX className="w-3.5 h-3.5 text-slate-500" /> : <Volume2 className="w-3.5 h-3.5 text-emerald-400" />}
          </button>

          {/* Close Bot Device */}
          {canRemove && (
            <button
              type="button"
              onClick={onRemove}
              className="p-1.5 rounded-lg bg-slate-800 hover:bg-rose-500/20 border border-slate-700 text-slate-400 hover:text-rose-300 active:scale-95 transition-all cursor-pointer"
              title="Remove this phone from matrix"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* ── Realistic Smartphone Frame Bezel ── */}
      <div className="w-full h-[680px] bg-black rounded-[2.8rem] p-2.5 shadow-2xl border-[5px] border-slate-800 relative flex flex-col overflow-hidden">
        {/* Dynamic Island / Camera Notch */}
        <div className="w-20 h-4 bg-black rounded-full mx-auto absolute top-3 left-1/2 -translate-x-1/2 z-30 flex items-center justify-center">
          <span className="w-2 h-2 rounded-full bg-slate-900 border border-slate-800" />
        </div>

        {/* Smartphone Screen Viewport */}
        <div className="w-full h-full rounded-[2.2rem] bg-slate-950 overflow-hidden relative flex flex-col">
          {/* Virtual Phone Status Bar */}
          <div className="w-full h-6 px-5 pt-1 flex items-center justify-between text-[10px] font-mono font-bold text-slate-400 bg-transparent z-20 pointer-events-none select-none">
            <span>12:45</span>
            <div className="flex items-center gap-1.5">
              <span>5G</span>
              {isNetworkCut ? <WifiOff className="w-3 h-3 text-rose-400" /> : <Wifi className="w-3 h-3 text-emerald-400" />}
              <span>98%</span>
            </div>
          </div>

          {/* Quick Room Code Helper Overlay on Bot Screens if Master Room is active */}
          {!isHostDevice && masterRoomCode && (
            <div className="w-full bg-purple-950/80 border-b border-purple-800/60 px-3 py-1 flex items-center justify-between text-[11px] z-20">
              <span className="text-purple-300 font-semibold truncate">
                Host Room: <b className="font-mono text-white">{masterRoomCode}</b>
              </span>
              <button
                type="button"
                onClick={() => {
                  try {
                    // Pre-fill room code in URL or navigate
                    const searchInput = document.querySelector(`[data-device="${accountKey}"] input`);
                    if (searchInput) {
                      (searchInput as HTMLInputElement).value = masterRoomCode;
                    }
                  } catch {}
                }}
                className="px-2 py-0.5 rounded bg-purple-600 text-white font-bold text-[10px] hover:bg-purple-500 active:scale-95"
              >
                Use Code
              </button>
            </div>
          )}

          {/* Welcome Back Resume Toast (Rule 3C simulation) */}
          {resumeWelcomeBanner && (
            <div className="absolute top-8 left-3 right-3 z-50 bg-emerald-600 text-white p-2.5 rounded-2xl shadow-xl flex items-center gap-2 text-xs font-bold animate-bounce select-none">
              <span className="text-base">👋</span>
              <div className="flex flex-col">
                <span>Welcome Back!</span>
                <span className="text-[10px] opacity-90">Rule 3C: Resumed foreground • 30s timer reset</span>
              </div>
            </div>
          )}

          {/* Offline / Airplane Mode Overlay (Rule 6 simulation) */}
          {isNetworkCut && (
            <div className="absolute top-7 left-2 right-2 z-40 bg-rose-600/90 text-white p-2 rounded-xl shadow-lg flex items-center justify-between text-xs select-none backdrop-blur-xs">
              <div className="flex items-center gap-1.5 font-bold text-[11px]">
                <WifiOff className="w-3.5 h-3.5" />
                <span>Airplane Mode (Rule 6 Grace)</span>
              </div>
              <button
                type="button"
                onClick={handleToggleNetwork}
                className="px-2 py-0.5 rounded-lg bg-white text-rose-700 font-black text-[10px] active:scale-95"
              >
                Reconnect
              </button>
            </div>
          )}

          {/* App Minimized / Backgrounded Simulation Overlay (Rule 3A) */}
          {isMinimized ? (
            <div className="w-full h-full flex flex-col items-center justify-center p-6 text-center bg-slate-900/95 backdrop-blur-md z-30 select-none animate-fade-in">
              <div className="w-16 h-16 rounded-3xl bg-amber-500/10 text-amber-400 border border-amber-500/30 flex items-center justify-center text-3xl mb-4 shadow-md">
                📱
              </div>
              <h3 className="text-sm font-black text-white mb-1">
                App in Background
              </h3>
              <p className="text-[11px] text-slate-400 mb-6 leading-relaxed">
                Simulating Android Home button.<br />
                <b className="text-amber-300">Rule 3A:</b> Turn countdown continues ticking in the background.
              </p>
              <button
                type="button"
                onClick={handleToggleMinimize}
                className="w-full py-3 rounded-2xl bg-amber-500 hover:bg-amber-400 text-slate-950 font-black text-xs cursor-pointer active:scale-95 shadow-lg flex items-center justify-center gap-2"
              >
                <Maximize2 className="w-4 h-4" />
                <span>Resume App (Rule 3C)</span>
              </button>
            </div>
          ) : (
            /* Fully Functioning Bingo Client Instance inside this Device */
            <div className="w-full h-full flex flex-col overflow-y-auto overflow-x-hidden no-scrollbar" data-device={accountKey}>
              <MqttSyncProvider manager={syncManager}>
                <AppContent
                  initialPlayer={player}
                  syncManager={syncManager}
                  onRoomCreated={onRoomCreated}
                  isMuted={isMuted}
                />
              </MqttSyncProvider>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
