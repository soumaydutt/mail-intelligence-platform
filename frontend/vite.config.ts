import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    proxy: {
      '/api': { target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: false },
      '/actuator/health': { target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], css: true, exclude: ['tests/e2e/**', '**/node_modules/**'] },
})
