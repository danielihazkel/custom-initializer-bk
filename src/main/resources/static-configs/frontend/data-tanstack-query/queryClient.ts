import { QueryClient } from '@tanstack/react-query';

/**
 * Singleton QueryClient — provided to the React tree via QueryClientProvider
 * in src/app/App.tsx. Tune retry/staleTime/refetch behaviour here.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});
