// Generated config for orval (https://orval.dev).
// Install with: pnpm add -D orval (then run `npx orval`).
import { defineConfig } from 'orval';

export default defineConfig({
  api: {
    input: './openapi.yaml',
    output: {
      mode: 'tags-split',
      target: 'src/shared/api/generated/endpoints.ts',
      schemas: 'src/shared/api/generated/model',
      client: 'react-query',
      override: { mutator: { path: './src/shared/api/axios.ts', name: 'api' } },
    },
  },
});
