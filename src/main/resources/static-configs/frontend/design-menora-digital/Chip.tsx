export type ChipTone = 'lavender' | 'outlined';

export interface ChipProps {
  /** One or two words at 15 / 21. It never wraps. */
  label: string;
  /** Lavender for labels, outlined for filters. Yellow is not a tone — it comes from `selected`. */
  tone?: ChipTone;
  /** Overrides the tone with yellow. Only meaningful on a filter; one per row. */
  selected?: boolean;
  /** Given, renders an `<a>`. */
  href?: string;
  /** Given (or `selected` in play), renders a `<button>`; otherwise a plain `<span>`. */
  onClick?: () => void;
  /** 600 marks a status that still needs the reader, 400 one that is settled (table cells). */
  weight?: 400 | 500 | 600;
  /** The tighter 5px 14px box a table cell uses. */
  compact?: boolean;
  className?: string;
}

/**
 * The soft purple chip the `lavender` token names: a category label above an article title, or a
 * filter in a row. Quieter than a Button on purpose — smaller type, no shadow, and never yellow unless selected.
 * No close "×" and no count badge: chips label and filter, they do not accumulate.
 */
export function Chip({ label, tone = 'lavender', selected = false, href, onClick, weight = 500, compact = false, className }: ChipProps) {
  const cls = [
    'mn',
    'mn-chip',
    tone === 'outlined' ? 'mn-chip--outlined' : '',
    selected ? 'is-selected' : '',
    compact ? 'mn-chip--compact' : '',
    weight === 600 ? 'mn-chip--strong' : weight === 400 ? 'mn-chip--quiet' : '',
    className ?? '',
  ].filter(Boolean).join(' ');
  if (href) {
    return (
      <a className={cls} href={href} onClick={onClick} aria-current={selected ? 'true' : undefined}>
        {label}
      </a>
    );
  }
  if (onClick) {
    return (
      <button type="button" className={cls} onClick={onClick} aria-pressed={selected}>
        {label}
      </button>
    );
  }
  return <span className={cls}>{label}</span>;
}
