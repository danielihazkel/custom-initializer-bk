export interface ChatLauncherProps {
  /** Defaults to "פנו אלינו". */
  label?: string;
  onClick?: () => void;
}

/** The floating "פנו אלינו" tab: 77px yellow, round except the corner that meets the screen edge. One per page. */
export function ChatLauncher({ label = 'פנו אלינו', onClick }: ChatLauncherProps) {
  return (
    <button type="button" className="mn mn-chat" onClick={onClick}>
      <span>{label}</span>
      <span className="mn-chat__disc" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
          <path d="M4 5h16v11H8l-4 4z" />
        </svg>
      </span>
    </button>
  );
}
