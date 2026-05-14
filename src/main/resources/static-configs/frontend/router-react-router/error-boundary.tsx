import { useRouteError, isRouteErrorResponse } from 'react-router-dom';

/**
 * Default route-level error boundary. Plug into a route's `errorElement` to
 * surface thrown render-time / loader errors without unmounting the whole app.
 */
export function ErrorBoundary() {
  const error = useRouteError();
  if (isRouteErrorResponse(error)) {
    return (
      <div style={{ padding: 24 }}>
        <h1>{error.status} {error.statusText}</h1>
        <p>{error.data}</p>
      </div>
    );
  }
  const message = error instanceof Error ? error.message : 'Unknown error';
  return (
    <div style={{ padding: 24 }}>
      <h1>Something went wrong</h1>
      <pre>{message}</pre>
    </div>
  );
}
