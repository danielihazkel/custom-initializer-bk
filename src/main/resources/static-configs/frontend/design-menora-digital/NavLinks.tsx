export interface NavItem {
  label: string;
  href?: string;
  /** Exactly one item may be the purple, weight-500 "portal" item. */
  portal?: boolean;
  /** Draws the caret that marks a mega-menu item. */
  menu?: boolean;
  onClick?: () => void;
}

export interface NavLinksProps {
  items: NavItem[];
  /** Label of the item to mark as current (aria-current="page"). */
  currentLabel?: string;
  className?: string;
}

/** The header menu row (`nav` style in `ink-alt`, 22px gaps). Provide items in reading order. */
export function NavLinks({ items, currentLabel, className }: NavLinksProps) {
  return (
    <nav className={['mn', 'mn-nav', className ?? ''].filter(Boolean).join(' ')}>
      {items.map(item => (
        <a
          key={item.label}
          href={item.href ?? '#'}
          className={item.portal ? 'is-portal' : undefined}
          aria-current={currentLabel === item.label ? 'page' : undefined}
          onClick={item.onClick ? e => { e.preventDefault(); item.onClick?.(); } : undefined}
        >
          {item.label}
          {item.menu && <span className="mn-caret" aria-hidden="true" />}
        </a>
      ))}
    </nav>
  );
}
