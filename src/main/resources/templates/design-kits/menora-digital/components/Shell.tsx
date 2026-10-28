import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { Menu, X } from 'lucide-react'
import { Toaster } from 'sonner'
import { t } from '@shared/i18n'
import { DropdownMenu, NavLinks, SearchField, ThemeToggle, Footer, useMenoraTheme } from '@shared/ui/menora'

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
  /** Ignored: the Menora shell always lays its nav out in the header. */
  navStyle?: 'sidebar' | 'topbar'
  /** Ignored: a labelled section is a menu already. */
  collapsibleGroups?: boolean
  /** Right-to-left: the toasts open on the left. */
  rtl?: boolean
  children: ReactNode
}

/**
 * Menora Digital shell: a white top header on the #f8f8f8 page ground (logo chip, the design system's
 * NavLinks row with the current item in brand purple — a labelled section is a caret item opening a
 * DropdownMenu — the outlined ThemeToggle), the content, and the dark Footer band. No floating chat
 * launcher — that is the marketing site's contact control, not an admin app's. Logical utilities
 * (start/end, rtl:) keep it mirror-correct.
 */
export function Shell({ appName, sections, current, title, crumb, onNavigate, onSearch, rtl, children }: Props) {
  const [navOpen, setNavOpen] = useState(false)
  // The header's search box: Enter hands the words over.
  const [shellQ, setShellQ] = useState('')
  const { isDark } = useMenoraTheme()
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
  const grouped = sections.some(s => s.label)
  const linksOf = (items: ShellNavItem[]) =>
    items.map(({ id, label }) => ({ label, href: `#/${id}`, current: current === id, onClick: () => go(id) }))
  const navItems = linksOf(sections.flatMap(s => s.items))

  return (
    <div className="flex min-h-full flex-col bg-canvas text-fg">
      <header className="rounded-b-xl bg-surface shadow-menu">
        <div className="mx-auto flex max-w-[1283px] flex-wrap items-center justify-between gap-4 px-4 py-3 md:px-6">
          <div className="flex items-center gap-4">
            <button
              onClick={() => setNavOpen(o => !o)}
              className="-ms-1 rounded-lg p-2 text-muted transition-colors hover:bg-surface-2 hover:text-fg md:hidden"
              aria-label={navOpen ? t('closeNav') : t('openNav')}
              aria-expanded={navOpen}
            >
              {navOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
            </button>
            {/* The supplied mark is dark purple: keep it on a white chip so it survives dark mode. */}
            <span className="mn-logo-chip">
              <img src="/menora-mivtachim-logo.png" alt={appName} className="h-auto w-[88px]" />
            </span>
            <div className="hidden leading-tight sm:block">
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

          {/* Static nav row (tablet and up) */}
          <div className="hidden md:block">
            {grouped ? (
              <nav className="mn mn-nav" aria-label={t('main')}>
                {sections.map((section, i) =>
                  section.label ? (
                    <DropdownMenu
                      key={i}
                      ariaLabel={section.label}
                      groups={[{ items: section.items.map(({ id, label }) => ({ label, href: `#/${id}` })) }]}
                      trigger={({ props }) => (
                        <a
                          href="#"
                          role="button"
                          {...props}
                          aria-current={section.items.some(n => n.id === current) ? 'page' : undefined}
                          onClick={e => {
                            e.preventDefault()
                            props.onClick()
                          }}
                        >
                          {section.label}
                          <span className="mn-caret" aria-hidden="true" />
                        </a>
                      )}
                    />
                  ) : (
                    section.items.map(({ id, label }) => (
                      <a
                        key={id}
                        href={`#/${id}`}
                        aria-current={current === id ? 'page' : undefined}
                        onClick={e => {
                          e.preventDefault()
                          go(id)
                        }}
                      >
                        {label}
                      </a>
                    ))
                  ),
                )}
              </nav>
            ) : (
              <NavLinks ariaLabel={t('main')} items={navItems} />
            )}
          </div>

          <div className="flex items-center gap-3">
            {onSearch && (
              <SearchField
                value={shellQ}
                onInput={setShellQ}
                label={t('searchEverything')}
                placeholder={t('searchEverything')}
                onSubmit={() => onSearch(shellQ.trim())}
                className="hidden w-64 sm:block"
              />
            )}
            <ThemeToggle darkLabel={t('switchToDark')} lightLabel={t('switchToLight')} />
          </div>
        </div>

        {/* Collapsible nav (mobile) */}
        {navOpen && (
          <div className="border-t border-border px-4 py-3 md:hidden">
            {grouped ? (
              sections.map((section, i) => (
                <div key={i}>
                  {section.label && <div className="px-2.5 pt-3 text-xs font-semibold uppercase tracking-wider text-muted">{section.label}</div>}
                  <NavLinks ariaLabel={section.label || t('main')} items={linksOf(section.items)} stacked />
                </div>
              ))
            ) : (
              <NavLinks ariaLabel={t('main')} items={navItems} stacked />
            )}
          </div>
        )}
      </header>

      <main ref={mainRef} className="flex-1 overflow-auto">
        {children}
      </main>

      <Footer legal={`${appName} · v0.0.1`} />

      <Toaster richColors position={rtl ? 'top-left' : 'top-right'} theme={isDark ? 'dark' : 'light'} />
    </div>
  )
}
