import axios from 'axios';

/**
 * Shared axios instance for the app. Configure base URL via Vite env so each
 * environment can override without code changes (set VITE_API_BASE_URL).
 */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10_000,
});
