export interface ServiceBubbleProps {
  label: string;
  /** The one bubble in focus: 182px, yellow, bolder. Only ever one per carousel. */
  active?: boolean;
  href?: string;
  onClick?: () => void;
}

/** The "כל השירותים" carousel item: a white 131px circle floating on `shadow-bubble`. */
export function ServiceBubble({ label, active = false, href, onClick }: ServiceBubbleProps) {
  return (
    <a
      className={['mn', 'mn-bubble', active ? 'is-active' : ''].filter(Boolean).join(' ')}
      href={href ?? '#'}
      onClick={onClick ? e => { e.preventDefault(); onClick(); } : undefined}
    >
      {label}
    </a>
  );
}
