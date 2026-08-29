import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The API is proxied so the browser only ever talks to one origin in
    // development. That keeps cookies, CORS and relative URLs behaving the same
    // way they will behind a reverse proxy in production.
    proxy: {
      '/api': {
        target: process.env.CAREERFLUX_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
