// Storage manager for Favorite Emojis matching Android EmojiPreferences.kt
export const MIN_FAVORITES = 1;
export const MAX_FAVORITES = 10;
export const DEFAULT_FAVORITES = ['🔥', '😂', '🎯', '👏', '😱'];

export const ALL_REACTION_EMOJIS = [
  '🔥', '😂', '🎯', '👏', '😱', '⚡', '🍀', '❤️', '🚀', '👑',
  '🤝', '🧠', '🎲', '🏆', '💥', '⏳', '🙌', '😅', '🥳', '💀',
  '🤡', '🤖', '😍', '😎', '😭', '🤯', '💯', '✨', '🎉', '💪',
  '👀', '🫡', '🙏', '😴', '🥶', '🥵', '💩', '👻', '👾', '🎮'
];

const STORAGE_KEY_FAVORITES = 'bingo_favorite_emojis_v1';

export class EmojiPreferences {
  public static getFavoriteEmojis(): string[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY_FAVORITES);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length > 0) {
          return parsed.slice(0, MAX_FAVORITES);
        }
      }
    } catch {}
    return [...DEFAULT_FAVORITES];
  }

  public static saveFavoriteEmojis(favorites: string[]): void {
    const clean = Array.from(new Set(favorites.map((e) => e.trim()).filter((e) => e.length > 0))).slice(0, MAX_FAVORITES);
    const finalFavs = clean.length >= MIN_FAVORITES ? clean : [...DEFAULT_FAVORITES];
    try {
      localStorage.setItem(STORAGE_KEY_FAVORITES, JSON.stringify(finalFavs));
    } catch {}
  }

  public static addFavoriteEmoji(emoji: string): boolean {
    const current = this.getFavoriteEmojis();
    const clean = emoji.trim();
    if (!clean || current.includes(clean) || current.length >= MAX_FAVORITES) {
      return false;
    }
    const updated = [...current, clean];
    this.saveFavoriteEmojis(updated);
    return true;
  }

  public static removeFavoriteEmoji(emoji: string): boolean {
    const current = this.getFavoriteEmojis();
    const clean = emoji.trim();
    if (current.length <= MIN_FAVORITES || !current.includes(clean)) {
      return false;
    }
    const updated = current.filter((e) => e !== clean);
    this.saveFavoriteEmojis(updated);
    return true;
  }

  public static resetFavoritesToDefault(): string[] {
    this.saveFavoriteEmojis(DEFAULT_FAVORITES);
    return [...DEFAULT_FAVORITES];
  }
}
