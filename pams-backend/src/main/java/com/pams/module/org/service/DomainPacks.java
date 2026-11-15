package com.pams.module.org.service;

import java.util.List;

public final class DomainPacks {
    public static final String PARTY = "party";
    public static final String YOUTH = "youth";
    public static final String VOLUNTEER = "volunteer";
    public static final List<String> CODES = List.of(PARTY, YOUTH, VOLUNTEER);

    private DomainPacks() {}

    public static String forPermission(String permission) {
        int separator = permission.indexOf(':');
        if (separator < 0) return null;
        String code = permission.substring(0, separator);
        return CODES.contains(code) ? code : null;
    }
}
