package com.pams.module.content;

import com.pams.common.BizException;
import com.pams.module.content.dto.NewsRequest;
import com.pams.module.content.entity.News;
import com.pams.module.content.repository.NewsRepository;
import com.pams.module.content.service.NewsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PRD-v5 F1：新闻稿发布闭环 */
class NewsServiceTest {

    private News news(News.NewsStatus status) {
        News n = new News();
        n.setId(1L);
        n.setTitle("标题");
        n.setStatus(status);
        return n;
    }

    @Test
    void publish_draftToPublished_setsToday() {
        NewsRepository repo = mock(NewsRepository.class);
        NewsService svc = new NewsService(repo);
        News n = news(News.NewsStatus.DRAFT);
        when(repo.findById(1L)).thenReturn(Optional.of(n));
        when(repo.save(any(News.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.publish(1L);

        assertThat(n.getStatus()).isEqualTo(News.NewsStatus.PUBLISHED);
        assertThat(n.getPublishDate()).isEqualTo(LocalDate.now());
        assertThat(n.getUpdatedAt()).isNotNull();
    }

    @Test
    void publish_alreadyPublished_rejected() {
        NewsRepository repo = mock(NewsRepository.class);
        NewsService svc = new NewsService(repo);
        when(repo.findById(1L)).thenReturn(Optional.of(news(News.NewsStatus.PUBLISHED)));

        assertThatThrownBy(() -> svc.publish(1L))
                .isInstanceOf(BizException.class)
                .hasMessage("该新闻稿已发布");
    }

    @Test
    void create_startsAsDraft() {
        NewsRepository repo = mock(NewsRepository.class);
        NewsService svc = new NewsService(repo);
        when(repo.save(any(News.class))).thenAnswer(inv -> inv.getArgument(0));
        NewsRequest req = new NewsRequest();
        req.setTitle("新稿");
        req.setContent("正文");

        News n = svc.create(9L, req);

        assertThat(n.getStatus()).isEqualTo(News.NewsStatus.DRAFT);
        assertThat(n.getDeleted()).isZero();
    }
}
