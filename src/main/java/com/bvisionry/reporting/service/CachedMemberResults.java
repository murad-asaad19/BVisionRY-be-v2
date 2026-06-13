package com.bvisionry.reporting.service;

import com.bvisionry.reporting.dto.MemberResultsResponse;

import java.util.UUID;

/**
 * Cache envelope around MemberResultsResponse. The wrapped response holds only
 * tier-agnostic data; premiumFeaturesAvailable is always false inside the cache
 * and is recomputed per-viewer outside the cache boundary. organizationId is
 * carried here so that recomputation does not need a second submission lookup.
 */
record CachedMemberResults(MemberResultsResponse response, UUID organizationId) {}
