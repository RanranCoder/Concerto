package com.pams.module.agent.repository;
import com.pams.module.agent.entity.AgentArtifact;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface AgentArtifactRepository extends JpaRepository<AgentArtifact,Long> {
    List<AgentArtifact> findByRunIdOrderByIdAsc(Long id);
    List<AgentArtifact> findByConversationIdAndUserIdOrderByIdAsc(Long id,Long uid);
    Optional<AgentArtifact> findByWorkItemId(Long id);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AgentArtifact a where a.id=:id") Optional<AgentArtifact> lock(@Param("id")Long id);
}
