package com.bvisionry.catalog.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.ContentType;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.CourseAccess;
import com.bvisionry.catalog.domain.CourseAudience;
import com.bvisionry.catalog.domain.CourseLevel;
import com.bvisionry.catalog.domain.CourseMode;
import com.bvisionry.catalog.domain.CourseState;
import com.bvisionry.catalog.domain.CourseVisibility;
import com.bvisionry.catalog.domain.EnrollPolicy;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.catalog.domain.Tag;
import com.bvisionry.catalog.dto.AdminCourseDetailDto;
import com.bvisionry.catalog.dto.CourseAdminDto;
import com.bvisionry.catalog.dto.authoring.ReorderRequest;
import com.bvisionry.catalog.dto.authoring.UpsertContentRequest;
import com.bvisionry.catalog.dto.authoring.UpsertCourseRequest;
import com.bvisionry.catalog.dto.authoring.UpsertSectionRequest;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.catalog.repository.CourseRepository;
import com.bvisionry.catalog.repository.SectionRepository;
import com.bvisionry.catalog.repository.TagRepository;

/**
 * Write-side service for course authoring (SUPER_ADMIN / INSTRUCTOR only).
 */
@Service
public class AuthoringService {

    private final CourseRepository courses;
    private final SectionRepository sections;
    private final ContentRepository contents;
    private final TagRepository tags;
    private final CourseMapper mapper;

