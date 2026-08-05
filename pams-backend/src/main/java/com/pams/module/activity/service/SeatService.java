package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.SeatRequest;
import com.pams.module.activity.entity.SeatMap;
import com.pams.module.activity.repository.SeatMapRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SeatService {
    private final SeatMapRepository repository;
    public SeatService(SeatMapRepository repository) { this.repository = repository; }

    /** 按 zone 分组返回：{"zone": [座位...]}，保持 zone 出现顺序 */
    public Map<String, List<SeatMap>> listByActivity(Long activityId) {
        List<SeatMap> seats = repository.findByActivityIdOrderByZoneAscRowNoAscColNoAsc(activityId);
        return seats.stream().collect(Collectors.groupingBy(SeatMap::getZone,
                LinkedHashMap::new, Collectors.toList()));
    }

    @Transactional
    public SeatMap create(SeatRequest req) {
        SeatMap s = new SeatMap();
        s.setActivityId(req.getActivityId());
        s.setRoomName(req.getRoomName());
        s.setZone(req.getZone());
        s.setRowNo(req.getRowNo());
        s.setColNo(req.getColNo());
        s.setPersonName(req.getPersonName());
        s.setSeatType(req.getSeatType());
        s.setCreatedAt(LocalDateTime.now());
        return repository.save(s);
    }

    @Transactional
    public void update(Long id, SeatRequest req) {
        SeatMap s = getEntity(id);
        s.setRoomName(req.getRoomName());
        s.setZone(req.getZone());
        s.setRowNo(req.getRowNo());
        s.setColNo(req.getColNo());
        s.setPersonName(req.getPersonName());
        s.setSeatType(req.getSeatType());
        repository.save(s);
    }

    @Transactional
    public void delete(Long id) {
        getEntity(id);
        repository.deleteById(id);
    }

    public SeatMap getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2201, "座位不存在"));
    }
}
