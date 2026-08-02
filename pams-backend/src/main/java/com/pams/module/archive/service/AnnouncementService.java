package com.pams.module.archive.service;

import com.pams.common.BizException;
import com.pams.module.archive.dto.AnnouncementRequest;
import com.pams.module.archive.entity.Announcement;
import com.pams.module.archive.repository.AnnouncementRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AnnouncementService {
    private final AnnouncementRepository repository;
    public AnnouncementService(AnnouncementRepository repository) { this.repository = repository; }

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
        return repository.save(a);
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

    private void apply(Announcement a, AnnouncementRequest req) {
        a.setTitle(req.getTitle());
        a.setContent(req.getContent());
        a.setPublishTime(req.getPublishTime() == null ? LocalDateTime.now() : req.getPublishTime());
    }
}
