package com.pcdd.sonovel.utils;

import org.shengzimao.reader.JavaScriptBridge;

/** Android replacement for the upstream Javet entry point; no native V8 dependency. */
public final class JsCaller {
    public static String call(String script, String text) {
        return JavaScriptBridge.call(script, text);
    }

    public static Object callFunction(String script, String functionName, Object... arguments) {
        if (!functionName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("Invalid JS function name");
        String args = cn.hutool.json.JSONUtil.toJsonStr(arguments);
        return JavaScriptBridge.call("r=String((function(){" + script + ";return " + functionName + ".apply(null," + args + ");})());", "");
    }

    public static String eval(String script) {
        return JavaScriptBridge.call("r=String(eval(" + cn.hutool.json.JSONUtil.toJsonStr(script) + "));", "");
    }

    private JsCaller() {}
}
