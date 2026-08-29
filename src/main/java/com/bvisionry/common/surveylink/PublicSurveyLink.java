package com.bvisionry.common.surveylink;

import java.util.UUID;

/**
 * A survey's public link, reduced to what a "what next" CTA needs: the token
 * that addresses {@code /survey/{token}} and the name to put on the button.
 *
 * <p>A token rather than an id, on purpose. The only caller is a page an
 * anonymous visitor is looking at, and the only survey such a visitor can
 * reach is one with a public link — so an id they could not open has no
 * business crossing this seam.
 */
public record PublicSurveyLink(UUID token, String name) {}
