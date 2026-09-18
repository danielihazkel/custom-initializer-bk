import type { CSSProperties, ReactNode } from 'react';
import type { NavItem } from './NavLinks';

export type SocialNetwork = 'facebook' | 'instagram' | 'linkedin';

export interface FooterColumn {
  title: string;
  items: NavItem[];
}

export interface FooterSocial {
  network: SocialNetwork;
  href: string;
  /** Required — the glyph alone says nothing to a screen reader. */
  label: string;
}

export interface FooterProps {
  /** Three to five on the marketing site (five is its count). Omit for a slim band that only carries the bottom row. */
  columns?: FooterColumn[];
  social?: FooterSocial[];
  /** The one `yellow-bright` item allowed on the band — text, never a fill. */
  accent?: { label: string; href: string; onClick?: () => void };
  /** The copyright line, in `micro` at 70% `on-dark`. */
  legal?: string;
  className?: string;
}

const GLYPHS: Record<SocialNetwork, ReactNode> = {
  facebook: <path d="M14 8h2V5h-2a4 4 0 0 0-4 4v2H8v3h2v6h3v-6h2.5l.5-3H13V9a1 1 0 0 1 1-1z" />,
  instagram: (
    <>
      <rect x="4" y="4" width="16" height="16" rx="5" />
      <circle cx="12" cy="12" r="3.6" />
      <path d="M17 7.2v.2" />
    </>
  ),
  linkedin: (
    <>
      <rect x="4" y="4" width="16" height="16" rx="3" />
      <path d="M8 11v6M8 8v.2M12 17v-3.5a2 2 0 0 1 4 0V17" />
    </>
  ),
};

/**
 * The dark band that owns `surface-dark`, `on-dark`, `yellow-bright` and `body-light`: link columns
 * headed by a bold `body` line, then a hairline row with outlined social circles, the single yellow-bright
 * accent link and the legal line. Never the supplied logo PNG here, and never a primary Button.
 */
export function Footer({ columns, social, accent, legal, className }: FooterProps) {
  const hasColumns = !!columns && columns.length > 0;
  const hasRow = (social && social.length > 0) || accent || legal;
  const colStyle = hasColumns ? ({ '--mn-footer-cols': columns!.length } as CSSProperties) : undefined;
  return (
    <footer className={['mn', 'mn-footer', className ?? ''].filter(Boolean).join(' ')}>
      {hasColumns && (
        <div className="mn-footer__cols" style={colStyle}>
          {columns!.map(col => (
            <div key={col.title} className="mn-footer__col">
              <span className="mn-footer__title">{col.title}</span>
              {col.items.map(item => (
                <a
                  key={item.label}
                  href={item.href ?? '#'}
                  onClick={item.onClick ? e => { e.preventDefault(); item.onClick?.(); } : undefined}
                >
                  {item.label}
                </a>
              ))}
            </div>
          ))}
        </div>
      )}
      {hasRow && (
        <div className="mn-footer__row" style={hasColumns ? undefined : { borderTop: 0, paddingTop: 0 }}>
          {social && social.length > 0 && (
            <span className="mn-footer__social">
              {social.map(s => (
                <a key={s.network} href={s.href} aria-label={s.label}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                    {GLYPHS[s.network]}
                  </svg>
                </a>
              ))}
            </span>
          )}
          <span className="mn-footer__spacer" />
          {accent && (
            <a
              className="mn-footer__accent"
              href={accent.href}
              onClick={accent.onClick ? e => { e.preventDefault(); accent.onClick?.(); } : undefined}
            >
              {accent.label}
            </a>
          )}
          {legal && <span className="mn-footer__legal">{legal}</span>}
        </div>
      )}
    </footer>
  );
}
