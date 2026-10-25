package com.pams.module.agent.repository;
import com.pams.module.agent.entity.AgentRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface AgentRequestRepository extends JpaRepository<AgentRequest,String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from AgentRequest r where r.streamId=:stream")
    Optional<AgentRequest> lockByStreamId(@org.springframework.data.repository.query.Param("stream") String stream);
    Optional<AgentRequest> findByStreamId(String streamId);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRequest r where r.id=:id")
    Optional<AgentRequest> lockById(@Param("id") String id);
}
