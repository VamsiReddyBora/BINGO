import React, { useState } from 'react';

interface Props {
  avatarUrl?: string | null;
  displayName?: string;
  sizeClassName?: string;
  fallbackIcon?: string;
  className?: string;
}

export const PlayerAvatar: React.FC<Props> = ({
  avatarUrl,
  displayName,
  sizeClassName = 'w-10 h-10 text-lg',
  fallbackIcon = '🧑',
  className = ''
}) => {
  const [imageError, setImageError] = useState(false);

  const cleanAvatar = avatarUrl?.trim() || null;

  // Detect if cleanAvatar is a valid web image URL or base64 data URI
  const isImageUrl =
    cleanAvatar &&
    !imageError &&
    (cleanAvatar.startsWith('http://') ||
      cleanAvatar.startsWith('https://') ||
      cleanAvatar.startsWith('data:image/') ||
      cleanAvatar.startsWith('blob:'));

  // Detect if cleanAvatar is an emoji (short, no slashes or dots)
  const isEmoji =
    cleanAvatar &&
    cleanAvatar.length <= 4 &&
    !cleanAvatar.includes('/') &&
    !cleanAvatar.includes('.') &&
    !cleanAvatar.includes('\\');

  // Fallback initial letter or fallback emoji
  const initial = displayName?.trim().charAt(0).toUpperCase() || fallbackIcon;

  return (
    <div
      className={`rounded-full overflow-hidden flex-shrink-0 flex items-center justify-center select-none ${sizeClassName} ${className}`}
    >
      {isImageUrl ? (
        <img
          src={cleanAvatar!}
          alt={displayName || 'Avatar'}
          onError={() => setImageError(true)}
          className="w-full h-full object-cover rounded-full pointer-events-none"
          loading="lazy"
        />
      ) : isEmoji ? (
        <span className="leading-none select-none">{cleanAvatar}</span>
      ) : (
        <span className="leading-none select-none font-bold text-slate-700">{initial}</span>
      )}
    </div>
  );
};
