package com.bvisionry.catalog.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A catalog course. Central read entity of the catalog slice.
 *
 * <p>Decoupled from the identity domain: {@code org_id} and {@code instructor_id}
 * are plain {@code UUID} columns with no {@code @ManyToOne} to identity entities.
 * Instructor display data is stored in the denormalized {@code instructor_name},
 * {@code instructor_title} and {@code instructor_bio} columns so the detail view
 * can be assembled without joining to the {@code users} table.
 *
 * <p>Collections ({@link #sections}, {@link #reviews}, {@link #tags}) are
 * {@code LAZY}; the repository fetch-joins them explicitly when loading a course
 * detail by slug, so {@code open-in-view=false} does not cause
 * {@code LazyInitializationException}s.
 */
@Entity
@Table(name = "course")
@Getter
@Setter
public class Course {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    /** FK to {@code organizations.id} — stored as a plain UUID (no @ManyToOne). */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "slug", nullable = false, length = 160, unique = true)
    private String slug;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "subtitle", length = 300)
    private String subtitle;

    @Column(name = "category", length = 80)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 20)
    private CourseLevel level = CourseLevel.BEGINNER;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private CourseMode mode = CourseMode.SELF_PACED;

    /**
     * Audience / distribution channel surfaced to the frontend as {@code course.mode}
     * (EMPLOYEE / PUBLIC / B2B). Backed by its own column so the catalog can filter
     * on it directly without overloading {@link #mode}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    private CourseAudience audience = CourseAudience.PUBLIC;

    /**
     * Access tier surfaced to the frontend as {@code course.visibility}
     * (EVERYONE / SIGNED_IN / ENROLLED / LINK).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "access", nullable = false, length = 20)
    private CourseAccess access = CourseAccess.EVERYONE;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Nullable: free / internal courses have no price. */
    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "rating", nullable = false, precision = 2, scale = 1)
    private BigDecimal rating = BigDecimal.ZERO;

    @Column(name = "reviews_count", nullable = false)
    private int reviewsCount = 0;

    @Column(name = "learners_count", nullable = false)
    private int learnersCount = 0;

    @Column(name = "duration_hours", precision = 6, scale = 2)
    private BigDecimal durationHours;

    /** Total number of lessons (contents) across all sections. */
    @Column(name = "lessons_count", nullable = false)
    private int lessonsCount = 0;

    /** Title of the awarded certification, or {@code null} if the course grants none. */
    @Column(name = "certification_title", length = 200)
    private String certificationTitle;

    /** Passing percentage (0–100) for the certification, when present. */
    @Column(name = "certification_passing_pct")
    private Integer certificationPassingPct;

    /**
     * FK to {@code users.id} — stored as a plain UUID (no @ManyToOne to identity).
     * Nullable so courses can exist without an assigned instructor.
     */
    @Column(name = "instructor_id")
    private UUID instructorId;

    /**
     * Denormalized instructor display name, copied from the users table at seed/authoring
     * time. Avoids joining identity tables for every catalog list query.
     */
    @Column(name = "instructor_name", length = 160)
    private String instructorName;

    /** Denormalized instructor professional title (e.g. "VP of Engineering"). */
    @Column(name = "instructor_title", length = 200)
    private String instructorTitle;

    /** Denormalized instructor bio text. */
    @Column(name = "instructor_bio", columnDefinition = "text")
    private String instructorBio;

    @Enumerated(EnumType.STRING)
    @Column(name = "enroll_policy", nullable = false, length = 20)
    private EnrollPolicy enrollPolicy = EnrollPolicy.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private CourseVisibility visibility = CourseVisibility.PUBLIC;

    @Column(name = "cover_gradient", length = 120)
    private String coverGradient;

    /** Optional cover image URL (added in V79; may be a {@code minio://} marker resolved at read time). */
    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private CourseState state = CourseState.PUBLISHED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sequence ASC")
    private List<Section> sections = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt DESC")
    private List<Review> reviews = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "course_tag",
            joinColumns = @JoinColumn(name = "course_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    /**
     * Ordered list of learning outcomes ("what you'll learn"), stored in the
     * {@code course_outcome} side table. Fetched explicitly in the detail query.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "course_outcome",
            joinColumns = @JoinColumn(name = "course_id"))
    @OrderColumn(name = "sequence")
    @Column(name = "outcome", nullable = false, length = 300)
    private List<String> outcomes = new ArrayList<>();

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
