import React, { useState } from 'react';
import { useTheme } from '../theme/theme';

interface Props {
  avatarUrl?: string | null;
  displayName?: string;
  username?: string;
  size?: number; // Size in px, default 40
  borderWidth?: number;
  borderColor?: string;
  className?: string;
}

export const PlayerAvatar: React.FC<Props> = ({
  avatarUrl,
  displayName = 'Player',
  username,
  size = 40,
  borderWidth = 1.5,
  borderColor,
  className = ''
}) => {
  const { tokens, isDark } = useTheme();
  const [imgError, setImgError] = useState(false);

  const clean = avatarUrl?.trim() || null;
  const isImage =
    clean &&
    !imgError &&
    (clean.startsWith('http://') ||
      clean.startsWith('https://') ||
      clean.startsWith('data:image/') ||
      clean.startsWith('blob:'));

  const initial = displayName.trim().charAt(0).toUpperCase() || 'P';
  const effectiveBorder = borderColor || tokens.surfaceBorder;

  return (
    <div
      style={{
        width: `${size}px`,
        height: `${size}px`,
        borderWidth: `${borderWidth}px`,
        borderColor: effectiveBorder,
        backgroundColor: isDark ? '#222222' : '#7E22CE'
      }}
      className={`rounded-full flex-shrink-0 flex items-center justify-center overflow-hidden border select-none transition-transform ${className}`}
    >
      {isImage ? (
        <img
          src={clean!}
          alt={displayName}
          onError={() => setImgError(true)}
          className="w-full h-full object-cover rounded-full"
        />
      ) : (
        <span
          style={{
            fontSize: `${Math.round(size * 0.44)}px`,
            color: '#FFFFFF'
          }}
          className="font-bold leading-none"
        >
          {initial}
        </span>
      )}
    </div>
  );
};
