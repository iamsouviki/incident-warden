import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  appType: 'spa',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // Overridable so the UI can be pointed at an API on another port without editing
        // this file — a second instance, a remote environment, or a port already taken.
        target: process.env.VITE_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
        timeout: 0,
        proxyTimeout: 0,
      },
    },
  },
})
