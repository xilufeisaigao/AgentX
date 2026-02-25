import { defineConfig } from "vite";

const host = process.env.AGENTX_UI_HOST || "127.0.0.1";
const port = Number(process.env.AGENTX_UI_PORT || "5173");
const apiBase = (process.env.AGENTX_UI_API_BASE || "http://127.0.0.1:18082").replace(/\/$/, "");

export default defineConfig({
  server: {
    host,
    port,
    strictPort: true,
    proxy: {
      "/api": {
        target: apiBase,
        changeOrigin: true,
        secure: false,
      },
    },
  },
  preview: {
    host,
    port,
    strictPort: true,
  },
});
