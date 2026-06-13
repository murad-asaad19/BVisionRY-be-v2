package com.bvisionry.membertype;

import com.bvisionry.membertype.entity.MemberType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberTypeRepository extends JpaRepository<MemberType, UUID> {
    List<MemberType> findAllByOrderByDisplayOrderAscLabelAsc();
    Optional<MemberType> findByCode(String code);
    boolean existsByCode(String code);

    @Query("select max(m.displayOrder) from MemberType m")
    Optional<Integer> findMaxDisplayOrder();
}
