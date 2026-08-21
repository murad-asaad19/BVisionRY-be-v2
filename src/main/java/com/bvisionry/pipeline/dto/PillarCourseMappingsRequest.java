package com.bvisionry.pipeline.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The COMPLETE set of rules for one pillar — the endpoint replaces, it does not
 * append. One save from an editor that owns the whole list is what the UI
 * actually does, and it keeps a whole pillar's edit to a single round trip.
 *
 * <p>It does NOT make concurrent edits safe. Under READ COMMITTED two admins
 * saving at once can interleave their delete-then-insert and leave the UNION of
 * both rule sets. This is reversible config, so that is recorded rather than
 * locked; take a row lock on the pillar if it ever matters.
 *
 * <p>An empty list is a legitimate body: it clears the pillar's rules.
 */
public record PillarCourseMappingsRequest(
        @NotNull
        @Size(max = 100, message = "A pillar can carry at most 100 course recommendations")
        @Valid List<PillarCourseMappingItem> mappings
) {}
