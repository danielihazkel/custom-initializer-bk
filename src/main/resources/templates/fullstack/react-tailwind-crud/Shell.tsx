import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { Menu, Moon, Sun } from 'lucide-react'
import { Toaster } from 'sonner'
import { t } from '@shared/i18n'
import { useTheme } from './useTheme'

export interface ShellNavItem {
  id: string
  label: string
  icon: LucideIcon
}

/** A nav section: a labelled group, or (label '') a run of ungrouped pages. */
export interface ShellSection {
  label: string
  items: ShellNavItem[]
}

interface Props {
  /** The project's name, shown by the logo. */
  appName: string
  sections: ShellSection[]
  /** The open page's id (it is marked in the nav). */
  current: string
  /** The open page's title, in the header. */
  title: string
  /** Where an off-nav page belongs, shown above its title. */
  crumb?: { id: string; label: string }
  onNavigate: (id: string) => void
  /** A search box in the header: the words typed, on Enter. */
  onSearch?: (words: string) => void
  /** The nav as a sidebar (default) or a row under the brand. */
  navStyle?: 'sidebar' | 'topbar'
  /** Labelled sidebar sections fold. */
  collapsibleGroups?: boolean
  /** Right-to-left: the toasts open on the left. */
  rtl?: boolean
  children: ReactNode
}

/**
 * The app's frame: the navy sidebar (off-canvas on a phone) or a top bar, the header with the
 * page title, an optional search box and the theme switch, and the scrolling page itself.
 */
