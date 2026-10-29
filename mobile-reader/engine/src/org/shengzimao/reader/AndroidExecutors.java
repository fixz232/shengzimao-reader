package org.shengzimao.reader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Shared target for every upstream Java 21 executor factory, including indirect utility calls. */
public final class AndroidExecutors {
    /** 上游命令行代码不能终止承载阅读器的 Android 进程。 */
    public static void preventProcessExit(int code) {
        throw new IllegalStateException("书源解析中止（代码 " + code + "），请重试或更换书源。");
    }

    public static ExecutorService newWorkerPool() { return Executors.newFixedThreadPool(6); }

    public static void close(ExecutorService executor) {
        executor.shutdown();
        try {
            while (!executor.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            }
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private AndroidExecutors() {}
}
