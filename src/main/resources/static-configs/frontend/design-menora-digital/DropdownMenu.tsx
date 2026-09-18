import type { KeyboardEvent, ReactNode } from 'react';
import { useCallback, useEffect, useId, useRef, useState } from 'react';

export interface MenuItem {
  label: string;
  /** Given, the item renders as an `<a>` — a route, not a decision. */
  href?: string;
  onSelect?: () => void;
  disabled?: boolean;
}

export interface MenuGroup {
  /** One group with no title renders as a plain list — the `auto` shape. */
  title?: string;
  items: MenuItem[];
}

/** Attributes to spread onto the element that opens the menu. */
export interface MenuTriggerProps {
  'aria-haspopup': 'menu';
  'aria-expanded': boolean;
  'aria-controls': string;
  onClick: () => void;
  className: string;
}

export interface DropdownMenuProps {
  /** Renders the trigger; spread `props` onto a `<button>` (or merge `props.className` into a Button port). */
  trigger: (state: { open: boolean; toggle: () => void; props: MenuTriggerProps }) => ReactNode;
  groups: MenuGroup[];
  /** `auto` = compact panel at `radius-menu` aligned to its trigger; `full` = the header sheet (square top, `radius-sheet` bottom). More than one group implies `full`. */
  width?: 'auto' | 'full';
  /** Controlled open state (the header owns it); omit for uncontrolled. */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  ariaLabel?: string;
  className?: string;
}

/**
 * The mega-menu every NavLinks caret promises, and the row-action menu a table needs. Click or focus
 * opens it (never hover alone); Escape closes and returns focus to the trigger; it renders nothing while
 * closed. Shadow, not border — `shadow-menu` is lighter than `shadow-card` because the panel is temporary.
 * No yellow and no primary Button inside: a menu offers routes, not a decision.
 */
export function DropdownMenu({ trigger, groups, width, open, onOpenChange, ariaLabel, className }: DropdownMenuProps) {
  const [innerOpen, setInnerOpen] = useState(false);
  const isControlled = open !== undefined;
  const isOpen = isControlled ? open : innerOpen;
  const hostRef = useRef<HTMLDivElement>(null);
  const panelId = useId();

  const setOpen = useCallback(
    (next: boolean) => {
      if (!isControlled) setInnerOpen(next);
      onOpenChange?.(next);
    },
    [isControlled, onOpenChange],
  );

  const focusTrigger = useCallback(() => {
    hostRef.current?.querySelector<HTMLElement>('[aria-haspopup="menu"]')?.focus();
  }, []);

  // Outside click closes.
  useEffect(() => {
    if (!isOpen) return;
    const onDown = (e: MouseEvent) => {
      if (hostRef.current && !hostRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [isOpen, setOpen]);

  // Opening moves focus to the first enabled item.
  useEffect(() => {
    if (!isOpen) return;
    const first = hostRef.current?.querySelector<HTMLElement>('[role="menuitem"]:not(:disabled)');
    first?.focus();
  }, [isOpen]);

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
      focusTrigger();
      return;
    }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    const items = Array.from(hostRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? []);
    if (items.length === 0) return;
    e.preventDefault();
    const current = items.indexOf(document.activeElement as HTMLElement);
    const delta = e.key === 'ArrowDown' ? 1 : -1;
    const next = current < 0 ? 0 : (current + delta + items.length) % items.length;
    items[next].focus();
  }

  const sheet = width === 'full' || (width === undefined && groups.length > 1);
  const toggle = () => setOpen(!isOpen);
  const triggerProps: MenuTriggerProps = {
    'aria-haspopup': 'menu',
    'aria-expanded': isOpen,
    'aria-controls': panelId,
    onClick: toggle,
    className: 'mn-menu-trigger',
  };

  function select(item: MenuItem) {
    setOpen(false);
    item.onSelect?.();
  }

  const hostCls = ['mn', sheet ? '' : 'mn-menu-host', className ?? ''].filter(Boolean).join(' ');
  const panelCls = ['mn-menu', sheet ? 'mn-menu--sheet' : ''].filter(Boolean).join(' ');
  return (
    <div ref={hostRef} className={hostCls} onKeyDown={onKeyDown}>
      {trigger({ open: isOpen, toggle, props: triggerProps })}
      {isOpen && (
        <div
          id={panelId}
          role="menu"
          aria-label={ariaLabel}
          className={panelCls}
          style={sheet ? { gridTemplateColumns: `repeat(${groups.length}, minmax(0, 1fr))` } : undefined}
        >
          {groups.map((group, gi) => (
            <div key={group.title ?? gi} className="mn-menu__group" role={group.title ? 'group' : undefined} aria-label={group.title}>
              {group.title && <span className="mn-menu__title">{group.title}</span>}
              {group.items.map(item =>
                item.href ? (
                  <a key={item.label} role="menuitem" className="mn-menu__item" href={item.href} onClick={() => select(item)}>
                    {item.label}
                  </a>
                ) : (
                  <button
                    key={item.label}
                    type="button"
                    role="menuitem"
                    className="mn-menu__item"
                    disabled={item.disabled}
                    onClick={() => select(item)}
                  >
                    {item.label}
                  </button>
                ),
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