export function Shell({ appName, sections, current, title, crumb, onNavigate, onSearch, navStyle = 'sidebar', collapsibleGroups, rtl, children }: Props) {
  const [navOpen, setNavOpen] = useState(false)
  const { isDark, toggle } = useTheme()
  const topbar = navStyle === 'topbar'
  // A new page starts at its top, not at the scroll offset the last one was left at.
  const mainRef = useRef<HTMLElement>(null)
  useEffect(() => {
    mainRef.current?.scrollTo(0, 0)
    window.scrollTo(0, 0)
  }, [current])

  const go = (id: string) => {
    onNavigate(id)
    setNavOpen(false)
  }

  const link = ({ id, label, icon: Icon }: ShellNavItem) => {
    const active = current === id
    return (
      <a
        key={id}
        href={`#/${id}`}
        onClick={e => {
          e.preventDefault()
          go(id)
        }}
        aria-current={active ? 'page' : undefined}
        className={topbar
          ? `flex items-center gap-2 whitespace-nowrap border-b-2 px-3 py-3 text-sm transition-colors ${
            active ? 'border-gold font-medium text-white' : 'border-transparent text-slate-300 hover:text-white'
          }`
          : `flex w-full items-center gap-3 rounded-lg border-s-2 px-3 py-2 text-sm transition-colors ${
            active
              ? 'border-gold bg-ink-soft font-medium text-white'
              : 'border-transparent text-slate-300 hover:bg-ink-soft hover:text-white'
          }`}
      >
        <Icon className={`h-4 w-4 ${active ? 'text-gold' : ''}`} />
        {label}
      </a>
    )
  }

  const sidebar = (
    <>
      <div className="flex items-center gap-2.5 border-b border-white/10 px-5 py-5">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-white p-1.5 shadow-sm">
          <img src="/logo.png" alt={appName} className="h-full w-auto" />
        </div>
        <div className="leading-tight">
          <div className="font-semibold tracking-tight">{appName}</div>
          <div className="text-[11px] uppercase tracking-wider text-slate-400">{t('admin')}</div>
        </div>
      </div>
      <nav className="flex-1 space-y-4 overflow-y-auto px-3 py-4">
        {sections.map((section, i) =>
          // A labelled section folds; a run of ungrouped pages has nothing to fold under.
          collapsibleGroups && section.label ? (
            <details key={i} open className="space-y-0.5">
              <summary className="cursor-pointer select-none px-2 pb-2 text-[10px] font-semibold uppercase tracking-wider text-slate-500">{section.label}</summary>
              {section.items.map(link)}
            </details>
          ) : (
            <div key={i} className="space-y-0.5">
              {section.label && (
                <div className="px-2 pb-2 text-[10px] font-semibold uppercase tracking-wider text-slate-500">{section.label}</div>
              )}
              {section.items.map(link)}
            </div>
          ),
        )}
      </nav>
      <div className="border-t border-white/10 px-5 py-3 text-[11px] text-white/70">
        v0.0.1
      </div>
    </>
  )

  return (
    <div className="flex h-full bg-canvas text-fg">
      {!topbar && (
        <>
          {/* Static sidebar (tablet and up) */}
          <aside className="hidden w-64 flex-col bg-app-shell text-slate-100 md:flex">
            {sidebar}
          </aside>

          {/* Off-canvas sidebar (mobile) */}
          <div className={`fixed inset-0 z-40 md:hidden ${navOpen ? '' : 'pointer-events-none'}`}>
            <div
              className={`absolute inset-0 bg-black/40 backdrop-blur-sm transition-opacity duration-200 ${
                navOpen ? 'opacity-100' : 'opacity-0'
              }`}
              onClick={() => setNavOpen(false)}
            />
            <aside
              className={`absolute start-0 top-0 flex h-full w-64 flex-col bg-app-shell text-slate-100 shadow-2xl transition-transform duration-200 ease-out ${
                navOpen ? 'translate-x-0' : '-translate-x-full rtl:translate-x-full'
              }`}
            >
              {sidebar}
            </aside>
          </div>
        </>
      )}

      <div className="flex flex-1 flex-col overflow-hidden">
        {topbar && (
          // Top bar: the brand, then every nav section in a row that scrolls sideways on a phone.
          <div className="bg-app-shell text-slate-100">
            <div className="flex items-center gap-4 px-4 md:px-8">
              <div className="flex shrink-0 items-center gap-2.5 py-2.5">
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-white p-1 shadow-sm">
                  <img src="/logo.png" alt={appName} className="h-full w-auto" />
                </div>
                <div className="font-semibold tracking-tight">{appName}</div>
              </div>
              <nav className="flex min-w-0 flex-1 items-stretch gap-1 overflow-x-auto" aria-label={t('navigation')}>
                {sections.map((section, i) => (
                  <div key={i} className="flex items-stretch gap-1">
                    {section.label && (
                      <span className="self-center whitespace-nowrap ps-2 pe-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">{section.label}</span>
                    )}
                    {section.items.map(link)}
                  </div>
                ))}
              </nav>
            </div>
          </div>
        )}
        <header className="flex items-center justify-between border-b border-border bg-surface px-4 py-3.5 md:px-8">
          <div className="flex items-center gap-3">
            {!topbar && (
              <button
                onClick={() => setNavOpen(true)}
                className="-ms-1 rounded-lg p-2 text-muted transition-colors hover:bg-surface-2 hover:text-fg md:hidden"
                aria-label={t('openNav')}
              >
                <Menu className="h-5 w-5" />
              </button>
            )}
            <div>
              {crumb ? (
                <nav aria-label={t('breadcrumb')} className="text-[11px] text-muted">
                  <a
                    href={`#/${crumb.id}`}
                    onClick={e => {
                      e.preventDefault()
                      go(crumb.id)
                    }}
                    className="transition-colors hover:text-fg hover:underline"
                  >
                    {crumb.label}
                  </a>
                  <span aria-hidden="true"> /</span>
                </nav>
              ) : (
                <div className="text-[11px] uppercase tracking-wider text-muted">{appName}</div>
              )}
              <h1 className="text-sm font-semibold text-fg">{title}</h1>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {onSearch && (
              <form
                role="search"
                onSubmit={e => {
                  e.preventDefault()
                  onSearch(String(new FormData(e.currentTarget).get('q') ?? '').trim())
                }}
              >
                <input
                  name="q"
                  type="search"
                  placeholder={t('searchEverything')}
                  aria-label={t('searchEverything')}
                  className="w-40 rounded-lg border border-border bg-canvas px-3 py-1.5 text-sm text-fg placeholder:text-muted focus:border-brand focus:outline-none sm:w-64"
                />
              </form>
            )}
            <button
              onClick={toggle}
              className="rounded-lg border border-border p-2 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
              title={isDark ? t('switchToLight') : t('switchToDark')}
              aria-label={t('toggleTheme')}
            >
              {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </button>
          </div>
        </header>

        <main ref={mainRef} className="flex-1 overflow-auto bg-canvas">
          {children}
        </main>
      </div>

      <Toaster richColors position={rtl ? 'top-left' : 'top-right'} theme={isDark ? 'dark' : 'light'} />
    </div>
  )
}
