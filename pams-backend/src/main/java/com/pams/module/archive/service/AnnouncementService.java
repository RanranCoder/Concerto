package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.module.archive.dto.AnnouncementRequest;
import com.pams.module.archive.entity.Announcement;
import com.pams.module.archive.entity.AnnouncementRead;
import com.pams.module.archive.entity.AnnouncementReadId;
import com.pams.module.archive.repository.AnnouncementReadRepository;
import com.pams.module.archive.repository.AnnouncementRepository;
import com.pams.module.notification.event.AnnouncementPublishedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AnnouncementService {
    private final AnnouncementRepository repository;
    private final AnnouncementReadRepository readRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AnnouncementService(AnnouncementRepository repository,
                               AnnouncementReadRepository readRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.readRepository = readRepository;
        this.eventPublisher = eventPublisher;
    }

    public List<Announcement> list() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    public Announcement getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2404, "公告不存在"));
    }

    @Transactional
    public Announcement create(Long publisherId, AnnouncementRequest req) {
        Announcement a = new Announcement();
        a.setPublisherId(publisherId);
        a.setCreatedAt(LocalDateTime.now());
        a.setDeleted(0);
        apply(a, req);
        Announcement saved = repository.save(a);
        // PRD-v5 F4：发布公告触发通知中心广播（发布者本人除外）
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new AnnouncementPublishedEvent(
                    saved.getId(), saved.getTitle(), publisherId));
        }
        return saved;
    }

    @Transactional
    public void update(Long id, AnnouncementRequest req) {
        apply(getEntity(id), req);
    }

    @Transactional
    public void delete(Long id) {
        Announcement a = getEntity(id);
        a.setDeleted(1);
        repository.save(a);
    }

    /** 标记已读（幂等：重复标记无副作用） */
    @Transactional
    public void markRead(Long announcementId, Long userId) {
        if (!repository.existsById(announcementId)) {
            throw new BizException(2404, "公告不存在");
        }
        if (!readRepository.existsByAnnouncementIdAndUserId(announcementId, userId)) {
            AnnouncementRead r = new AnnouncementRead();
            r.setAnnouncementId(announcementId);
            r.setUserId(userId);
            readRepository.save(r);
        }
    }

    /** 当前用户已读公告 id 集合（前端据此渲染已读态，替代 localStorage） */
    public List<Long> readIds(Long userId) {
        return readRepository.findReadIdsByUserId(userId);
    }

    private void apply(Announcement a, AnnouncementRequest req) {
        a.setTitle(req.getTitle());
        a.setContent(req.getContent());
        a.setPublishTime(req.getPublishTime() == null ? LocalDateTime.now() : req.getPublishTime());
    }
}
