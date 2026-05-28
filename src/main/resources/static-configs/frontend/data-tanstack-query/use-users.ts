import { useQuery } from '@tanstack/react-query';

export interface User {
  id: number;
  name: string;
  email: string;
}

/**
 * Sample TanStack Query hook. Replace the fetch URL + types with your own API.
 */
export function useUsers() {
  return useQuery<User[]>({
    queryKey: ['users'],
    queryFn: async () => {
      const res = await fetch('/api/users');
      if (!res.ok) throw new Error(`Failed to load users: ${res.status}`);
      return res.json() as Promise<User[]>;
    },
  });
}
