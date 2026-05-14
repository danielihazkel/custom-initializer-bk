import { createBrowserRouter } from 'react-router-dom';
import { HomePage } from '@pages/home';

/**
 * Browser router definition. Add new routes here as you grow the app —
 * lazy-load route modules with React.lazy() once the bundle warrants it.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <HomePage />,
  },
]);
