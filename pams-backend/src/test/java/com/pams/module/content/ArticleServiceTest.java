package com.pams.module.content;

import com.pams.common.BizException;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.content.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ArticleServiceTest {

    ArticleRepository repo;
    ArticleService service;

    @BeforeEach
    void setup() {
        repo = mock(ArticleRepository.class);
        service = new ArticleService(repo);
    }

    @Test
    void review_approve_moves_to_approved() {
        Article a = new Article();
        a.setId(1L);
        a.setStatus(Article.ArticleStatus.PENDING);
        when(repo.findById(1L)).thenReturn(Optional.of(a));
        service.review(1L, true, "ok", 100L);
        assertThat(a.getStatus()).isEqualTo(Article.ArticleStatus.APPROVED);
        assertThat(a.getReviewerId()).isEqualTo(100L);
        assertThat(a.getReviewComment()).isEqualTo("ok");
        assertThat(a.getPublishTime()).isNull();
        verify(repo).save(a);
    }

    @Test
    void review_reject_moves_to_rejected() {
        Article a = new Article();
        a.setId(2L);
        a.setStatus(Article.ArticleStatus.PENDING);
        when(repo.findById(2L)).thenReturn(Optional.of(a));
        service.review(2L, false, "改标题", 100L);
        assertThat(a.getStatus()).isEqualTo(Article.ArticleStatus.REJECTED);
        assertThat(a.getReviewComment()).isEqualTo("改标题");
    }

    @Test
    void publish_requires_approved_status() {
        Article a = new Article();
        a.setId(3L);
        a.setStatus(Article.ArticleStatus.DRAFT);
        a.setAuthorId(50L);
        when(repo.findById(3L)).thenReturn(Optional.of(a));
        com.pams.module.content.dto.PublishRequest req = new com.pams.module.content.dto.PublishRequest();
        req.setWxUrl("https://mp.weixin.qq.com/s/abc");
        assertThatThrownBy(() -> service.publish(3L, req, 50L, false))
                .isInstanceOf(BizException.class);
    }

    @Test
    void publish_sets_published_and_wx_url() {
        Article a = new Article();
        a.setId(4L);
        a.setStatus(Article.ArticleStatus.APPROVED);
        a.setAuthorId(50L);
        when(repo.findById(4L)).thenReturn(Optional.of(a));
        com.pams.module.content.dto.PublishRequest req = new com.pams.module.content.dto.PublishRequest();
        req.setWxUrl("https://mp.weixin.qq.com/s/abc");
        service.publish(4L, req, 50L, false);
        assertThat(a.getStatus()).isEqualTo(Article.ArticleStatus.PUBLISHED);
        assertThat(a.getWxUrl()).isEqualTo("https://mp.weixin.qq.com/s/abc");
        assertThat(a.getPublishTime()).isNotNull();
    }

    @Test
    void update_only_allows_draft_or_rejected() {
        Article a = new Article();
        a.setId(5L);
        a.setStatus(Article.ArticleStatus.PENDING);
        a.setAuthorId(50L);
        when(repo.findById(5L)).thenReturn(Optional.of(a));
        com.pams.module.content.dto.ArticleRequest req = new com.pams.module.content.dto.ArticleRequest();
        req.setTitle("新标题");
        assertThatThrownBy(() -> service.update(5L, req, 50L, false))
                .isInstanceOf(BizException.class);
    }

    @Test
    void update_denies_non_author_non_leader() {
        Article a = new Article();
        a.setId(6L);
        a.setStatus(Article.ArticleStatus.DRAFT);
        a.setAuthorId(50L);
        when(repo.findById(6L)).thenReturn(Optional.of(a));
        com.pams.module.content.dto.ArticleRequest req = new com.pams.module.content.dto.ArticleRequest();
        req.setTitle("新标题");
        assertThatThrownBy(() -> service.update(6L, req, 99L, false))
                .isInstanceOf(BizException.class);
    }

    @Test
    void create_requires_author_id() {
        com.pams.module.content.dto.ArticleRequest req = new com.pams.module.content.dto.ArticleRequest();
        req.setTitle("预热");
        req.setActivityId(1L);
        req.setDeadline(java.time.LocalDateTime.now().plusDays(1));
        assertThatThrownBy(() -> service.create(10L, req))
                .isInstanceOf(BizException.class);
    }

    @Test
    void review_missing_throws() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.review(9L, true, "x", 100L))
                .isInstanceOf(BizException.class);
        verify(repo, never()).save(any());
    }
}
