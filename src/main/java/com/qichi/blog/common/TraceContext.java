package com.qichi.blog.common;

import java.security.SecureRandom;

public final class TraceContext {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {}

    public static String generate() {
        char[] out = new char[12];
        for (int i = 0; i < out.length; i++) {
            out[i] = HEX[RANDOM.nextInt(HEX.length)];
        }
        return new String(out);
    }

    public static void set(String traceId) { CURRENT.set(traceId); }

    public static String current() {
        String id = CURRENT.get();
        return id != null ? id : "-";
    }

    public static void clear() { CURRENT.remove(); }
}
