'use strict';
const { CancellationToken } = require('builder-util-runtime');

function releaseNotesText(value) {
  const entries = Array.isArray(value) ? value.map(item => typeof item === 'string' ? item : item?.note || '') : [value || ''];
  return entries.join('\n').trim().slice(0, 12000);
}

function errorText(error) {
  const message = String(error?.message || error || '未知错误');
  if (error?.code === 'ERR_UPDATER_CHANNEL_FILE_NOT_FOUND') return 'GitHub 尚未发布桌面版更新信息，请稍后重试或打开版本发布页。';
  if (/checksum|ERR_UPDATER_INVALID_SIGNATURE/i.test(message + ' ' + error?.code)) return '下载文件校验失败，请重新检查并下载更新。';
  return message.split('\n')[0].slice(0, 500);
}

class DesktopUpdater {
  constructor({ updater, isPackaged, platform, onState = () => {} }) {
    if (!updater || typeof updater.on !== 'function') throw new TypeError('缺少桌面更新器。');
    this.updater = updater;
    this.supported = isPackaged === true && platform === 'win32';
    this.onState = onState;
    this.pending = null;
    this.downloadToken = null;
    this.installBusy = false;
    this.state = { phase: 'idle', version: '', releaseNotes: '', progress: 0, error: '', checkedAt: 0 };
    updater.autoDownload = false;
    updater.autoInstallOnAppQuit = false;
    updater.allowPrerelease = false;
    updater.allowDowngrade = false;
    updater.disableWebInstaller = true;
    updater.on('update-available', info => { if (this.state.phase === 'checking') this.set({ phase: 'available', version: String(info.version), releaseNotes: releaseNotesText(info.releaseNotes), progress: 0, checkedAt: Date.now() }); });
    updater.on('update-not-available', () => { if (this.state.phase === 'checking') this.set({ phase: 'not-available', version: '', releaseNotes: '', progress: 0, checkedAt: Date.now() }); });
    updater.on('download-progress', info => { if (this.state.phase === 'downloading' && !this.downloadToken?.cancelled) this.set({ progress: Math.max(0, Math.min(100, Number(info.percent) || 0)) }); });
    updater.on('update-downloaded', info => { if (this.state.phase === 'downloading' && !this.downloadToken?.cancelled) this.set({ phase: 'downloaded', version: String(info.version || this.state.version), progress: 100, error: '' }); });
    updater.on('error', error => { if (!this.downloadToken?.cancelled) this.fail(error); });
  }

  snapshot() { return { ...this.state, supported: this.supported }; }

  set(patch) {
    this.state = { ...this.state, ...patch };
    this.onState(this.snapshot());
  }

  fail(error) {
    this.set({ phase: 'error', error: errorText(error) });
  }

  ensureSupported() {
    if (!this.supported) throw new Error('自动更新仅适用于 Windows 安装版；可通过 GitHub Releases 手动获取新版本。');
  }

  check() {
    this.ensureSupported();
    if (this.pending || ['downloaded', 'confirming', 'installing'].includes(this.state.phase)) throw new Error('更新任务正在进行，请稍候或安装已下载的更新。');
    this.set({ phase: 'checking', version: '', releaseNotes: '', progress: 0, error: '' });
    this.pending = Promise.resolve().then(() => this.updater.checkForUpdates()).catch(error => this.fail(error)).finally(() => { this.pending = null; });
    return this.snapshot();
  }

  download() {
    this.ensureSupported();
    if (this.state.phase !== 'available' || this.pending) throw new Error('请先检查并确认有可用更新，或等待当前任务结束。');
    const token = this.downloadToken = new CancellationToken();
    this.set({ phase: 'downloading', progress: 0, error: '' });
    this.pending = Promise.resolve().then(() => this.updater.downloadUpdate(token)).catch(error => { if (!token.cancelled) this.fail(error); }).finally(() => { this.pending = null; this.downloadToken = null; token.dispose(); if (token.cancelled) this.set({ phase: 'available', progress: 0, error: '' }); });
    return this.snapshot();
  }

  cancel() {
    this.ensureSupported();
    if (this.state.phase !== 'downloading' || !this.downloadToken) throw new Error('当前没有正在下载的更新。');
    this.set({ phase: 'cancelling' });
    this.downloadToken.cancel();
    return this.snapshot();
  }

  async install({ confirm, flushProgress }) {
    this.ensureSupported();
    if (this.state.phase !== 'downloaded' || this.installBusy) throw new Error('更新尚未下载完成，或安装已在进行。');
    this.installBusy = true;
    this.set({ phase: 'confirming', error: '' });
    try {
      if (!await confirm()) return false;
      await this.pending;
      if (this.state.phase !== 'confirming') throw new Error('更新状态已改变，请重新检查更新。');
      await flushProgress();
      this.set({ phase: 'installing' });
      this.updater.quitAndInstall(false, true);
      return this.state.phase !== 'error';
    } catch (error) {
      this.set({ phase: this.state.phase === 'confirming' ? 'downloaded' : 'error', error: errorText(error) });
      throw error;
    } finally {
      this.installBusy = false;
      if (this.state.phase === 'confirming') this.set({ phase: 'downloaded' });
    }
  }
}

module.exports = { DesktopUpdater, releaseNotesText };
