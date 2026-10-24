package org.shengzimao.reader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Shared target for every upstream Java 21 executor factory, including indirect utility calls. */
public final class AndroidExecutors {
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
