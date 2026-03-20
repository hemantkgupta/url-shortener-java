import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    proxy: {
      // API write calls → write-api (port 8080)
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Short code redirects → read-api (port 8081)
      // Matches /{shortCode} paths that aren't assets or /api
      '/r': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/r/, ''),
      },
    },
  },
})
