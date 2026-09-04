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
    // Source maps reconstruct the entire frontend source from the served
    // bundle, so a production build does not emit them and nginx refuses to
    // serve `.map` even if one appears. Set CAREERFLUX_SOURCEMAPS=true to get
    // them back for a debugging build, knowing what that publishes.
    sourcemap: process.env.CAREERFLUX_SOURCEMAPS === 'true',
  },
});
