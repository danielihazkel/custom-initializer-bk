import { ActionDisc } from './ActionDisc';
import type { ActionDiscProps } from './ActionDisc';

export interface ActionPanelProps {
  /** Three to six discs; six is the site's rhythm. Every disc is a destination, none submits anything. */
  items: ActionDiscProps[];
  ariaLabel?: string;
  className?: string;
}

/**
 * The rounded white "פעולות נפוצות" card that holds a single evenly spaced row of ActionDiscs:
 * `surface` at `radius-card` on `shadow-card`, no border. It never wraps — below its natural width
 * the row scrolls sideways. Sits on `surface-page`, directly under the hero.
 */
export function ActionPanel({ items, ariaLabel, className }: ActionPanelProps) {
  return (
    <nav className={['mn', 'mn-panel', 'mn-action-panel', className ?? ''].filter(Boolean).join(' ')} aria-label={ariaLabel}>
      {items.map(item => (
        <ActionDisc key={item.label} {...item} />
      ))}
    </nav>
  );
}
