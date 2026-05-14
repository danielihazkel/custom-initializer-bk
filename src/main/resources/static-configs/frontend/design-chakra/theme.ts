import { extendTheme } from '@chakra-ui/react';

/**
 * App-wide Chakra UI theme. Override colors, fonts, and component styles
 * here. Consumed by the ChakraProvider in src/app/App.tsx.
 */
export const theme = extendTheme({
  config: {
    initialColorMode: 'light',
    useSystemColorMode: false,
  },
  colors: {
    brand: {
      50: '#e3f2fd',
      100: '#bbdefb',
      500: '#1976d2',
      900: '#0d47a1',
    },
  },
  fonts: {
    heading: `'Inter', system-ui, sans-serif`,
    body: `'Inter', system-ui, sans-serif`,
  },
});
