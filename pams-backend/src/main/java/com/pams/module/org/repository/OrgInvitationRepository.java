package com.pams.module.org.repository;

import com.pams.module.org.entity.OrgInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrgInvitationRepository extends JpaRepository<OrgInvitation, Long> {
    Optional<OrgInvitation> findByCode(String code);
}
