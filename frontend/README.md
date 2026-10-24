# LiveCut AI 工作台

Vue 3 + TypeScript 工作台，调用本机 Java API，不在浏览器保存模型或 Worker 密钥。由 [Vue 官方 create-vue](https://vuejs.org/guide/quick-start.html) 初始化，使用 npm 锁文件固定依赖。

```powershell
npm ci
npm run dev
npm run build
```

Node 使用 CI 验证版本 24.14.1；dev 默认绑定 `127.0.0.1:5173`，代理 `/api` 到 `127.0.0.1:8080`。先在仓库根目录启动 Java 与 Worker，再从页面上传和分析。若 Java 改了端口，同步修改 vite.config.ts 中的代理。

工作台操作及验证边界见 [审核工作台](../docs/11-审核工作台.md)，数据契约见 [审核 API](../docs/10-review-api.md)。正式审核版本导出是下一阶段。
