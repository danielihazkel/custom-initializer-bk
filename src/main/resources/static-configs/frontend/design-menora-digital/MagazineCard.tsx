import type { ReactNode } from 'react';

export interface MagazineCardProps {
  title: string;
  /** Metadata line in `micro`, e.g. "זמן קריאה: 4 דקות". */
  meta?: string;
  href?: string;
  /** Optional media (an `<img>`); the placeholder is a `surface-lavender` block. */
  media?: ReactNode;
}

/** An article teaser: 278px wide, `radius-card`, 1px `line` border and `shadow-card`. */
export function MagazineCard({ title, meta, href, media }: MagazineCardProps) {
  return (
    <a className="mn mn-card" href={href ?? '#'}>
      <div className="mn-card__media">{media}</div>
      <div className="mn-card__body">
        <h3 className="mn-card__title">{title}</h3>
        {meta && <span className="mn-card__meta">{meta}</span>}
      </div>
    </a>
  );
}
