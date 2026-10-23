package com.pcdd.sonovel.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** API-compatible Android adapter for the upstream Java 21 virtual-thread helper. */
public final class VirtualThreadLimiter implements AutoCloseable {
    private final ExecutorService executor;
    private final List<Future<?>> pending = new ArrayList<>();

    public VirtualThreadLimiter(int concurrency) {
        executor = Executors.newFixedThreadPool(Math.max(1, Math.min(6, concurrency)));
    }

    public synchronized void submit(Runnable task) {
        pending.add(executor.submit(task));
    }

    @Override public void close() {
        executor.shutdown();
        try {
            for (Future<?> task : pending) task.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("在线任务已取消。", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IllegalStateException("目录读取失败。", error.getCause());
        } finally {
            executor.shutdownNow();
        }
    }
}
