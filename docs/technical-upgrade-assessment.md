# 技术升级评估

## 当前技术栈

- Electron: `^2.0.4`
- Vue: `^2.5.16`
- Vuex: `^3.0.1`
- vue-electron / vuex-electron / electron-vue: 旧模板链路
- Renderer 目前依赖 `remote`、Node 集成和直接文件系统能力

## 已完成的技术优化

- `menu.vue` 已拆出 `BookLibrary`、`SettingsPanel`、`SourcePanel`、`AppCenter`、`ColorPicker`。
- 读者设置、书源、依附配置、当前书快照统一通过 `src/renderer/utils/settings-store.js` 访问，避免页面里散落 `localStorage`。
- Vuex 书库数据仍由 `electron-store` 持久化，设置类数据暂时保留原 key，兼容旧数据。

## 升级风险

- Electron 现代版本默认不再推荐 `remote`，当前 `menu.vue`、`index.vue`、`utils/file.js` 都有使用。
- Renderer 中直接使用 `fs/path/electron.remote`，如果关闭 Node 集成，需要补 preload + IPC。
- `electron-vue` 模板链路较旧，直接升级 webpack/vue-loader 容易引入构建错误。
- Vue 2.5 升级到 Vue 2.7 风险较小；迁移 Vue 3 风险较大，需要改插件和模板兼容问题。

## 建议升级顺序

1. 先替换 `remote`：
   - 文件选择、保存对话框移到主进程 IPC。
   - 窗口控制、菜单、路径读取移到 preload/IPC。
2. 再收紧 Renderer 权限：
   - 逐步关闭 Node 集成。
   - 保留明确的 preload API。
3. 再升级构建链路：
   - 先 Vue 2.7 + 对应 template compiler。
   - 再评估 Vite 或现代 webpack。
4. 最后升级 Electron：
   - 每跨几个大版本做一次可启动 exe。
   - 保留 `scripts/smoke-packaged.js` 作为回归门槛。

## 验证门槛

- `npm run pack` 必须通过。
- 未压缩版和单文件 exe 都必须通过 `scripts/smoke-packaged.js`。
- 必测功能：窗口显示、设置同步、拖动/缩放、老板键、书库导入、书源搜索/预览/导入、应用中心、Excel 数独。
