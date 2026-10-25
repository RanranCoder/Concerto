package com.pams.module.agent.repository;
import com.pams.module.agent.entity.AgentArtifactVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface AgentArtifactVersionRepository extends JpaRepository<AgentArtifactVersion,Long> {
    Optional<AgentArtifactVersion> findByArtifactIdAndVersion(Long id,int version);
    List<AgentArtifactVersion> findByArtifactIdOrderByVersionDesc(Long id,Pageable page);
    Optional<AgentArtifactVersion> findByTaskId(Long id);
}
