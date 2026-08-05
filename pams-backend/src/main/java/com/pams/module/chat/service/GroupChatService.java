package com.pams.module.chat.service;

import com.pams.common.BizException;
import com.pams.module.chat.dto.GroupChatRequest;
import com.pams.module.chat.entity.GroupChat;
import com.pams.module.chat.entity.GroupChatCategory;
import com.pams.module.chat.entity.GroupChatDepartment;
import com.pams.module.chat.repository.GroupChatCategoryRepository;
import com.pams.module.chat.repository.GroupChatDepartmentRepository;
import com.pams.module.chat.repository.GroupChatRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 群聊管理（PRD F06）
 */
@Service
public class GroupChatService {

    private final GroupChatRepository chatRepo;
    private final GroupChatCategoryRepository categoryRepo;
    private final GroupChatDepartmentRepository departmentRepo;
    private final ActivityRepository activityRepo;
    private final UserRepository userRepo;

    public GroupChatService(GroupChatRepository chatRepo,
                            GroupChatCategoryRepository categoryRepo,
                            GroupChatDepartmentRepository departmentRepo,
                            ActivityRepository activityRepo,
                            UserRepository userRepo) {
        this.chatRepo = chatRepo;
        this.categoryRepo = categoryRepo;
        this.departmentRepo = departmentRepo;
        this.activityRepo = activityRepo;
        this.userRepo = userRepo;
    }

    // ===== 分类管理 =====

    public List<Map<String, Object>> listCategories() {
        return categoryRepo.findAllByOrderBySortOrderAscIdAsc().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("sortOrder", c.getSortOrder());
            m.put("createdAt", c.getCreatedAt());
            return m;
        }).toList();
    }

    @Transactional
    public Long createCategory(String name) {
        if (name == null || name.isBlank()) throw new BizException(2601, "分类名称不能为空");
        GroupChatCategory c = new GroupChatCategory();
        c.setName(name.trim());
        c.setSortOrder((int) categoryRepo.count());
        c.setCreatedAt(LocalDateTime.now());
        return categoryRepo.save(c).getId();
    }

    @Transactional
    public void renameCategory(Long id, String name) {
        if (name == null || name.isBlank()) throw new BizException(2601, "分类名称不能为空");
        GroupChatCategory c = categoryRepo.findById(id)
                .orElseThrow(() -> new BizException(2602, "分类不存在"));
        c.setName(name.trim());
        categoryRepo.save(c);
    }

    @Transactional
    public void deleteCategory(Long id) {
        categoryRepo.deleteById(id);
    }

    @Transactional
    public void sortCategories(List<Long> ids) {
        if (ids == null) return;
        for (int i = 0; i < ids.size(); i++) {
            int order = i; // 显式赋值，保证 effectively final
            categoryRepo.findById(ids.get(i)).ifPresent(c -> {
                c.setSortOrder(order);
                categoryRepo.save(c);
            });
        }
    }

    // ===== 群聊 CRUD =====

    public List<Map<String, Object>> list(String keyword, Long categoryId, String status, String department) {
        List<GroupChat> all = chatRepo.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (GroupChat c : all) {
            List<String> departments = departmentRepo.findByGroupChatId(c.getId())
                    .stream().map(GroupChatDepartment::getDepartment).toList();
            // 筛选
            if (keyword != null && !keyword.isBlank() && !c.getName().contains(keyword.trim())) continue;
            if (categoryId != null && !categoryId.equals(c.getCategoryId())) continue;
            if (status != null && !status.isBlank() && !status.equals(c.getStatus())) continue;
            if (department != null && !department.isBlank() && !departments.contains(department)) continue;
            result.add(toVo(c, departments));
        }
        return result;
    }

    public Map<String, Object> get(Long id) {
        GroupChat c = chatRepo.findById(id).orElseThrow(() -> new BizException(2603, "群聊不存在"));
        List<String> departments = departmentRepo.findByGroupChatId(c.getId())
                .stream().map(GroupChatDepartment::getDepartment).toList();
        return toVo(c, departments);
    }

    private Map<String, Object> toVo(GroupChat c, List<String> departments) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("categoryId", c.getCategoryId());
        m.put("categoryName", c.getCategoryId() == null ? null
                : categoryRepo.findById(c.getCategoryId()).map(GroupChatCategory::getName).orElse(null));
        m.put("activityId", c.getActivityId());
        m.put("activityName", c.getActivityId() == null ? null
                : activityRepo.findById(c.getActivityId()).map(a -> a.getName()).orElse(null));
        m.put("ownerId", c.getOwnerId());
        m.put("ownerName", c.getOwnerId() == null ? null
                : userRepo.findById(c.getOwnerId()).map(u -> u.getRealName()).orElse(null));
        m.put("qrCodeUrl", c.getQrCodeUrl() == null ? "" : c.getQrCodeUrl());
        m.put("remark", c.getRemark() == null ? "" : c.getRemark());
        m.put("status", c.getStatus());
        m.put("departments", departments);
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    @Transactional
    public Long create(Long userId, GroupChatRequest req) {
        GroupChat c = new GroupChat();
        apply(c, req);
        c.setCreatedBy(userId);
        c.setDeleted(0);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        GroupChat saved = chatRepo.save(c);
        saveDepartments(saved.getId(), req.getDepartments());
        return saved.getId();
    }

    @Transactional
    public void update(Long id, GroupChatRequest req) {
        GroupChat c = chatRepo.findById(id).orElseThrow(() -> new BizException(2603, "群聊不存在"));
        apply(c, req);
        c.setUpdatedAt(LocalDateTime.now());
        chatRepo.save(c);
        saveDepartments(id, req.getDepartments());
    }

    /** 归档：状态置为 ARCHIVED */
    @Transactional
    public void archive(Long id) {
        GroupChat c = chatRepo.findById(id).orElseThrow(() -> new BizException(2603, "群聊不存在"));
        c.setStatus("ARCHIVED");
        c.setUpdatedAt(LocalDateTime.now());
        chatRepo.save(c);
    }

    @Transactional
    public void delete(Long id) {
        GroupChat c = chatRepo.findById(id).orElseThrow(() -> new BizException(2603, "群聊不存在"));
        c.setDeleted(1);
        c.setUpdatedAt(LocalDateTime.now());
        chatRepo.save(c);
        departmentRepo.deleteByGroupChatId(id);
    }

    private void apply(GroupChat c, GroupChatRequest req) {
        c.setName(req.getName());
        c.setCategoryId(req.getCategoryId());
        c.setActivityId(req.getActivityId());
        c.setOwnerId(req.getOwnerId());
        c.setQrCodeUrl(req.getQrCodeUrl());
        c.setRemark(req.getRemark());
        c.setStatus(req.getStatus() == null ? "ACTIVE" : req.getStatus());
    }

    private void saveDepartments(Long groupChatId, List<String> departments) {
        departmentRepo.deleteByGroupChatId(groupChatId);
        if (departments == null) return;
        for (String d : departments) {
            GroupChatDepartment gd = new GroupChatDepartment();
            gd.setGroupChatId(groupChatId);
            gd.setDepartment(d);
            departmentRepo.save(gd);
        }
    }
}
