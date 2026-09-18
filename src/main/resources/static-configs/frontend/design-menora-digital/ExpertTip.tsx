export interface ExpertTipProps {
  /** One or two lines of plain speech. No quotation marks — the component adds none. */
  quote: string;
  /** A real person. */
  name: string;
  /** Their title, in `caption`. Falsy tightens the block to a single line. */
  role?: string;
  /** 52px circle, cropped square. Falsy omits it — never substitute an initials avatar. */
  photo?: string;
  className?: string;
}

/**
 * The "המומחים מדברים" testimonial card: `body-emphasis` quote first, then the `h4` name over the
 * `caption` role. It builds trust; the section's Button collects on it — no CTA and no ratings inside.
 */
export function ExpertTip({ quote, name, role, photo, className }: ExpertTipProps) {
  return (
    <figure className={['mn', 'mn-panel', 'mn-tip', className ?? ''].filter(Boolean).join(' ')} style={{ margin: 0 }}>
      <blockquote style={{ margin: 0 }}>
        <p className="mn-tip__quote" style={{ margin: 0 }}>{quote}</p>
      </blockquote>
      <figcaption className="mn-tip__who">
        {photo && <img className="mn-tip__photo" src={photo} alt="" />}
        <span className="mn-tip__lines">
          <span className="mn-tip__name">{name}</span>
          {role && <span className="mn-tip__role">{role}</span>}
        </span>
      </figcaption>
    </figure>
  );
}