    public AuthoringService(CourseRepository courses,
                            SectionRepository sections,
                            ContentRepository contents,
                            TagRepository tags,
                            CourseMapper mapper) {
        this.courses = courses;
        this.sections = sections;
        this.contents = contents;
        this.tags = tags;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    @Transactional
    public Section createSection(String slug, UpsertSectionRequest req) {
        var course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));
        SecurityUtils.requireOrgAccess(course.getOrgId());
        Section s = new Section();
        s.setCourse(course);
        s.setOrgId(course.getOrgId());
        s.setTitle(req.title());
        s.setSequence(req.sequence());
        return sections.save(s);
    }

    @Transactional
    public Section updateSection(String sectionId, UpsertSectionRequest req) {
        Section s = sections.findById(UUID.fromString(sectionId))
                .orElseThrow(() -> new IllegalArgumentException("Section not found: " + sectionId));
        SecurityUtils.requireOrgAccess(s.getOrgId());
        s.setTitle(req.title());
        s.setSequence(req.sequence());
        return sections.save(s);
    }

    @Transactional
    public void deleteSection(String sectionId) {
        // Load first so we can enforce org-ownership before deleting (the
        // controller is role-gated but not org-scoped — see class header).
        Section s = sections.findById(UUID.fromString(sectionId))
                .orElseThrow(() -> new IllegalArgumentException("Section not found: " + sectionId));
        SecurityUtils.requireOrgAccess(s.getOrgId());
        sections.delete(s);
    }

    @Transactional
    public void reorderSections(String slug, ReorderRequest req) {
        Course course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));
        SecurityUtils.requireOrgAccess(course.getOrgId());
        List<String> ids = req.ids();
        for (int i = 0; i < ids.size(); i++) {
            int seq = i;
            String rawId = ids.get(i);
            sections.findById(UUID.fromString(rawId))
                    // Only reorder sections that actually belong to this course;
                    // ignore foreign/cross-org ids smuggled into the id list.
                    .filter(s -> s.getCourse() != null && course.getId().equals(s.getCourse().getId()))
                    .ifPresent(s -> {
                        s.setSequence(seq);
                        sections.save(s);
                    });
        }
    }

    // -------------------------------------------------------------------------
    // Content / Lessons
    // -------------------------------------------------------------------------

    @Transactional
    public Content createContent(String sectionId, UpsertContentRequest req) {
        Section section = sections.findById(UUID.fromString(sectionId))
                .orElseThrow(() -> new IllegalArgumentException("Section not found: " + sectionId));
        SecurityUtils.requireOrgAccess(section.getOrgId());
        Content c = new Content();
        c.setSection(section);
        c.setOrgId(section.getOrgId());
        applyContentRequest(c, req);
        return contents.save(c);
    }

    @Transactional
    public Content updateContent(String contentId, UpsertContentRequest req) {
        Content c = contents.findById(UUID.fromString(contentId))
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + contentId));
        SecurityUtils.requireOrgAccess(c.getOrgId());
        applyContentRequest(c, req);
        return contents.save(c);
    }

    @Transactional
    public void deleteContent(String contentId) {
        // Load first so we can enforce org-ownership before deleting (the
        // controller is role-gated but not org-scoped — see class header).
        Content c = contents.findById(UUID.fromString(contentId))
                .orElseThrow(() -> new IllegalArgumentException("Content not found: " + contentId));
        SecurityUtils.requireOrgAccess(c.getOrgId());
        contents.delete(c);
    }

    @Transactional
    public void reorderContents(String sectionId, ReorderRequest req) {
        Section section = sections.findById(UUID.fromString(sectionId))
                .orElseThrow(() -> new IllegalArgumentException("Section not found: " + sectionId));
        SecurityUtils.requireOrgAccess(section.getOrgId());
        List<String> ids = req.ids();
        for (int i = 0; i < ids.size(); i++) {
            int seq = i;
            contents.findById(UUID.fromString(ids.get(i)))
                    // Only reorder contents that actually belong to this section;
                    // ignore foreign/cross-org ids smuggled into the id list.
                    .filter(c -> c.getSection() != null && section.getId().equals(c.getSection().getId()))
                    .ifPresent(c -> {
                        c.setSequence(seq);
                        contents.save(c);
                    });
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void applyContentRequest(Content c, UpsertContentRequest req) {
        c.setTitle(req.title());
        c.setContentType(ContentType.valueOf(req.contentType().toUpperCase()));
        c.setSequence(req.sequence());
        c.setDurationMin(req.durationMin());
        c.setAllowPreview(req.allowPreview());
        c.setBody(req.body());
        c.setVideoUrl(req.videoUrl());
        c.setAssetUrl(req.assetUrl());
        c.setPipelineId(req.pipelineId());
    }

    // -------------------------------------------------------------------------
    // Courses (course-level metadata authoring + lifecycle)
    // -------------------------------------------------------------------------

    /**
     * Bvisionry Academy org — owning org for courses authored by a SUPER_ADMIN
     * (whose {@code organization} is {@code null}). Matches the seed org id.
     */
    private static final UUID ACADEMY_ORG_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ca");

    /**
     * Full editable detail for the authoring editor — works for ANY state
     * (the public catalog endpoint only returns PUBLISHED, so DRAFT courses
     * 404 there). Hydrates sections+contents, outcomes and tags.
     */
    @Transactional(readOnly = true)
    public AdminCourseDetailDto getForEditing(String slug) {
        Course course = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));
        // Editing read: scope to the owning org (controller is role-gated only).
        SecurityUtils.requireOrgAccess(course.getOrgId());
        List<Section> hydratedSections = sections.findByCourseIdWithContents(course.getId());
        // Attach outcomes + tags onto the same managed instance (same tx).
        courses.fetchOutcomes(course.getId());
        courses.fetchTags(course.getId());
        return mapper.toAdminDetail(course, hydratedSections);
    }

    /** Lists authorable courses incl. DRAFT/ARCHIVED: SUPER_ADMIN → all orgs, else the caller's org. */
    @Transactional(readOnly = true)
    public List<CourseAdminDto> listForAuthoring() {
        List<Course> found = SecurityUtils.isSuperAdmin()
                ? courses.findAllByOrderByUpdatedAtDesc()
                : courses.findByOrgIdOrderByUpdatedAtDesc(orgIdOf(SecurityUtils.getCurrentUser()));
        return found.stream().map(this::toAdminDto).toList();
    }

    @Transactional
    public CourseAdminDto createCourse(UpsertCourseRequest req) {
        String slug = req.slug() == null ? null : req.slug().trim();
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug is required");
        }
        if (courses.existsBySlug(slug)) {
            throw new IllegalArgumentException("A course with slug '" + slug + "' already exists");
        }
        Course c = new Course();
        c.setOrgId(orgIdOf(SecurityUtils.getCurrentUser()));
        c.setState(CourseState.DRAFT); // new courses start unpublished
        applyCourseRequest(c, req, true);
        Course saved = courses.save(c);
        applyTags(saved, req.tags());
        return toAdminDto(courses.save(saved));
    }

    @Transactional
    public CourseAdminDto updateCourse(String slug, UpsertCourseRequest req) {
        Course c = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));
        SecurityUtils.requireOrgAccess(c.getOrgId());
        String newSlug = req.slug() == null ? null : req.slug().trim();
        if (newSlug != null && !newSlug.equals(c.getSlug()) && courses.existsBySlug(newSlug)) {
            throw new IllegalArgumentException("A course with slug '" + newSlug + "' already exists");
        }
        applyCourseRequest(c, req, false);
        applyTags(c, req.tags());
        return toAdminDto(courses.save(c));
    }

    @Transactional
    public void deleteCourse(String slug) {
        Course c = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));
        SecurityUtils.requireOrgAccess(c.getOrgId());
        courses.delete(c);
    }

    @Transactional
    public CourseAdminDto setState(String slug, String stateRaw) {
        Course c = courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));
        SecurityUtils.requireOrgAccess(c.getOrgId());
        CourseState state;
        try {
            state = CourseState.valueOf(stateRaw == null ? "" : stateRaw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid course state: " + stateRaw);
        }
        // Don't let an empty shell go live — a PUBLISHED course must have lessons.
        if (state == CourseState.PUBLISHED && contents.countByCourseId(c.getId()) == 0) {
            throw new IllegalArgumentException("Cannot publish a course with no lessons");
        }
        c.setState(state);
        return toAdminDto(courses.save(c));
    }

    // -------------------------------------------------------------------------
    // Course helpers
    // -------------------------------------------------------------------------

    private CourseAdminDto toAdminDto(Course c) {
        int sectionCount = (int) sections.countByCourseId(c.getId());
        int lessonCount = (int) contents.countByCourseId(c.getId());
        return mapper.toAdminDto(c, sectionCount, lessonCount);
    }

    private static UUID orgIdOf(User user) {
        return user.getOrganization() != null ? user.getOrganization().getId() : ACADEMY_ORG_ID;
    }

    /**
     * Applies the request onto the course. On CREATE, enum/certification/cover-image
     * fields fall back to column defaults. On UPDATE, those configuration fields are
     * PRESERVED when the request omits them (blank/null) — so editing the title can
     * never silently reset access tier, enrollment policy, visibility, or
     * certification. Text fields and price/duration are always replaced (the forms
     * round-trip them, so clearing is intentional).
     */
    private void applyCourseRequest(Course c, UpsertCourseRequest req, boolean isCreate) {
        c.setSlug(req.slug().trim());
        c.setTitle(req.title());
        c.setSubtitle(blankToNull(req.subtitle()));
        c.setCategory(blankToNull(req.category()));
        c.setDescription(req.description());
        c.setPrice(req.price());
        c.setCurrency(blankOr(req.currency(), isCreate ? "USD" : c.getCurrency()).toUpperCase());
        c.setDurationHours(req.durationHours());
        c.setInstructorName(blankToNull(req.instructorName()));
        c.setInstructorTitle(blankToNull(req.instructorTitle()));
        c.setInstructorBio(blankToNull(req.instructorBio()));
        c.setCoverGradient(blankToNull(req.coverGradient()));

        if (isCreate) {
            c.setLevel(parseEnumOr(CourseLevel.class, req.level(), CourseLevel.BEGINNER));
            c.setMode(parseEnumOr(CourseMode.class, req.mode(), CourseMode.SELF_PACED));
            c.setAudience(parseEnumOr(CourseAudience.class, req.audience(), CourseAudience.PUBLIC));
            c.setAccess(parseEnumOr(CourseAccess.class, req.access(), CourseAccess.EVERYONE));
            c.setEnrollPolicy(parseEnumOr(EnrollPolicy.class, req.enrollPolicy(), EnrollPolicy.OPEN));
            c.setVisibility(parseEnumOr(CourseVisibility.class, req.visibility(), CourseVisibility.PUBLIC));
            c.setCertificationTitle(blankToNull(req.certificationTitle()));
            c.setCertificationPassingPct(req.certificationPassingPct());
            c.setCoverImageUrl(blankToNull(req.coverImageUrl()));
        } else {
            c.setLevel(enumForUpdate(CourseLevel.class, req.level(), c.getLevel()));
            c.setMode(enumForUpdate(CourseMode.class, req.mode(), c.getMode()));
            c.setAudience(enumForUpdate(CourseAudience.class, req.audience(), c.getAudience()));
            c.setAccess(enumForUpdate(CourseAccess.class, req.access(), c.getAccess()));
            c.setEnrollPolicy(enumForUpdate(EnrollPolicy.class, req.enrollPolicy(), c.getEnrollPolicy()));
            c.setVisibility(enumForUpdate(CourseVisibility.class, req.visibility(), c.getVisibility()));
            if (req.certificationTitle() != null) {
                c.setCertificationTitle(blankToNull(req.certificationTitle()));
            }
            if (req.certificationPassingPct() != null) {
                c.setCertificationPassingPct(req.certificationPassingPct());
            }
            if (req.coverImageUrl() != null) {
                c.setCoverImageUrl(blankToNull(req.coverImageUrl()));
            }
        }

        if (req.outcomes() != null) {
            c.getOutcomes().clear();
            req.outcomes().stream()
                    .filter(o -> o != null && !o.isBlank())
                    .map(String::trim)
                    .forEach(o -> c.getOutcomes().add(o));
        }
    }

    /** Find-or-create tags by (org, name) and replace the course's tag set. Null = leave unchanged. */
    private void applyTags(Course c, List<String> tagNames) {
        if (tagNames == null) {
            return;
        }
        Set<Tag> resolved = new LinkedHashSet<>();
        for (String raw : tagNames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            Tag tag = tags.findByOrgIdAndNameIgnoreCase(c.getOrgId(), name)
                    .orElseGet(() -> {
                        Tag t = new Tag();
                        t.setOrgId(c.getOrgId());
                        t.setName(name);
                        return tags.save(t);
                    });
            resolved.add(tag);
        }
        c.getTags().clear();
        c.getTags().addAll(resolved);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String blankOr(String s, String dflt) {
        return (s == null || s.isBlank()) ? dflt : s.trim();
    }

    private static <E extends Enum<E>> E parseEnumOr(Class<E> type, String raw, E dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return dflt;
        }
    }

    /** Update-mode enum: keep the current value when the request omits it (blank) or sends an unknown value. */
    private static <E extends Enum<E>> E enumForUpdate(Class<E> type, String raw, E current) {
        if (raw == null || raw.isBlank()) {
            return current;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return current;
        }
    }
}
