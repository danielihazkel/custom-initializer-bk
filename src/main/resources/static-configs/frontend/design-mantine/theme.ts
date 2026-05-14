import { createTheme, type MantineColorsTuple } from '@mantine/core';

/**
 * App-wide Mantine theme. Tweak primary color, font family, and component
 * defaults here. Consumed by the MantineProvider in src/app/App.tsx.
 *
 * Mantine requires a 10-shade tuple per custom color. The generator emits the
 * selected palette's primary hex into all 10 slots — replace with real shades
 * (e.g. via https://mantine.dev/colors-generator/) once you have brand guidance.
 */
const brand: MantineColorsTuple = [
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
  '{{palette.primary}}',
];

export const theme = createTheme({
  colors: { brand },
  primaryColor: 'brand',
  fontFamily: 'Inter, system-ui, sans-serif',
  defaultRadius: 'md',
});
