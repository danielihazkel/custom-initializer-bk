import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './app/App'
{{#hasEvents}}
import { LiveUpdates } from './shared/api/LiveUpdates'
{{/hasEvents}}
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
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
