import type { KeyboardEvent } from 'react';
import { useRef } from 'react';

export interface TabItem {
  id: string;
  label: string;
  /** id of the `role="tabpanel"` element this tab controls (rendered by the caller). */
  panelId?: string;
}

export interface TabsProps {
  /** Two to four. Past that the labels shorten into abbreviations — use a DropdownMenu instead. */
  items: TabItem[];
  /** Controlled — there is no uncontrolled mode; the panel below has to stay in step. */
  activeIndex: number;
  /** Fires on click and on arrow keys. */
  onChange: (index: number) => void;
  ariaLabel?: string;
  /** A strip with no panel underneath (e.g. a view switcher): tabs get all four corners rounded. */
  bare?: boolean;
  className?: string;
}

/**
 * The tab strip the tokens describe: the active tab is a `surface` lip rising out of the panel
 * (`radius-tab` on the top corners only), the inactive ones sit flush in `surface-muted`. No underline,
 * nothing yellow — tabs are navigation inside one panel, never page-level navigation.
 */
export function Tabs({ items, activeIndex, onChange, ariaLabel, bare = false, className }: TabsProps) {
  const listRef = useRef<HTMLDivElement>(null);

  function focusTab(index: number) {
    const buttons = listRef.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
    buttons?.[index]?.focus();
  }

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const rtl = (typeof document !== 'undefined' ? document.documentElement.dir : '') === 'rtl';
    const forward = rtl ? 'ArrowLeft' : 'ArrowRight';
    const backward = rtl ? 'ArrowRight' : 'ArrowLeft';
    let next: number | null = null;
    if (e.key === forward) next = (activeIndex + 1) % items.length;
    else if (e.key === backward) next = (activeIndex - 1 + items.length) % items.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = items.length - 1;
    if (next === null) return;
    e.preventDefault();
    onChange(next);
    focusTab(next);
  }

  const cls = ['mn', 'mn-tabs', bare ? 'mn-tabs--bare' : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <div ref={listRef} role="tablist" aria-label={ariaLabel} className={cls} onKeyDown={onKeyDown}>
      {items.map((item, i) => {
        const selected = i === activeIndex;
        return (
          <button
            key={item.id}
            type="button"
            role="tab"
            id={`${item.id}-tab`}
            className="mn-tab"
            aria-selected={selected}
            aria-controls={item.panelId}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(i)}
          >
            {item.label}
          </button>
        );
      })}
    </div>
  );
}
