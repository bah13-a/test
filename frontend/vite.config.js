import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Le build est servi par Spring Boot (src/main/resources/static). En dev : proxy vers l'API sur :8080.
export default defineConfig({
  plugins: [react()],
  build: { outDir: '../src/main/resources/static', emptyOutDir: true },
  server: { proxy: { '/admin': 'http://localhost:8080', '/portal': 'http://localhost:8080', '/api': 'http://localhost:8080', '/auth': 'http://localhost:8080' } },
  test: { environment: 'jsdom', globals: true, setupFiles: './src/setup.js', include: ['src/**/*.test.{js,jsx}'] },
});
