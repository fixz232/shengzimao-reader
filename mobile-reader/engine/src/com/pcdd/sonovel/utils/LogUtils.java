package com.pcdd.sonovel.utils;

import com.pcdd.sonovel.model.LogLevel;
import cn.hutool.core.util.StrUtil;
import java.io.File;

/** Android logs go to the process log, never to a desktop-relative logs/ directory. */
public final class LogUtils {
    private static volatile File logFile;

    public static void configureDirectory(String directory) { logFile = new File(directory, "engine.log"); }
    public static File getLogFile() {
        if (logFile == null) throw new IllegalStateException("引擎日志目录尚未配置。");
        return logFile;
    }
    public static void logByProgressbar(LogLevel level, int enabled, String message, Object... args) { write(message, args); }
    public static void info(String message, Object... args) { write(message, args); }
    public static void infoConsole(String message, Object... args) { write(message, args); }
    public static void warn(String message, Object... args) { write(message, args); }
    public static void warnConsole(String message, Object... args) { write(message, args); }
    public static void error(String message, Object... args) { write(message, args); }
    public static void errorConsole(String message, Object... args) { write(message, args); }
    public static void error(Throwable error, String message, Object... args) { write(message, args); error.printStackTrace(System.err); }
    private static void write(String message, Object... args) { System.err.println("[sonovel] " + StrUtil.format(message, args)); }
    private LogUtils() {}
}
