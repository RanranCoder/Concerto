package com.pams.module.notification.dto;

import lombok.Data;

@Data
public class NotificationPreferenceVO {
    private String type;
    private boolean enabled;
    private boolean system;

    public static NotificationPreferenceVO of(String type, boolean enabled, boolean system) {
        NotificationPreferenceVO vo = new NotificationPreferenceVO();
        vo.setType(type);
        vo.setEnabled(enabled);
        vo.setSystem(system);
        return vo;
    }
}
