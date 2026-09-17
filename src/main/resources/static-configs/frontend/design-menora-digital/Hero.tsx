import { Button } from './Button';

export interface HeroProps {
  /** A full sentence ending with "." — the closing full stop is rendered in yellow. */
  title: string;
  lead?: string;
  /** Label of the single primary CTA. Never two CTAs. */
  cta?: string;
  onCta?: () => void;
}

/** The homepage banner block: `display` headline, light `lead` line and one primary Button. */
export function Hero({ title, lead, cta, onCta }: HeroProps) {
  const endsWithDot = title.endsWith('.');
  return (
    <div className="mn">
      <h2 className="mn-display">
        {endsWithDot ? title.slice(0, -1) : title}
        {endsWithDot && <span className="dot">.</span>}
      </h2>
      {lead && <p className="mn-lead">{lead}</p>}
      {cta && (
        <div style={{ marginTop: 24 }}>
          <Button label={cta} variant="primary" onClick={onCta} />
        </div>
      )}
    </div>
  );
}
