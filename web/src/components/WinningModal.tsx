import React, { useEffect, useRef } from 'react';
import { Trophy, RotateCcw, Home, Sparkles } from 'lucide-react';
import confetti from 'canvas-confetti';
import { soundEffects } from '../audio/sounds';

interface Props {
  isWinner: boolean;
  winnerName: string;
  myLinesCount: number;
  opponentLinesCount: number;
  onPlayAgain: () => void;
  onLeave: () => void;
  wantsPlayAgainName?: string | null;
}

const CELEBRATION_EMOJIS = ['🎉', '⭐', '🏆', '🔥', '✨', '👑', '🎯', '💥', '🥳', '🚀'];

export const WinningModal: React.FC<Props> = ({
  isWinner,
  winnerName,
  myLinesCount,
  opponentLinesCount,
  onPlayAgain,
  onLeave,
  wantsPlayAgainName
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (isWinner) {
      soundEffects.playBingoWin();

      // Confetti burst
      confetti({
        particleCount: 80,
        spread: 100,
        origin: { y: 0.6 }
      });

      // 360 Radial Starburst Canvas Animation
      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const dpr = window.devicePixelRatio || 1;
      const width = window.innerWidth;
      const height = window.innerHeight;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);

      const centerX = width / 2;
      const centerY = height / 2;

      // Generate 48 radial particles
      const particles: Array<{
        emoji: string;
        angle: number;
        speed: number;
        distance: number;
        scale: number;
        alpha: number;
        rotation: number;
        rotSpeed: number;
      }> = [];

      for (let i = 0; i < 48; i++) {
        const angle = (i / 48) * Math.PI * 2 + (Math.random() - 0.5) * 0.15;
        const speed = 2.2 + Math.random() * 3.5;
        const emoji = CELEBRATION_EMOJIS[Math.floor(Math.random() * CELEBRATION_EMOJIS.length)];
        particles.push({
          emoji,
          angle,
          speed,
          distance: 10,
          scale: 0.7 + Math.random() * 0.6,
          alpha: 1.0,
          rotation: Math.random() * Math.PI * 2,
          rotSpeed: (Math.random() - 0.5) * 0.05
        });
      }

      let animationFrameId: number;
      let startTime = Date.now();

      const render = () => {
        const elapsed = (Date.now() - startTime) / 1000;
        ctx.clearRect(0, 0, width, height);

        particles.forEach(p => {
          p.distance += p.speed;
          p.speed *= 0.985; // smooth deceleration
          p.rotation += p.rotSpeed;
          p.alpha = Math.max(0, 1.0 - elapsed / 3.2);

          const x = centerX + Math.cos(p.angle) * p.distance;
          const y = centerY + Math.sin(p.angle) * p.distance;

          ctx.save();
          ctx.translate(x, y);
          ctx.rotate(p.rotation);
          ctx.globalAlpha = p.alpha;
          ctx.font = `${Math.round(28 * p.scale)}px sans-serif`;
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText(p.emoji, 0, 0);
          ctx.restore();
        });

        if (elapsed < 3.5) {
          animationFrameId = requestAnimationFrame(render);
        }
      };

      animationFrameId = requestAnimationFrame(render);

      return () => {
        cancelAnimationFrame(animationFrameId);
      };
    }
  }, [isWinner]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in select-none">
      {/* 360 Radial Starburst Canvas Overlay */}
      <canvas
        ref={canvasRef}
        className="pointer-events-none fixed inset-0 z-10 w-full h-full"
      />

      <div className="relative z-20 w-full max-w-sm bg-gradient-to-b from-slate-900 via-slate-900 to-slate-950 border border-slate-700/80 rounded-3xl p-6 shadow-2xl text-center">
        {/* Glow backdrop badge */}
        <div className="mx-auto -mt-12 w-20 h-20 rounded-full bg-gradient-to-tr from-amber-500 to-yellow-300 p-1 shadow-[0_0_30px_rgba(245,158,11,0.5)] flex items-center justify-center animate-bounce">
          {isWinner ? (
            <Trophy className="w-10 h-10 text-slate-950" />
          ) : (
            <Sparkles className="w-10 h-10 text-slate-950" />
          )}
        </div>

        <h2 className="mt-4 text-3xl font-extrabold tracking-wide font-heading bg-gradient-to-r from-amber-400 via-yellow-200 to-amber-500 bg-clip-text text-transparent">
          B - I - N - G - O !
        </h2>

        <p className="mt-2 text-lg font-semibold text-slate-200">
          {isWinner ? '🎉 Victory! You Won!' : `👏 ${winnerName} Won the Game!`}
        </p>

        {/* Lines completed scoreboard */}
        <div className="mt-5 grid grid-cols-2 gap-3 p-3 bg-slate-800/60 rounded-2xl border border-slate-700/50">
          <div className="flex flex-col items-center">
            <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Your Lines</span>
            <span className="text-2xl font-black text-blue-400">{myLinesCount} / 5</span>
          </div>
          <div className="flex flex-col items-center border-l border-slate-700/50">
            <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Opponent</span>
            <span className="text-2xl font-black text-rose-400">{opponentLinesCount} / 5</span>
          </div>
        </div>

        {wantsPlayAgainName && (
          <p className="mt-3 text-xs font-bold text-emerald-400 animate-pulse">
            ✨ {wantsPlayAgainName} wants to play again!
          </p>
        )}

        {/* Action Buttons */}
        <div className="mt-6 flex flex-col gap-2.5">
          <button
            type="button"
            onClick={() => {
              soundEffects.playTap();
              onPlayAgain();
            }}
            className="w-full py-3.5 px-4 rounded-xl font-bold bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 active:scale-95 text-white shadow-lg shadow-blue-500/30 flex items-center justify-center gap-2 transition-all cursor-pointer"
          >
            <RotateCcw className="w-5 h-5" />
            Play Again
          </button>

          <button
            type="button"
            onClick={() => {
              soundEffects.playTap();
              onLeave();
            }}
            className="w-full py-3 px-4 rounded-xl font-semibold bg-slate-800 hover:bg-slate-750 active:scale-95 text-slate-300 hover:text-white border border-slate-700 flex items-center justify-center gap-2 transition-all cursor-pointer"
          >
            <Home className="w-4 h-4" />
            Back to Main Menu
          </button>
        </div>
      </div>
    </div>
  );
};
