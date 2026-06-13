package com.bvisionry.membertype.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A member type (e.g. "Leader", "Founder", or any admin-defined category) that
 * a user can be tagged with. Stored as a row instead of a Java enum so super
 * admins can add types without a code change. {@code isSystem} rows (LEADER,
 * FOUNDER) were the original enum values and are protected from deletion so
 * existing UI and assignment logic that references them keeps working.
 */
@Entity
@Table(name = "member_types")
@Getter
@Setter
@NoArgsConstructor
public class MemberType extends BaseEntity {

    /** Stable identifier stored on users.user_type. Uppercased by the service. */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /** Human-readable label shown in dropdowns and badges. */
    @Column(nullable = false, length = 128)
    private String label;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;
}
