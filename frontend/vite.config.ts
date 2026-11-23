import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The browser only ever talks to localhost:5173. Vite forwards /api/* to the Spring Boot backend,
    // so to the browser it's all one website (same origin) and the backend needs no CORS setup.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom', // a fake browser (DOM) inside Node, so components can render in tests
    setupFiles: ['./src/test/setup.ts'],
  },
})
