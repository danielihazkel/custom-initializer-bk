import type { KeyboardEvent } from 'react';
import { useId } from 'react';

export interface SearchSuggestion {
  label: string;
  href?: string;
  onSelect?: () => void;
}

export interface SearchFieldProps {
  /** Controlled value. Pair with `onInput`; debounce in the caller — the field does no fetching of its own. */
  value: string;
  onInput: (value: string) => void;
  /** The visually hidden `<label>` — a placeholder is not a label. */
  label: string;
  /** A question, not a label. */
  placeholder?: string;
  /** Empty renders no panel, even when `expanded`. */
  suggestions?: SearchSuggestion[];
  /** The caller owns it: open on focus, close on Escape or blur. */
  expanded?: boolean;
  /** Given, a clear control appears while the field holds text. */
  onClear?: () => void;
  clearLabel?: string;
  /** Enter in the field. */
  onSubmit?: () => void;
  id?: string;
  className?: string;
}

const glyph = (size: number) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
    <circle cx="11" cy="11" r="6.5" />
    <path d="M16 16l4.5 4.5" />
  </svg>
);

/**
 * The system's only text input: a 50px pill on a `line-strong` border (purple on focus) with the
 * search glyph on the leading side, plus the suggestions panel that drops 8px below it at `radius-menu`.
 * No yellow submit inside the pill — Enter submits.
 */
export function SearchField({
  value, onInput, label, placeholder, suggestions = [], expanded = false, onClear, clearLabel, onSubmit, id, className,
}: SearchFieldProps) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const showPanel = expanded && suggestions.length > 0;

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' && onSubmit) {
      e.preventDefault();
      onSubmit();
    }
  }

  return (
    <div className={['mn', 'mn-search', className ?? ''].filter(Boolean).join(' ')}>
      <div className="mn-search__pill">
        {glyph(19)}
        <label htmlFor={inputId} style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clipPath: 'inset(50%)' }}>
          {label}
        </label>
        <input
          id={inputId}
          type="search"
          className="mn-search__input"
          value={value}
          placeholder={placeholder}
          onChange={e => onInput(e.target.value)}
          onKeyDown={onKeyDown}
          autoComplete="off"
        />
        {onClear && value !== '' && (
          <button type="button" className="mn-search__clear" onClick={onClear} aria-label={clearLabel ?? 'Clear'}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        )}
      </div>
      {showPanel && (
        <div className="mn-search__panel" role="listbox">
          {suggestions.map(s =>
            s.href ? (
              <a key={s.label} className="mn-search__item" href={s.href} role="option" aria-selected={false} onClick={s.onSelect}>
                {glyph(15)}
                {s.label}
              </a>
            ) : (
              <button key={s.label} type="button" className="mn-search__item" role="option" aria-selected={false} onClick={s.onSelect}>
                {glyph(15)}
                {s.label}
              </button>
            ),
          )}
        </div>
      )}
    </div>
  );
}
