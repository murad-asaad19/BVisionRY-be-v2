package com.bvisionry.common.surveylink;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared-kernel view of "can an anonymous visitor be sent to this survey, and
 * under what name" — consumed by features that PAIR a survey to their own
 * completion screen but must not depend on the {@code survey} package.
 *
 * <p>Exactly the seam {@link com.bvisionry.common.media.MediaUrlPort} is:
 * features may depend on {@code common}, never the reverse, and
 * {@code ArchitectureRulesTest} rule 1 freezes every existing feature→feature
 * edge while CI forbids the store from growing — so a new
 * {@code exercise → survey} edge cannot be built, and this is the way across.
 *
 * <p>Implemented by {@code PublicSurveyLinkService} in the {@code survey}
 * package. The signatures carry no survey type, so nothing about a Survey's
 * status, visibility or schema leaks into the kernel.
 */
public interface PublicSurveyLinkPort {

    /**
     * Whether a survey with this id exists at all — regardless of whether it is
     * published, public or reachable.
     *
     * <p>Separate from {@link #publicLink} because pairing and offering are
     * different questions: pairing a DRAFT survey is legitimate (it may be
     * published later), pairing one that was deleted is not.
     */
    boolean exists(UUID surveyId);

    /**
     * The survey's public link, present only when an anonymous visitor could
     * actually open it: published, public, and holding a minted token.
     * {@link Optional#empty()} for every other case — including a survey that
     * no longer exists — so a caller can never render a dead CTA.
     */
    Optional<PublicSurveyLink> publicLink(UUID surveyId);
}
