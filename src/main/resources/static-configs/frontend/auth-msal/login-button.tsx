import { useIsAuthenticated, useMsal } from '@azure/msal-react';
import { loginRequest } from '@shared/auth/msal-config';

export function LoginButton() {
  const { instance } = useMsal();
  const isAuthenticated = useIsAuthenticated();

  if (isAuthenticated) {
    return (
      <button type="button" onClick={() => instance.logoutRedirect()}>
        Sign out
      </button>
    );
  }
  return (
    <button type="button" onClick={() => instance.loginRedirect(loginRequest)}>
      Sign in with Microsoft
    </button>
  );
}
