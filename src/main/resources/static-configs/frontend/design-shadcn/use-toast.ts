import * as React from 'react';

type ToastVariant = 'default' | 'destructive';

interface ToastInstance {
  id: string;
  title?: React.ReactNode;
  description?: React.ReactNode;
  variant?: ToastVariant;
}

const listeners = new Set<(toasts: ToastInstance[]) => void>();
let toasts: ToastInstance[] = [];

function notify() {
  for (const l of listeners) l(toasts);
}

export function toast(input: Omit<ToastInstance, 'id'>): void {
  const id = Math.random().toString(36).slice(2);
  toasts = [...toasts, { ...input, id }];
  notify();
  setTimeout(() => {
    toasts = toasts.filter((t) => t.id !== id);
    notify();
  }, 5000);
}

export function useToast() {
  const [state, setState] = React.useState<ToastInstance[]>(toasts);
  React.useEffect(() => {
    listeners.add(setState);
    return () => {
      listeners.delete(setState);
    };
  }, []);
  return { toasts: state, toast };
}
