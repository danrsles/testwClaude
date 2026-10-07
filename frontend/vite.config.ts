// defineConfig comes from vitest/config rather than vite so the test block
// below is typed; it is the same function with Vitest's options added.
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Proxying /api to the Spring app keeps the browser on one origin, so
    // there is no CORS to configure. The backend needs no changes.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  test: {
    // Components need a DOM to render into; jsdom supplies one in Node.
    environment: 'jsdom',
    setupFiles: './src/setupTests.ts',
  },
})
