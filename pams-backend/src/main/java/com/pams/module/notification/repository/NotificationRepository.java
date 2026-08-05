package com.pams.module.notification.repository;

import com.pams.module.notification.entity.Notification;
import com.pams.module.notification.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
        SELECT n FROM Notification n
        WHERE n.deleted = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
        ORDER BY n.createdAt DESC
    """)
    List<Notification> findForUser(@Param("userId") Long userId,
                                   @Param("roleCode") String roleCode,
                                   @Param("deptId") Long deptId);

    @Query("""
        SELECT COUNT(n) FROM Notification n
        WHERE n.deleted = 0 AND n.isRead = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
    """)
    long countUnreadForUser(@Param("userId") Long userId,
                            @Param("roleCode") String roleCode,
                            @Param("deptId") Long deptId);

    @Query("""
        SELECT n FROM Notification n
        WHERE n.deleted = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
          AND (:type IS NULL OR n.type = :type)
        ORDER BY n.createdAt DESC
    """)
    List<Notification> pageForUser(@Param("userId") Long userId,
                                   @Param("roleCode") String roleCode,
                                   @Param("deptId") Long deptId,
                                   @Param("type") NotificationType type,
                                   org.springframework.data.domain.Pageable pageable);

    @Query("""
        SELECT COUNT(n) FROM Notification n
        WHERE n.deleted = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
          AND (:type IS NULL OR n.type = :type)
    """)
    long countForUser(@Param("userId") Long userId,
                      @Param("roleCode") String roleCode,
                      @Param("deptId") Long deptId,
                      @Param("type") NotificationType type);

    @Modifying
    @Query("""
        UPDATE Notification n SET n.isRead = 1, n.readAt = :now
        WHERE n.deleted = 0 AND n.isRead = 0
          AND (n.recipientId = :userId
               OR n.recipientRole = :roleCode
               OR n.recipientDeptId = :deptId)
    """)
    void markAllAsRead(@Param("userId") Long userId,
                       @Param("roleCode") String roleCode,
                       @Param("deptId") Long deptId,
                       @Param("now") LocalDateTime now);
}
