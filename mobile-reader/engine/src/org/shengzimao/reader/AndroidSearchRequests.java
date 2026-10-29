package org.shengzimao.reader;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Encode literal SoNovel URL substitutions; JS URL rules keep their existing semantics. */
public final class AndroidSearchRequests {
    private AndroidSearchRequests() {}
    private static final ThreadLocal<Throwable> failure = new ThreadLocal<>();

    public static void recordFailure(Throwable error) { failure.set(error); }

    public static Throwable takeFailure() {
        Throwable error = failure.get();
        failure.remove();
        return error;
    }

    public static String formatUrl(String template, Object[] values) {
        Object[] encoded = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            // Use the String charset overload available on Android API 26.
            try { encoded[i] = URLEncoder.encode(String.valueOf(values[i]), StandardCharsets.UTF_8.name()).replace("+", "%20"); }
            catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        }
        return String.format(Locale.ROOT, template, encoded);
    }
}
