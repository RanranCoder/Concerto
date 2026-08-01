package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.SigninRequest;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.repository.SigninRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SigninService {
    private final SigninRepository repository;
    public SigninService(SigninRepository repository) { this.repository = repository; }

    public List<Signin> listByActivity(Long activityId, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return repository.findByActivityIdAndNameContaining(activityId, keyword.trim());
        }
        return repository.findByActivityId(activityId);
    }

    public long count(Long activityId) {
        return repository.countByActivityId(activityId);
    }

    @Transactional
    public Signin create(SigninRequest req) {
        Signin s = new Signin();
        s.setActivityId(req.getActivityId());
        s.setPersonId(req.getPersonId());
        s.setName(req.getName());
        s.setStudentNo(req.getStudentNo());
        s.setClassName(req.getClassName());
        s.setIdentityType(req.getIdentityType());
        s.setSignType(req.getSignType() == null ? Signin.SignType.MANUAL : req.getSignType());
        s.setSignTime(req.getSignTime());
        s.setLocation(req.getLocation());
        s.setPhone(req.getPhone());
        s.setRemark(req.getRemark());
        s.setCreatedAt(LocalDateTime.now());
        return repository.save(s);
    }

    @Transactional
    public void delete(Long id) {
        getEntity(id);
        repository.deleteById(id);
    }

    public Signin getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2301, "签到记录不存在"));
    }
}
