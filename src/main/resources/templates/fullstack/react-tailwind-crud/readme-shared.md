# shared

Reusable infrastructure with no business knowledge: `ui/` (Table, FormDrawer,
Field), `api/` (fetch client + generic `useResource` hook). The lowest layer —
imports from nothing above it. Consume via `@shared/ui` and `@shared/api`.
