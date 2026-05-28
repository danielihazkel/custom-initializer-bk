import 'styled-components';

declare module 'styled-components' {
  export interface DefaultTheme {
    colors: {
      primary: string;
      secondary: string;
      accent: string;
      error: string;
      background: string;
      surface: string;
      text: string;
    };
    radii: { sm: string; md: string; lg: string };
    spacing: (n: number) => string;
  }
}
