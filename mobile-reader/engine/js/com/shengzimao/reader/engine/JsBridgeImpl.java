package com.shengzimao.reader.engine;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;

/**
 * 书源规则 @js: 片段的执行器（与 Rhino 一起编入独立的 JS dex）。
 *
 * 之所以用 Java 而不是 Kotlin：这样它只依赖 Rhino 与 java.*，可以脱离 App 的
 * Kotlin 运行时单独预编译成 dex，避免两套 kotlin-stdlib 在类空间里打架。
 */
public final class JsBridgeImpl {

    /** 执行 rules 里的脚本；任何失败返回 null，由引擎回落到原文。 */
    public static String run(String script, String text) {
        String code = "function func(r) {\n" + script + "\n    return r;\n}\n";
        Context context = Context.enter();
        try {
            context.setOptimizationLevel(-1);
            context.setLanguageVersion(Context.VERSION_ES6);
            org.mozilla.javascript.Scriptable scope = context.initSafeStandardObjects();
            context.evaluateString(scope, code, "rule-js", 1, null);
            Object value = scope.get("func", scope);
            if (!(value instanceof Function)) return null;
            Function function = (Function) value;
            Object result = function.call(context, scope, scope, new Object[]{text});
            return Context.toString(result);
        } catch (Throwable error) {
            return null;
        } finally {
            Context.exit();
        }
    }

    private JsBridgeImpl() {
    }
}
