import { useAccount, useIsAuthenticated, useMsal } from '@azure/msal-react';
import { loginRequest } from '@shared/auth/msal-config';

export function useAuth() {
  const { instance, accounts } = useMsal();
  const account = useAccount(accounts[0]);
  const isAuthenticated = useIsAuthenticated();

  return {
    account,
    isAuthenticated,
    login: () => instance.loginRedirect(loginRequest),
    logout: () => instance.logoutRedirect(),
  };
}
