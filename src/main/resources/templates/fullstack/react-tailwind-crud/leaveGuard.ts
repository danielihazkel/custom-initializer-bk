import { t } from '../i18n'

/** How many open forms hold unsaved edits. While any does, leaving the page asks first. */
let holds = 0

/** Marks unsaved edits as present until the returned release is called (once). */
export function holdLeave(): () => void {
  holds++
  let released = false
  return () => {
    if (released) return
    released = true
    holds--
  }
}

/** May the app move to another page? Asks when a form still holds unsaved edits. */
export function mayLeave(): boolean {
  return holds === 0 || window.confirm(t('discardChanges'))
}
