import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 仅开发服务器使用本机代理，保持浏览器请求同源；容器部署由 Nginx 接管转发。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/actuator': 'http://127.0.0.1:8080',
    },
  },
})
