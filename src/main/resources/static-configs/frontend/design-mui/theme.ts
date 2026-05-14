import { createTheme } from '@mui/material/styles';

/**
 * App-wide MUI theme. Customize palette, typography, and component
 * defaults here. Consumed by the ThemeProvider in src/app/App.tsx.
 */
export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '{{palette.primary}}' },
    secondary: { main: '{{palette.secondary}}' },
    {{#hasPaletteError}}error: { main: '{{palette.error}}' },{{/hasPaletteError}}
  },
  typography: {
    fontFamily: [
      '-apple-system',
      'BlinkMacSystemFont',
      'Segoe UI',
      'Roboto',
      'Helvetica',
      'Arial',
      'sans-serif',
    ].join(','),
  },
  shape: { borderRadius: 8 },
});
