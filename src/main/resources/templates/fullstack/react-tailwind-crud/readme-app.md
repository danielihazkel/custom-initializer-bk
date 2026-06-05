# app

App-level wiring: the root `App` component, providers, and global composition.
Highest layer — may import from any layer below (`pages`, `widgets`, `features`,
`entities`, `shared`), never the reverse.
