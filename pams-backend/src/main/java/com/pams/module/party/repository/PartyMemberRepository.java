package com.pams.module.party.repository;

import com.pams.module.party.entity.PartyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PartyMemberRepository extends JpaRepository<PartyMember, Long>,
        JpaSpecificationExecutor<PartyMember> {

    Optional<PartyMember> findByStudentNo(String studentNo);

    boolean existsByStudentNo(String studentNo);

    /** 编辑查重：同学号但排除自身 id（改学号时不能撞别人的学号）。 */
    boolean existsByStudentNoAndIdNot(String studentNo, Long id);

    /**
     * 按政治面貌（发展阶段）分组计数 —— 供知识库的事实快照使用。
     *
     * <p><b>刻意只返回 {@code (阶段名, 人数)}</b>，不返回个人材料：
     * 知识库快照只允许收录聚合事实，不得含个人可识别信息（见 {@code MemberSource} 与
     * {@code SnapshotSpecRegistry} 的口径说明）。用一条"只 select 计数"的查询，
     * 比"查明细再在 Java 里计数"更安全 —— 前者结构上就传不出人名。
     *
     * <p>{@code @SQLRestriction("deleted = 0")} 对 JPQL 实体查询自动生效，无需显式条件。
     */
    @Query("select m.politicalStatus, count(m) from PartyMember m group by m.politicalStatus")
    List<Object[]> countGroupByPoliticalStatus();
}
