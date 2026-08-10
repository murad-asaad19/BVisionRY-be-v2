package com.bvisionry.courseaccess.repository;

import com.bvisionry.courseaccess.domain.OrgCourseRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgCourseRuleRepository extends JpaRepository<OrgCourseRule, UUID> {

    List<OrgCourseRule> findByOrgId(UUID orgId);

    Optional<OrgCourseRule> findByOrgIdAndCourseId(UUID orgId, UUID courseId);
}
