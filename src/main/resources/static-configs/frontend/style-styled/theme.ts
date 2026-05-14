import type { DefaultTheme } from 'styled-components';

/**
 * App-wide styled-components theme. Consumed by the ThemeProvider in
 * src/app/App.tsx and typed by src/shared/theme/styled.d.ts so theme
 * lookups (e.g. {@code ${({ theme }) => theme.colors.primary}}) are
 * checked at compile time.
 */
export const theme: DefaultTheme = {
  colors: {
    primary: '{{palette.primary}}',
    secondary: '{{palette.secondary}}',
    {{#hasPaletteAccent}}accent: '{{palette.accent}}',{{/hasPaletteAccent}}{{^hasPaletteAccent}}accent: '{{palette.primary}}',{{/hasPaletteAccent}}
    {{#hasPaletteError}}error: '{{palette.error}}',{{/hasPaletteError}}{{^hasPaletteError}}error: '#d32f2f',{{/hasPaletteError}}
    background: '#ffffff',
    surface: '#f7f7f8',
    text: '#111827',
  },
  radii: { sm: '4px', md: '8px', lg: '16px' },
  spacing: (n: number) => `${n * 4}px`,
};
