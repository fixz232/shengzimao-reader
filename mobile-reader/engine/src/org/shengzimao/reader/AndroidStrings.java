package org.shengzimao.reader;

/** Java 11 String 空白工具的 API 26 兼容实现，保留 Unicode 空白语义。 */
public final class AndroidStrings {
    private AndroidStrings() {}
    public static boolean isBlank(String value) {
        for (int i = 0; i < value.length();) {
            int point = value.codePointAt(i);
            if (!Character.isWhitespace(point)) return false;
            i += Character.charCount(point);
        }
        return true;
    }
    public static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
}
