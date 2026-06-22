package com.bvisionry.leadmagnet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LeadMagnetRequestRepository extends JpaRepository<LeadMagnetRequest, UUID> {
}
