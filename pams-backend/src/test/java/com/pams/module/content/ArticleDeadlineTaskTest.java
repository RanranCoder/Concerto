package com.pams.module.content;

import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.content.task.ArticleDeadlineTask;
import com.pams.module.notification.event.ArticleDeadlineReminderEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArticleDeadlineTaskTest {

    @Test
    void remindOverdue_publishes_event_and_sets_reminded_at() {
        ArticleRepository repo = mock(ArticleRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ArticleDeadlineTask task = new ArticleDeadlineTask(repo, publisher);

        Article a = new Article();
        a.setId(1L);
        a.setTitle("预热");
        a.setActivityId(5L);
        a.setAuthorId(50L);
        a.setDeadline(LocalDateTime.now().plusDays(1));
        a.setStatus(Article.ArticleStatus.DRAFT);
        when(repo.findOverdue(any())).thenReturn(List.of(a));

        task.remindOverdue();

        verify(publisher).publishEvent(any(ArticleDeadlineReminderEvent.class));
        assertThat(a.getDeadlineRemindedAt()).isNotNull();
        verify(repo).save(a);
    }

    @Test
    void remindOverdue_skips_already_reminded_today() {
        ArticleRepository repo = mock(ArticleRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ArticleDeadlineTask task = new ArticleDeadlineTask(repo, publisher);

        Article a = new Article();
        a.setId(2L);
        a.setTitle("报道");
        a.setActivityId(5L);
        a.setAuthorId(50L);
        a.setDeadline(LocalDateTime.now().plusDays(1));
        a.setStatus(Article.ArticleStatus.DRAFT);
        a.setDeadlineRemindedAt(LocalDateTime.now()); // 今天已提醒过
        when(repo.findOverdue(any())).thenReturn(List.of(a));

        task.remindOverdue();

        verify(publisher, never()).publishEvent(any(ArticleDeadlineReminderEvent.class));
        verify(repo, never()).save(a);
    }
}
