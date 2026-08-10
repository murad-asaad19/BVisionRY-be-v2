package com.bvisionry.courseaccess.dto;

import java.util.List;

/**
 * The member Library (spec §2.1): what they have, and what they may browse.
 *
 * <p>{@code courses} carries suggestions too — a row with status
 * {@code SUGGESTED} is the one-tap Accept card. The catalog is the visible
 * set only, so a member is never shown a course their org cannot open.
 */
public record MyLibraryResponse(List<MemberCourseView> courses, List<CatalogCourseView> catalog) {
}
