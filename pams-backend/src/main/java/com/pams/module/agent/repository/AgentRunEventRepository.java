package com.pams.module.agent.repository;
import com.pams.module.agent.entity.AgentRunEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
public interface AgentRunEventRepository extends JpaRepository<AgentRunEvent,Long> {
    @Query("select e from AgentRunEvent e where e.deliveredAt is null and (e.leaseUntil is null or e.leaseUntil<:now) order by e.id")
    List<AgentRunEvent> pending(@Param("now") LocalDateTime now,Pageable page);
    List<AgentRunEvent> findByRunIdAndEventSeqGreaterThanOrderByEventSeqAsc(Long runId,long since,Pageable page);
    @Transactional @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("update AgentRunEvent e set e.deliveryToken=:token,e.leaseUntil=:until,e.attempts=e.attempts+1 where e.id=:id and e.deliveredAt is null and (e.leaseUntil is null or e.leaseUntil<:now)")
    int claim(@Param("id")Long id,@Param("token")String token,@Param("now")LocalDateTime now,@Param("until")LocalDateTime until);
    @Transactional @Modifying
    @Query("update AgentRunEvent e set e.deliveredAt=:now,e.deliveryToken=null,e.leaseUntil=null where e.id=:id and e.deliveryToken=:token")
    int delivered(@Param("id")Long id,@Param("token")String token,@Param("now")LocalDateTime now);
}
