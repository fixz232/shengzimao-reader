'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');
const commands = new Set(['reading-section','reading-mode','normal-window','online-consent','online-link','online-details','online-update','online-track','page-chunk','snapshot', 'import', 'import-dropped', 'open', 'read', 'move', 'bookmark', 'bookmark-remove', 'favorite', 'remove', 'books-update', 'books-remove', 'chapters', 'clear-history', 'settings', 'settings-reset', 'choose-icon', 'reset-icon', 'convert', 'local-search', 'legacy-scan', 'legacy-migrate', 'search', 'online-cancel', 'online-download', 'online-download-many', 'online-retry', 'copy-link', 'engine-probe', 'windows-list', 'attachment-start', 'attachment-stop', 'app-open', 'reader-context-menu', 'window-state', 'window-bounds', 'reading-tick', 'show-manager', 'show-reader', 'hide-reader', 'quit', 'backup-export', 'backup-import', 'open-profile']);
contextBridge.exposeInMainWorld('reader', {
  onTtsStop: callback => {const listener=()=>callback();ipcRenderer.on('reader:tts-stop',listener);return ()=>ipcRenderer.removeListener('reader:tts-stop',listener);},
  request: async (command, payload = {}) => {
    if (!commands.has(command)) throw new Error('操作未授权。');
    const response = await ipcRenderer.invoke('reader:request', { command, payload });
    if (!response.ok) throw new Error(response.error);
    return response.value;
  },
  pathForFile: file => webUtils.getPathForFile(file),
  onTtsToggle: callback => {
    const listener = () => callback();
    ipcRenderer.on('reader:tts-toggle', listener);
    return () => ipcRenderer.removeListener('reader:tts-toggle', listener);
  },
  onNavigate: callback => {
    const listener = (_event, navigation) => callback(navigation);
    ipcRenderer.on('reader:navigate', listener);
    return () => ipcRenderer.removeListener('reader:navigate', listener);
  },
  onChanged: callback => {
    const listener = () => callback();
    ipcRenderer.on('reader:changed', listener);
    return () => ipcRenderer.removeListener('reader:changed', listener);
  }
});
