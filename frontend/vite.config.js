import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
//
// Dev proxy mirrors the Nginx gateway routing (nginx/gateway.conf).
// In Docker: all traffic goes through gateway:8000.
// In local dev (`npm run dev`): Vite proxies to the right service directly.
//
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    port: 5173,
    proxy: {
      // ── Write API ──────────────────────────────────────────────────────
      '/api/v1/shorten': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },

      // ── Analytics API ──────────────────────────────────────────────────
      '/api/v1/analytics': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api/v1/history': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
    },
    // Short-code redirects (/{code}) are NOT proxied here because the
    // Vite dev server would need to catch them before React Router does.
    // Use the gateway directly (http://localhost:8000/{code}) to test redirects.
  },
})
