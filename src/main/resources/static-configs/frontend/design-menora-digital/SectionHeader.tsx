import { Button } from './Button';

export interface SectionHeaderProps {
  /** Two or three words, in the `h2` style (36 / 40, weight 600). */
  title: string;
  /** The "לכל ה…" text link. Falsy omits it and forces `align: 'center'`. */
  moreLabel?: string;
  /** Renders the link as an `<a>` — it is navigation. */
  moreHref?: string;
  onMore?: () => void;
  /** Logical alignment: `start` is the right edge under `dir="rtl"`. */
  align?: 'start' | 'center';
  className?: string;
}

/**
 * The pairing every section opens with: an `h2` and, where there is more behind it, a chevroned
 * "see all" text Button — baseline-aligned, 18px apart. Never a primary Button and never a subtitle;
 * the section's action belongs inside the section.
 */
export function SectionHeader({ title, moreLabel, moreHref, onMore, align = 'start', className }: SectionHeaderProps) {
  const effective = moreLabel ? align : 'center';
  const cls = ['mn', 'mn-section', effective === 'center' ? 'mn-section--center' : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <div className={cls}>
      <h2 className="mn-section__title">{title}</h2>
      {moreLabel && <Button label={moreLabel} variant="text" href={moreHref ?? (onMore ? undefined : '#')} onClick={onMore} />}
    </div>
  );
}
