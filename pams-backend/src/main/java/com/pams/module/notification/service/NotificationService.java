package com.pams.module.notification.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.entity.User;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        // 铃铛列表只取前 50 条（分页下沉 DB），并对 sender 批量取数避免 N+1
        List<Notification> list = repo.pageForUser(userId, roleCode, deptId, null, PageRequest.of(0, 50));
        return toVOs(list);
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
        pr.setRecords(toVOs(list));
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

    /** 批量 toVO：一次 findAllById 取所有发送者姓名，消除逐条 userRepo.findById 的 N+1。 */
    private List<NotificationVO> toVOs(List<Notification> list) {
        Set<Long> senderIds = list.stream()
            .map(Notification::getSenderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> senderNames = senderIds.isEmpty()
            ? Map.of()
            : userRepo.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, User::getRealName, (a, b) -> a));
        return list.stream().map(n -> {
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
            vo.setSenderName(n.getSenderId() == null ? null : senderNames.get(n.getSenderId()));
            return vo;
        }).toList();
    }
}
