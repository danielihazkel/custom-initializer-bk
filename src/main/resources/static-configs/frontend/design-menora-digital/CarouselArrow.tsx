export interface CarouselArrowProps {
  /** In RTL `prev` points right and `next` points left. */
  direction?: 'prev' | 'next';
  onClick?: () => void;
  ariaLabel?: string;
}

/** The 36px lavender disc with an ink chevron that pages every carousel. */
export function CarouselArrow({ direction = 'prev', onClick, ariaLabel }: CarouselArrowProps) {
  return (
    <button
      type="button"
      className={['mn', 'mn-arrow', direction === 'next' ? 'mn-arrow--next' : ''].filter(Boolean).join(' ')}
      aria-label={ariaLabel ?? (direction === 'next' ? 'הבא' : 'הקודם')}
      onClick={onClick}
    >
      <span aria-hidden="true" />
    </button>
  );
}
