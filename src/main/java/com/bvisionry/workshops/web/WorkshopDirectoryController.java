package com.bvisionry.workshops.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.workshops.repository.WorkshopRepository;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Every workshop across orgs — the platform cohort board's WORKSHOP ref
 * picker (spec §13: cohorts are platform artifacts, so a task may reference
 * any org's workshop; the org label is the disambiguation hint).
 */
@RestController
@RequestMapping(path = "/api/admin/workshops", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Workshops (platform directory)", description = "All workshops, for platform pickers.")
@RequiredArgsConstructor
public class WorkshopDirectoryController {

    private final WorkshopRepository workshops;

    public record WorkshopRefDto(UUID id, String name, String orgName) {
    }

    @GetMapping
    public List<WorkshopRefDto> list() {
        return workshops.findAllRefs().stream()
                .map(r -> new WorkshopRefDto(r.getId(), r.getName(), r.getOrgName()))
                .toList();
    }
}
