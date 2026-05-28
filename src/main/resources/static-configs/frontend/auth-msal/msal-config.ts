import { PublicClientApplication, type Configuration } from '@azure/msal-browser';

const msalConfig: Configuration = {
  auth: {
    clientId: import.meta.env.VITE_MSAL_CLIENT_ID ?? '',
    authority: import.meta.env.VITE_MSAL_TENANT_ID
      ? `https://login.microsoftonline.com/${import.meta.env.VITE_MSAL_TENANT_ID}`
      : 'https://login.microsoftonline.com/common',
    redirectUri: import.meta.env.VITE_MSAL_REDIRECT_URI ?? window.location.origin,
  },
  cache: {
    cacheLocation: 'sessionStorage',
    storeAuthStateInCookie: false,
  },
};

export const msalInstance = new PublicClientApplication(msalConfig);
export const loginRequest = { scopes: ['User.Read'] };
