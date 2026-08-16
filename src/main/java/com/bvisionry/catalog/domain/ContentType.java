package com.bvisionry.catalog.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Type of a {@link Content} item within a {@link Section}.
 *
 * <p>Stored as {@code varchar} via {@code @Enumerated(STRING)}; mirrored by the
 * {@code ck_content_type} CHECK constraint in {@code V76__catalog_schema.sql}.
 * The set is the union of the original authoring types and the API contract's
 * {@code lesson.type} vocabulary so the value serialises 1:1 to the frontend.
 *
 * <h2>Retired types</h2>
 * {@link #SCORM}, {@link #WEBPAGE}, {@link #DOCUMENT} and {@link #IMAGE} have no
 * runtime in the course player — {@code learn/_components/content-viewer.tsx}
 * dispatches on VIDEO / PDF / PAGE / ARTICLE / QUIZ / ASSIGNMENT / CERTIFICATION
 * / LINK and drops everything else onto a "Content unavailable" placeholder. So
 * authoring no longer offers them and {@code AuthoringService} rejects them on
 * create and update: an author must not be able to produce a lesson the player
 * cannot play.
 *
 * <p><b>The constants themselves must NOT be deleted.</b> Rows carrying them can
 * exist: {@code V77__catalog_seed.sql} itself inserts two SCORM lessons (lines
 * 436 and 481), and every deployed database has accepted all twelve values since
 * V76 because {@code ck_content_type} admits them. {@code @Enumerated(STRING)}
 * hydration of such a row against a missing constant throws, i.e. a 500 on read.
 * Narrowing {@code ck_content_type} would be a contraction migration and is
 * forbidden by the expand-contract rule, so the values stay legal at the DB
 * level and legacy rows keep loading and rendering their fallback.
 */
public enum ContentType {
    VIDEO,
    ARTICLE,
    QUIZ,
    /** @deprecated Retired — no player runtime; kept so pre-existing rows still hydrate. */
    @Deprecated DOCUMENT,
    /** @deprecated Retired — {@code product.never_add}; kept so seeded/legacy rows still hydrate. */
    @Deprecated SCORM,
    ASSIGNMENT,
    /** @deprecated Retired — no player runtime; kept so pre-existing rows still hydrate. */
    @Deprecated WEBPAGE,
    // API-contract lesson types (frontend `lesson.type`):
    PDF,
    CERTIFICATION,
    PAGE,
    LINK,
    /** @deprecated Retired — no player runtime; kept so pre-existing rows still hydrate. */
    @Deprecated IMAGE;

    /** Types the player cannot render. Readable, never writable — see the class doc. */
    private static final Set<ContentType> RETIRED = EnumSet.of(SCORM, WEBPAGE, DOCUMENT, IMAGE);

    /** True when the player has a runtime for this type, i.e. an author may pick it. */
    public boolean isAuthorable() {
        return !RETIRED.contains(this);
    }

    /** The types authoring may offer — the player renders every one of them. */
    public static List<ContentType> authorable() {
        return Arrays.stream(values()).filter(ContentType::isAuthorable).toList();
    }
}
