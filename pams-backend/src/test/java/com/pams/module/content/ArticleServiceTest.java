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
    void review_approve_publishes() {
        Article a = new Article();
        a.setId(1L);
        a.setStatus(Article.ArticleStatus.PENDING);
        when(repo.findById(1L)).thenReturn(Optional.of(a));

        service.review(1L, true, "ok", 100L);

        assertThat(a.getStatus()).isEqualTo(Article.ArticleStatus.PUBLISHED);
        assertThat(a.getPublishTime()).isNotNull();
        assertThat(a.getReviewerId()).isEqualTo(100L);
        assertThat(a.getReviewComment()).isEqualTo("ok");
        verify(repo).save(a);
    }

    @Test
    void review_missing_throws() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.review(9L, true, "x", 100L))
                .isInstanceOf(BizException.class);
        verify(repo, never()).save(any());
    }
}
