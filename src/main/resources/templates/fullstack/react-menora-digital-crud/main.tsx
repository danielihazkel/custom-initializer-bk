import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './app/App'
{{#hasEvents}}
import { LiveUpdates } from './shared/api/LiveUpdates'
{{/hasEvents}}
// Assistant is the self-hosted fallback for the licensed Almoni brand face (see src/shared/ui/menora/tokens.css).
import '@fontsource/assistant/300.css'
import '@fontsource/assistant/400.css'
import '@fontsource/assistant/500.css'
import '@fontsource/assistant/600.css'
import '@fontsource/assistant/700.css'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
{{^hasEvents}}
    <App />
{{/hasEvents}}
{{#hasEvents}}
    <LiveUpdates>
      <App />
    </LiveUpdates>
{{/hasEvents}}
  </React.StrictMode>,
)
