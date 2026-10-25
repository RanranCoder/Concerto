package com.pams.module.agent.core;

import java.util.*;

/** Compatibility boundary for live tool references and old persisted links. */
public final class BusinessLinks {
    private BusinessLinks() {}
    public static String normalize(String link) {
        if (link == null) return null;
        var activity = java.util.regex.Pattern.compile("^/activity/(\\d+)(?:/(tasks|seat|signin))?(\\?.*)?$").matcher(link);
        if (activity.matches()) {
            String root="/activities/"+activity.group(1);
            String section=activity.group(2);
            return section==null?root+(activity.group(3)==null?"":activity.group(3))
                : "tasks".equals(section)?root+"/gantt":root+"?tab="+section;
        }
        for (String[] pair : List.of(new String[]{"/member?id=","/members/"},new String[]{"/party/member?id=","/party/members/"})) {
            if (link.matches(java.util.regex.Pattern.quote(pair[0])+"\\d+")) return pair[1]+link.substring(pair[0].length());
        }
        for (String[] pair : List.of(new String[]{"/content/article","/content/articles"},
                new String[]{"/archive/material","/archive/materials"},new String[]{"/archive/credit","/archive/credits"},
                new String[]{"/archive/announcement","/archive/announcements"},new String[]{"/notification","/notifications"})) {
            if (link.equals(pair[0]) || link.startsWith(pair[0]+"?")) return pair[1]+link.substring(pair[0].length());
        }
        return link;
    }
    public static List<Map<String,Object>> references(List<Map<String,Object>> refs) {
        if (refs == null) return List.of();
        return refs.stream().map(ref -> {
            Map<String,Object> copy = new LinkedHashMap<>(ref);
            if (copy.get("link") instanceof String link) copy.put("link",normalize(link));
            return copy;
        }).toList();
    }
}
