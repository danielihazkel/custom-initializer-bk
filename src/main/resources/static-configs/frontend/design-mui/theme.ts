import { createTheme } from '@mui/material/styles';

/**
 * App-wide MUI theme. Customize palette, typography, and component
 * defaults here. Consumed by the ThemeProvider in src/app/App.tsx.
 */
export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#1976d2' },
    secondary: { main: '#9c27b0' },
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
