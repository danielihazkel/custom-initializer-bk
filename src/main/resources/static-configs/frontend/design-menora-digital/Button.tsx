import type { ReactNode } from 'react';

export type ButtonVariant = 'primary' | 'header' | 'outlined' | 'text';

export interface ButtonProps {
  label: ReactNode;
  /** `primary` = the yellow pill (one per block); `header` = the 56px header CTA; `outlined` = secondary beside a primary; `text` = "לכל ה…" link with a chevron. */
  variant?: ButtonVariant;
  /** Primary with the 33px carousel-CTA padding. */
  wide?: boolean;
  /** Renders an `<a>` instead of a `<button>`. */
  href?: string;
  onClick?: () => void;
  className?: string;
}

/** The Menora pill CTA. Yellow never changes on hover — only the cursor. */
export function Button({ label, variant = 'primary', wide = false, href, onClick, className }: ButtonProps) {
  const cls = ['mn', 'mn-btn', `mn-btn--${variant}`, wide ? 'mn-btn--wide' : '', className ?? ''].filter(Boolean).join(' ');
  const content = (
    <>
      {variant === 'text' && <span className="mn-chev" aria-hidden="true" />}
      {label}
    </>
  );
  if (href) {
    return (
      <a className={cls} href={href} onClick={onClick}>
        {content}
      </a>
    );
  }
  return (
    <button type="button" className={cls} onClick={onClick}>
      {content}
    </button>
  );
}
