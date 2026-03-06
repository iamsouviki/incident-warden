import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  appType: 'spa',          // serve index.html for all 404 paths (History API routing)
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 0,          // no timeout — allow very slow LLM responses
        proxyTimeout: 0,     // no timeout — keep waiting for backend/model
      },
    },
  },
})
