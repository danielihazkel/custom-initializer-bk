import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Combine Tailwind class names safely. Used by shadcn/ui components.
 * Example: cn('px-2', condition && 'text-red-500', props.className)
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
