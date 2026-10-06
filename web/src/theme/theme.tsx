import React, { createContext, useContext, useState, useEffect } from 'react';

export interface AppAccentPalette {
  id: string;
  name: string;
  previewColor: string;
  primaryLight: string;
  primaryDark: string;
}

export const PALETTES: AppAccentPalette[] = [
  { id: 'matte_slate', name: 'Matte Slate', previewColor: '#64748B', primaryLight: '#475569', primaryDark: '#94A3B8' },
  { id: 'royal_violet', name: 'Royal Violet', previewColor: '#7C3AED', primaryLight: '#7C3AED', primaryDark: '#8B5CF6' },
  { id: 'deep_indigo', name: 'Deep Indigo', previewColor: '#4F46E5', primaryLight: '#4F46E5', primaryDark: '#6366F1' },
  { id: 'ocean_blue', name: 'Ocean Blue', previewColor: '#2563EB', primaryLight: '#2563EB', primaryDark: '#3B82F6' },
  { id: 'midnight_cyan', name: 'Midnight Cyan', previewColor: '#0284C7', primaryLight: '#0284C7', primaryDark: '#38BDF8' },
  { id: 'forest_teal', name: 'Forest Teal', previewColor: '#0D9488', primaryLight: '#0D9488', primaryDark: '#14B8A6' },
  { id: 'emerald_sage', name: 'Emerald Sage', previewColor: '#16A34A', primaryLight: '#16A34A', primaryDark: '#22C55E' },
  { id: 'amber_gold', name: 'Amber Gold', previewColor: '#D97706', primaryLight: '#D97706', primaryDark: '#F59E0B' },
  { id: 'rose_crimson', name: 'Rose Crimson', previewColor: '#E11D48', primaryLight: '#E11D48', primaryDark: '#FB7185' },
  { id: 'ruby_red', name: 'Ruby Red', previewColor: '#DC2626', primaryLight: '#DC2626', primaryDark: '#EF4444' },
  { id: 'sunset_orange', name: 'Sunset Orange', previewColor: '#EA580C', primaryLight: '#EA580C', primaryDark: '#F97316' },
  { id: 'warm_copper', name: 'Warm Copper', previewColor: '#C2410C', primaryLight: '#C2410C', primaryDark: '#FB923C' }
];

export const BORDER_COLOR_PRESETS = [
  { hex: '#475569', name: 'Slate' },
  { hex: '#334155', name: 'Gray' },
  { hex: '#1E293B', name: 'Subtle' },
  { hex: '#94A3B8', name: 'Contrast' },
  { hex: '#38BDF8', name: 'Vivid' }
];

export interface CustomBoardColors {
  myPickHex: string | null;
  opponentPickHex: string | null;
  recentPickHex: string | null;
  completedLineHex: string | null;
  cellBorderEnabled: boolean;
  cellBorderHex: string;
}

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
  accentPaletteId: string;
  boardColors: CustomBoardColors;
  toggleTheme: () => void;
  setDarkTheme: (dark: boolean) => void;
  setAccentPalette: (paletteId: string) => void;
  updateBoardColors: (updates: Partial<CustomBoardColors>) => void;
  resetBoardColors: () => void;
}

const DEFAULT_BOARD_COLORS: CustomBoardColors = {
  myPickHex: null,
  opponentPickHex: null,
  recentPickHex: null,
  completedLineHex: null,
  cellBorderEnabled: true,
  cellBorderHex: '#282828'
};

const ThemeContext = createContext<ThemeContextType>({
  isDark: true,
  tokens: AmoledDarkTokens,
  accentPaletteId: 'royal_violet',
  boardColors: DEFAULT_BOARD_COLORS,
  toggleTheme: () => {},
  setDarkTheme: () => {},
  setAccentPalette: () => {},
  updateBoardColors: () => {},
  resetBoardColors: () => {}
});

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isDark, setIsDark] = useState<boolean>(() => {
    try {
      const saved = localStorage.getItem('bingo_dark_theme');
      if (saved !== null) return saved === 'true';
    } catch {}
    return true; // Default to AMOLED Pure Black
  });

  const [accentPaletteId, setAccentPaletteId] = useState<string>(() => {
    try {
      const saved = localStorage.getItem('bingo_accent_palette');
      if (saved) return saved;
    } catch {}
    return 'royal_violet';
  });

  const [boardColors, setBoardColors] = useState<CustomBoardColors>(() => {
    try {
      const saved = localStorage.getItem('bingo_custom_board_colors');
      if (saved) return { ...DEFAULT_BOARD_COLORS, ...JSON.parse(saved) };
    } catch {}
    return DEFAULT_BOARD_COLORS;
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

  const setAccentPalette = (paletteId: string) => {
    setAccentPaletteId(paletteId);
    try {
      localStorage.setItem('bingo_accent_palette', paletteId);
    } catch {}
  };

  const updateBoardColors = (updates: Partial<CustomBoardColors>) => {
    setBoardColors((prev) => {
      const next = { ...prev, ...updates };
      try {
        localStorage.setItem('bingo_custom_board_colors', JSON.stringify(next));
      } catch {}
      return next;
    });
  };

  const resetBoardColors = () => {
    setBoardColors(DEFAULT_BOARD_COLORS);
    try {
      localStorage.removeItem('bingo_custom_board_colors');
    } catch {}
  };

  // Compute active tokens with palette and custom board colors applied
  const baseTokens = isDark ? { ...AmoledDarkTokens } : { ...CleanLightTokens };
  const palette = PALETTES.find((p) => p.id === accentPaletteId) || PALETTES[1];
  const accentColor = isDark ? palette.primaryDark : palette.primaryLight;

  baseTokens.accentBrand = accentColor;
  if (!isDark) {
    baseTokens.primaryButtonBg = accentColor;
  }

  // Apply custom board colors if configured
  if (boardColors.myPickHex) {
    baseTokens.cellPlayerPickBg = boardColors.myPickHex;
    baseTokens.cellPlayerPickBorder = boardColors.myPickHex;
  }
  if (boardColors.opponentPickHex) {
    baseTokens.cellOpponentPickBg = boardColors.opponentPickHex;
    baseTokens.cellOpponentPickBorder = boardColors.opponentPickHex;
  }
  if (boardColors.recentPickHex) {
    baseTokens.recentPickBg = boardColors.recentPickHex;
  }
  if (boardColors.completedLineHex) {
    baseTokens.completedLineBg = boardColors.completedLineHex;
  }
  if (boardColors.cellBorderEnabled && boardColors.cellBorderHex) {
    baseTokens.cellNeutralBorder = boardColors.cellBorderHex;
  } else if (!boardColors.cellBorderEnabled) {
    baseTokens.cellNeutralBorder = 'transparent';
  }

  return (
    <ThemeContext.Provider
      value={{
        isDark,
        tokens: baseTokens,
        accentPaletteId,
        boardColors,
        toggleTheme,
        setDarkTheme,
        setAccentPalette,
        updateBoardColors,
        resetBoardColors
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
};

export const useTheme = () => useContext(ThemeContext);
