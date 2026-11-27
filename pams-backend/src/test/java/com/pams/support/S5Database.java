package com.pams.support;

/** S5 writes synthetic business data, so both database engines require an isolated target. */
public final class S5Database {
    private S5Database() {}

    public static boolean postgresql(String url) {
        if (url == null || !url.matches("jdbc:(mysql|postgresql)://127\\.0\\.0\\.1:[0-9]+/pams_s5_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("S5 requires an isolated pams_s5_* database on a loopback port");
        }
        return url.startsWith("jdbc:postgresql:");
    }
}
