import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

// `npm test`: the generated tests (src/**/*.test.ts[x]) in a simulated browser, with the app's own
// Vite settings (the @app/@shared/… aliases).
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
    },
  }),
)
