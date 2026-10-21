import { ClerkProvider } from '@clerk/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App.tsx'
import './index.css'

// Baked into the bundle at build time. It only names the Clerk instance and is
// meant to be public; the secret key never goes near the frontend.
const publishableKey = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY
if (!publishableKey) {
  throw new Error('VITE_CLERK_PUBLISHABLE_KEY is not set; copy frontend/.env.example to .env.local')
}

// TanStack Query's cache, for server state that more than one component reads
// (so far only notifications; see useNotifications).
const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ClerkProvider publishableKey={publishableKey} afterSignOutUrl="/">
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </ClerkProvider>
  </StrictMode>,
)
