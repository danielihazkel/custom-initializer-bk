import { createTheme } from '@mantine/core';

/**
 * App-wide Mantine theme. Tweak primary color, font family, and component
 * defaults here. Consumed by the MantineProvider in src/app/App.tsx.
 */
export const theme = createTheme({
  primaryColor: 'blue',
  fontFamily: 'Inter, system-ui, sans-serif',
  defaultRadius: 'md',
});
