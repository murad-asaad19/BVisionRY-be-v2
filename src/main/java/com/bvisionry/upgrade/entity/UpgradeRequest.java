package com.bvisionry.upgrade.entity;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.organization.entity.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "upgrade_requests")
@Getter
@Setter
@NoArgsConstructor
public class UpgradeRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "feature_context", length = 32)
    private UpgradeFeatureContext featureContext;

    @Column(length = 500)
    private String note;
}
