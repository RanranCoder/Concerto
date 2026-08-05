package com.pams.module.notification.service;

import com.pams.common.BizException;
import com.pams.module.notification.dto.NotificationVO;
import com.pams.module.notification.entity.Notification;
import com.pams.module.notification.entity.NotificationType;
import com.pams.module.notification.repository.NotificationRepository;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final UserRepository userRepo;

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
        vo.setRead(n.getIsRead() == 1);
        vo.setCreatedAt(n.getCreatedAt());
        if (n.getSenderId() != null) {
            userRepo.findById(n.getSenderId())
                .ifPresent(u -> vo.setSenderName(u.getRealName()));
        }
        return vo;
    }
}
