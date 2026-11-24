import { defineConfig } from 'vitest/config'
import vue2 from '@vitejs/plugin-vue2'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  plugins: [vue2()],
  test: {
    environment: 'jsdom',
    include: ['tests/unit/**/*.spec.js'],
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
})
