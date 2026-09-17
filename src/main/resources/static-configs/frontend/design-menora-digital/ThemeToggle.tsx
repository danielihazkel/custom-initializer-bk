import { useMenoraTheme } from './useMenoraTheme';

export interface ThemeToggleProps {
  /** Label shown while the light theme is active (i.e. the action switches to dark). */
  darkLabel?: string;
  /** Label shown while the dark theme is active (i.e. the action switches to light). */
  lightLabel?: string;
}

/** An outlined pill that flips the Menora Digital theme (`data-theme="dark"` on <html>). */
export function ThemeToggle({ darkLabel = 'מצב כהה', lightLabel = 'מצב בהיר' }: ThemeToggleProps) {
  const { isDark, toggle } = useMenoraTheme();
  return (
    <button
      type="button"
      className="mn mn-btn mn-btn--outlined mn-theme-toggle"
      onClick={toggle}
      aria-pressed={isDark}
      title={isDark ? lightLabel : darkLabel}
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
        {isDark ? (
          <>
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
          </>
        ) : (
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
        )}
      </svg>
      {isDark ? lightLabel : darkLabel}
    </button>
  );
}
