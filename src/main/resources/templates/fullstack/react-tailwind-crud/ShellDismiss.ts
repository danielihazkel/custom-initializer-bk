import { useEffect } from 'react'
import type { RefObject } from 'react'

/**
 * Closes a small menu the way one expects: a click anywhere outside it, or Escape — which then
 * closes the menu alone (a drawer or dialog under it skips an Escape that was already handled).
 */
export function useDismiss(box: RefObject<HTMLElement | null>, open: boolean, close: () => void) {
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) close()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      e.preventDefault()
      close()
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey, true)
    }
  }, [box, open, close])
}
