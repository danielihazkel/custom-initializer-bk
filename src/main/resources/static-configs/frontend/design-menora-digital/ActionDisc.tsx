import type { ReactNode } from 'react';

export interface ActionDiscProps {
  label: string;
  /** A 22px line icon in `ink` (stroke currentColor) — e.g. a Lucide icon. */
  icon?: ReactNode;
  href?: string;
  onClick?: () => void;
}

/** A common-action shortcut: a 46px yellow disc with a line icon and a two-line caption beneath. */
export function ActionDisc({ label, icon, href, onClick }: ActionDiscProps) {
  return (
    <a className="mn mn-action" href={href ?? '#'} onClick={onClick ? e => { e.preventDefault(); onClick(); } : undefined}>
      <span className="mn-action__disc" aria-hidden="true">{icon}</span>
      <span>{label}</span>
    </a>
  );
}
