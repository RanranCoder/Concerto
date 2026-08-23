package com.pams.module.chat.service;

import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.chat.entity.GroupChat;
import com.pams.module.chat.entity.GroupChatCategory;
import com.pams.module.chat.entity.GroupChatDepartment;
import com.pams.module.chat.repository.GroupChatCategoryRepository;
import com.pams.module.chat.repository.GroupChatDepartmentRepository;
import com.pams.module.chat.repository.GroupChatRepository;
import com.pams.repository.UserRepository;
import com.pams.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupChatServiceTest {

    @Test
    void deleteCategory_clearsChatReferencesBeforeDelete() {
        // 删除分类前必须先批量置空引用它的群聊 category_id，再删除分类，避免孤儿引用
        GroupChatRepository chatRepo = mock(GroupChatRepository.class);
        GroupChatCategoryRepository categoryRepo = mock(GroupChatCategoryRepository.class);
        GroupChatService svc = new GroupChatService(
                chatRepo, categoryRepo,
                mock(GroupChatDepartmentRepository.class),
                mock(ActivityRepository.class),
                mock(UserRepository.class));

        svc.deleteCategory(7L);

        InOrder inOrder = inOrder(chatRepo, categoryRepo);
        inOrder.verify(chatRepo).clearCategoryId(7L);
        inOrder.verify(categoryRepo).deleteById(7L);
    }

    @Test
    void list_batchesLookups_notNPlusOne() {
        // 群聊列表必须批量加载分类/活动/群主名称，而非逐条 findById
        GroupChatCategory category = new GroupChatCategory();
        category.setId(10L);
        category.setName("测试分类");

        GroupChat chat = new GroupChat();
        chat.setId(1L);
        chat.setName("测试群聊");
        chat.setCategoryId(10L);

        GroupChatDepartment dept = new GroupChatDepartment();
        dept.setGroupChatId(1L);
        dept.setDepartment("文秘部");

        GroupChatRepository chatRepo = mock(GroupChatRepository.class);
        GroupChatCategoryRepository categoryRepo = mock(GroupChatCategoryRepository.class);
        GroupChatDepartmentRepository departmentRepo = mock(GroupChatDepartmentRepository.class);
        ActivityRepository activityRepo = mock(ActivityRepository.class);
        UserRepository userRepo = mock(UserRepository.class);

        when(chatRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(chat));
        when(departmentRepo.findByGroupChatIdIn(any())).thenReturn(List.of(dept));
        when(categoryRepo.findAllById(any())).thenReturn(List.of(category));
        when(activityRepo.findAllById(any())).thenReturn(List.of());
        when(userRepo.findAllById(any())).thenReturn(List.of());

        GroupChatService svc = new GroupChatService(chatRepo, categoryRepo, departmentRepo, activityRepo, userRepo);
        var result = svc.list(null, null, null, null);

        // 验证使用了批量方法而非逐条
        verify(departmentRepo).findByGroupChatIdIn(any());
        verify(categoryRepo).findAllById(any());
        verify(activityRepo).findAllById(any());
        verify(userRepo).findAllById(any());
        assertThat(result.size()).isEqualTo(1);
    }
}
