import type { Preview } from '@storybook/react';
{{#hasStyleTailwind}}import '../src/index.css';
{{/hasStyleTailwind}}
const preview: Preview = {
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
  },
};

export default preview;
