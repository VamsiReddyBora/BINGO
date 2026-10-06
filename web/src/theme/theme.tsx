import React, { createContext, useContext, useState, useEffect } from 'react';

export interface BingoThemeTokens {
  isDark: boolean;
  background: string;
  backgroundSecondary: string;
  surface: string;
  surfaceBorder: string;
  textPrimary: string;
  textSecondary: string;
  textMuted: string;
  badgeSurface: string;
  badgeOutline: string;
  badgeContent: string;
  primaryButtonBg: string;
  primaryButtonText: string;

  // Board Tokens
  cellNeutralBg: string;
  cellNeutralText: string;
  cellNeutralBorder: string;
  cellNeutralBevel: string;

  cellPlayerPickBg: string;
  cellPlayerPickText: string;
  cellPlayerPickBorder: string;
  cellPlayerPickBevel: string;

  cellOpponentPickBg: string;
  cellOpponentPickText: string;
  cellOpponentPickBorder: string;
  cellOpponentPickBevel: string;

  recentPickBg: string;
  recentPickText: string;
  recentPickBorder: string;
  recentPickBevel: string;
  recentPickGlow: string;

  completedLineBg: string;
  completedLineText: string;
  completedLineBevel: string;

  completedLetterGradientStart: string;
  completedLetterGradientEnd: string;
  completedLetterBorder: string;

  accentBrand: string;
  accentOpponent: string;
  accentOrange: string;
}

export const CleanLightTokens: BingoThemeTokens = {
  isDark: false,
  background: '#FAFAFC',
  backgroundSecondary: '#F4F4F6',
  surface: '#FFFFFF',
  surfaceBorder: '#F1F5F9',
  textPrimary: '#1E293B',
  textSecondary: '#475569',
  textMuted: '#64748B',
  badgeSurface: '#F1F5F9',
  badgeOutline: '#E2E8F0',
  badgeContent: '#0F172A',
  primaryButtonBg: '#7E22CE',
  primaryButtonText: '#FFFFFF',

  // Board Tokens
  cellNeutralBg: '#FFFFFF',
  cellNeutralText: '#1E293B',
  cellNeutralBorder: '#CBD5E1',
  cellNeutralBevel: '#E2E8F0',

  cellPlayerPickBg: '#7E22CE',
  cellPlayerPickText: '#FFFFFF',
  cellPlayerPickBorder: '#9333EA',
  cellPlayerPickBevel: '#6B21A8',

  cellOpponentPickBg: '#C2410C',
  cellOpponentPickText: '#FFFFFF',
  cellOpponentPickBorder: '#EA580C',
  cellOpponentPickBevel: '#9A3412',

  recentPickBg: '#D9B13D',
  recentPickText: '#0F172A',
  recentPickBorder: '#B45309',
  recentPickBevel: '#C2410C',
  recentPickGlow: 'rgba(217, 177, 61, 0.25)',

  completedLineBg: '#64748B',
  completedLineText: '#FFFFFF',
  completedLineBevel: 'rgba(100, 116, 139, 0.8)',

  completedLetterGradientStart: '#FBBF24',
  completedLetterGradientEnd: '#F59E0B',
  completedLetterBorder: '#F59E0B',

  accentBrand: '#7E22CE',
  accentOpponent: '#C2410C',
  accentOrange: '#EA580C'
};

export const AmoledDarkTokens: BingoThemeTokens = {
  isDark: true,
  background: '#000000',
  backgroundSecondary: '#0A0A0A',
  surface: '#141414',
  surfaceBorder: '#262626',
  textPrimary: '#FFFFFF',
  textSecondary: '#D4D4D8',
  textMuted: '#A1A1AA',
  badgeSurface: '#1C1C1E',
  badgeOutline: '#2E2E32',
  badgeContent: '#FFFFFF',
  primaryButtonBg: '#FFFFFF',
  primaryButtonText: '#000000',

  // Board Tokens
  cellNeutralBg: '#141414',
  cellNeutralText: '#FFFFFF',
  cellNeutralBorder: '#282828',
  cellNeutralBevel: '#0A0A0A',

  cellPlayerPickBg: '#7E22CE',
  cellPlayerPickText: '#FFFFFF',
  cellPlayerPickBorder: '#9333EA',
  cellPlayerPickBevel: '#6B21A8',

  cellOpponentPickBg: '#C2410C',
  cellOpponentPickText: '#FFFFFF',
  cellOpponentPickBorder: '#EA580C',
  cellOpponentPickBevel: '#9A3412',

  recentPickBg: '#FFFFFF',
  recentPickText: '#000000',
  recentPickBorder: '#FFFFFF',
  recentPickBevel: '#EA580C',
  recentPickGlow: 'rgba(255, 255, 255, 0.3)',

  completedLineBg: '#64748B',
  completedLineText: '#FFFFFF',
  completedLineBevel: '#475569',

  completedLetterGradientStart: '#F59E0B',
  completedLetterGradientEnd: '#D97706',
  completedLetterBorder: '#B45309',

  accentBrand: '#FFFFFF',
  accentOpponent: '#F97316',
  accentOrange: '#EA580C'
};

interface ThemeContextType {
  isDark: boolean;
  tokens: BingoThemeTokens;
  toggleTheme: () => void;
  setDarkTheme: (dark: boolean) => void;
}

const ThemeContext = createContext<ThemeContextType>({
  isDark: true,
  tokens: AmoledDarkTokens,
  toggleTheme: () => {},
  setDarkTheme: () => {}
});

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isDark, setIsDark] = useState<boolean>(() => {
    try {
      const saved = localStorage.getItem('bingo_dark_theme');
      if (saved !== null) {
        return saved === 'true';
      }
    } catch {}
    return true; // Default to AMOLED Dark like the Android app
  });

  useEffect(() => {
    try {
      localStorage.setItem('bingo_dark_theme', String(isDark));
    } catch {}

    const root = document.documentElement;
    if (isDark) {
      root.classList.add('dark');
      root.classList.remove('light');
    } else {
      root.classList.remove('dark');
      root.classList.add('light');
    }

    const metaTheme = document.getElementById('theme-color-meta');
    if (metaTheme) {
      metaTheme.setAttribute('content', isDark ? '#000000' : '#FAFAFC');
    }
  }, [isDark]);

  const toggleTheme = () => setIsDark((prev) => !prev);
  const setDarkTheme = (dark: boolean) => setIsDark(dark);

  const tokens = isDark ? AmoledDarkTokens : CleanLightTokens;

  return (
    <ThemeContext.Provider value={{ isDark, tokens, toggleTheme, setDarkTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};

export const useTheme = () => useContext(ThemeContext);
