export interface NavItem {
  label: string;
  href?: string;
  /** Exactly one item may be the purple, weight-500 "portal" item. */
  portal?: boolean;
  /** Draws the caret that marks a mega-menu item. */
  menu?: boolean;
  /** Marks the item as the current page (aria-current="page"). */
  current?: boolean;
  onClick?: () => void;
}

export interface NavLinksProps {
  items: NavItem[];
  /** Label of the item to mark as current (aria-current="page") — an alternative to `item.current`. */
  currentLabel?: string;
  /** One link per row (a mobile drawer). */
  stacked?: boolean;
  ariaLabel?: string;
  className?: string;
}

/** The header menu row (`nav` style in `ink-alt`, 22px gaps). Provide items in reading order. */
export function NavLinks({ items, currentLabel, stacked = false, ariaLabel, className }: NavLinksProps) {
  const cls = ['mn', 'mn-nav', stacked ? 'mn-nav--stack' : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <nav className={cls} aria-label={ariaLabel}>
      {items.map(item => (
        <a
          key={item.label}
          href={item.href ?? '#'}
          className={item.portal ? 'is-portal' : undefined}
          aria-current={item.current || currentLabel === item.label ? 'page' : undefined}
          onClick={item.onClick ? e => { e.preventDefault(); item.onClick?.(); } : undefined}
        >
          {item.label}
          {item.menu && <span className="mn-caret" aria-hidden="true" />}
        </a>
      ))}
    </nav>
  );
}
