import { useCallback, useEffect, useState } from 'react';

export type MenoraTheme = 'light' | 'dark';

const STORAGE_KEY = 'menora-theme';

function readInitial(): MenoraTheme {
  if (typeof window === 'undefined') return 'light';
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch {
    /* storage unavailable — fall through to the system preference */
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function apply(theme: MenoraTheme) {
  const root = document.documentElement;
  // The design system switches on data-theme; the `dark` class is kept in step for Tailwind-style variants.
  if (theme === 'dark') root.setAttribute('data-theme', 'dark');
  else root.removeAttribute('data-theme');
  root.classList.toggle('dark', theme === 'dark');
}

/**
 * Light/dark theme for the Menora Digital tokens, persisted in localStorage and reflected as
 * `data-theme="dark"` (+ a `dark` class) on <html>. Every token in tokens.css flips off that attribute.
 */
export function useMenoraTheme() {
  const [theme, setTheme] = useState<MenoraTheme>(readInitial);

  useEffect(() => {
    apply(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      /* ignore */
    }
  }, [theme]);

  const toggle = useCallback(() => setTheme(t => (t === 'dark' ? 'light' : 'dark')), []);

  return { theme, setTheme, toggle, isDark: theme === 'dark' };
}
