/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Clerk's publishable key (pk_test_... or pk_live_...). Public by design; see .env.example. */
  readonly VITE_CLERK_PUBLISHABLE_KEY: string | undefined
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
