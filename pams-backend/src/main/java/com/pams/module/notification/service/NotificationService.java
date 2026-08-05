package com.pams.module.notification.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.notification.dto.NotificationPreferenceVO;
import com.pams.module.notification.dto.NotificationVO;
import com.pams.module.notification.entity.Notification;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.repository.NotificationRepository;
import com.pams.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final UserRepository userRepo;
    // 通知偏好本期轻量实现：内存按用户维度存取（重启即清空，不落库、不改表结构）
    private final java.util.concurrent.ConcurrentHashMap<Long, Map<String, Boolean>> prefStore = new java.util.concurrent.ConcurrentHashMap<>();

    public NotificationService(NotificationRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    @Transactional
    public Notification createAndSave(NotificationType type, String title, String content,
                                      String entityType, Long entityId, Long senderId,
                                      Long recipientId, String recipientRole, Long recipientDeptId) {
        Notification n = new Notification();
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setEntityType(entityType);
        n.setEntityId(entityId);
        n.setSenderId(senderId);
        n.setRecipientId(recipientId);
        n.setRecipientRole(recipientRole);
        n.setRecipientDeptId(recipientDeptId);
        n.setIsRead(0);
        n.setDeleted(0);
        n.setCreatedAt(LocalDateTime.now());
        return repo.save(n);
    }

    public List<NotificationVO> findForUser(Long userId, String roleCode, Long deptId) {
        List<Notification> list = repo.findForUser(userId, roleCode, deptId);
        return list.stream().map(this::toVO).toList();
    }

    public long countUnreadForUser(Long userId, String roleCode, Long deptId) {
        return repo.countUnreadForUser(userId, roleCode, deptId);
    }

    public PageResult<NotificationVO> pageForUser(Long userId, String roleCode, Long deptId,
                                                  NotificationType type, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<Notification> list = repo.pageForUser(userId, roleCode, deptId, type,
            PageRequest.of(safePage - 1, safeSize));
        long total = repo.countForUser(userId, roleCode, deptId, type);
        PageResult<NotificationVO> pr = new PageResult<>();
        pr.setRecords(list.stream().map(this::toVO).toList());
        pr.setTotal(total);
        pr.setCurrent(safePage);
        pr.setSize(safeSize);
        return pr;
    }

    public List<NotificationPreferenceVO> getPreferences(Long userId) {
        Map<String, Boolean> userPrefs = prefStore.getOrDefault(userId, java.util.Map.of());
        List<NotificationPreferenceVO> result = new java.util.ArrayList<>();
        for (NotificationType t : NotificationType.values()) {
            boolean system = t == NotificationType.SYSTEM;
            boolean enabled = system || userPrefs.getOrDefault(t.name(), true);
            result.add(NotificationPreferenceVO.of(t.name(), enabled, system));
        }
        return result;
    }

    public void savePreferences(Long userId, List<NotificationPreferenceVO> prefs) {
        if (prefs == null) return;
        Map<String, Boolean> userPrefs = new java.util.concurrent.ConcurrentHashMap<>();
        for (NotificationPreferenceVO p : prefs) {
            if (p == null || p.getType() == null || p.isSystem()) continue;
            try {
                NotificationType.valueOf(p.getType());
                userPrefs.put(p.getType(), p.isEnabled());
            } catch (IllegalArgumentException ignored) {
                // 未知类型忽略，不做库结构变更
            }
        }
        prefStore.put(userId, userPrefs);
    }

    @Transactional
    public void markAsRead(Long id, Long userId) {
        Notification n = repo.findById(id)
            .orElseThrow(() -> new BizException(2050, "通知不存在"));
        if (n.getIsRead() == 0) {
            n.setIsRead(1);
            n.setReadAt(LocalDateTime.now());
            repo.save(n);
        }
    }

    @Transactional
    public void markAllAsRead(Long userId, String roleCode, Long deptId) {
        repo.markAllAsRead(userId, roleCode, deptId, LocalDateTime.now());
    }

    private NotificationVO toVO(Notification n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType().name());
        vo.setTitle(n.getTitle());
        vo.setContent(n.getContent());
        vo.setEntityType(n.getEntityType());
        vo.setEntityId(n.getEntityId());
        vo.setPriority("NORMAL");
        vo.setRead(n.getIsRead() == 1);
        vo.setCreatedAt(n.getCreatedAt());
        if (n.getSenderId() != null) {
            userRepo.findById(n.getSenderId())
                .ifPresent(u -> vo.setSenderName(u.getRealName()));
        }
        return vo;
    }
}
