package org.shengzimao.reader;

/**
 * 书源规则里 {@code @js:} 片段的执行出口。
 * SoNovel 的 JsCaller 原本用 Javet（含 10 MB 的 Windows V8 dll），安卓上不可用；
 * 这里改由 Kotlin 侧注入的 Rhino 实现执行同样形态的脚本。
 */
public final class JavaScriptBridge {
    private static volatile AndroidEngine.JsBridge current;

    static void install(AndroidEngine.JsBridge bridge) {
        current = bridge;
    }

    public static String call(String script, String text) {
        AndroidEngine.JsBridge bridge = current;
        if (bridge == null) return text;
        try {
            return bridge.call(script, text);
        } catch (Throwable error) {
            return text;
        }
    }

    private JavaScriptBridge() {
    }
}
