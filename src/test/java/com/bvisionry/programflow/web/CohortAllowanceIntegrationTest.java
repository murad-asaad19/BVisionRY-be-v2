package com.bvisionry.programflow.web;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cohort-rate ceiling, end to end against REAL Postgres with Flyway applied
 * (so V156's data migration and CHECK constraint are exercised, not assumed).
 *
 * <p>Deliberately an integration test and not a Mockito one: the two things most
 * likely to be wrong here are SQL, not Java — the rolling-window boundary and
 * the billing-FAMILY scoping that stops a customer resetting their ceiling by
 * creating a sub-organization. A mocked repository would assert my own beliefs
 * about that query back to me.
 *
 * <p>The 45-day-old seed cohort is the discriminating fixture: it is inside a
 * quarter but outside a month, so Starter must refuse and Growth must allow.
 * A "created just now" fixture would be refused by BOTH and prove nothing about
 * the window actually being read from the tier.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CohortAllowanceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CohortService cohortService;
    @Autowired private CohortRepository cohorts;
    @Autowired private OrganizationRepository orgs;

    private Organization root;
    private Organization subA;
    private Organization subB;

    @BeforeEach
    void seed() {
        root = saveOrg("Root Accelerator", SubscriptionTier.STARTER, null);
        subA = saveOrg("Cohort Surface A", SubscriptionTier.FREE, root);
        subB = saveOrg("Cohort Surface B", SubscriptionTier.FREE, root);
        authenticate(UserRole.ORG_ADMIN);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /* ------------------------------------------------------ the rate itself */

    @Test
    void starter_secondCohortInTheQuarter_isRefusedWithAnActionableMessage() {
        seedCohort(subA, 45);

        assertThatThrownBy(() -> cohortService.create(subA.getId(), req("Spring 26")))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Starter")
                .hasMessageContaining("1 cohort per quarter")
                .hasMessageContaining("Upgrade");
    }

    /** Same org, same 45-day-old cohort, one tier up: Growth meters a MONTH, so it fits. */
    @Test
    void growth_sameHistory_isAllowed() {
        seedCohort(subA, 45);
        setTier(root, SubscriptionTier.GROWTH);

        assertThatCode(() -> cohortService.create(subA.getId(), req("Spring 26")))
                .doesNotThrowAnyException();
    }

    /** The reclassification a SUPER_ADMIN performs via PATCH /organizations/{id}/tier. */
    @Test
    void superAdminReclassification_flipsTheOutcomeBothWays() {
        seedCohort(subA, 10); // inside BOTH windows

        setTier(root, SubscriptionTier.GROWTH);
        assertThatThrownBy(() -> cohortService.create(subA.getId(), req("Too soon")))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("Growth")
                .hasMessageContaining("1 cohort per month");

        setTier(root, SubscriptionTier.FOUNDER_SUCCESS);
        assertThatCode(() -> cohortService.create(subA.getId(), req("Unlimited")))
                .doesNotThrowAnyException();
    }

    @Test
    void growth_cohortOlderThanTheWindow_doesNotCount() {
        seedCohort(subA, 45);
        setTier(root, SubscriptionTier.GROWTH);

        // 45 days ago is outside the month window; creating now must succeed and
        // then immediately close the window behind itself.
        cohortService.create(subA.getId(), req("First"));
        assertThatThrownBy(() -> cohortService.create(subA.getId(), req("Second")))
                .isInstanceOf(IllegalOperationException.class);
    }

    /* ----------------------------------------------- the billing-family rule */

    /**
     * The bypass this design exists to close: spin up a second sub-org and the
     * ceiling would reset, because cohorts live on sub-orgs while the plan lives
     * on the root. Sibling sub-orgs share one meter.
     */
    @Test
    void aSiblingSubOrgSharesTheParentsMeter() {
        seedCohort(subA, 10);

        assertThatThrownBy(() -> cohortService.create(subB.getId(), req("Different sub-org")))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("sub-organizations share the parent organization's plan");
    }

    /** ...and the meter stops at the family boundary: another customer is invisible. */
    @Test
    void anUnrelatedOrgsCohortsDoNotCount() {
        Organization otherRoot = saveOrg("Other Customer", SubscriptionTier.STARTER, null);
        Organization otherSub = saveOrg("Other Surface", SubscriptionTier.FREE, otherRoot);
        seedCohort(otherSub, 1);

        assertThatCode(() -> cohortService.create(subA.getId(), req("Ours")))
                .doesNotThrowAnyException();
    }

    /** A cohort on the ROOT org itself is in the family too (COALESCE(parent, id)). */
    @Test
    void aCohortOnTheRootOrgCountsAgainstItsSubOrgs() {
        seedCohort(root, 10);

        assertThatThrownBy(() -> cohortService.create(subA.getId(), req("Sub-org attempt")))
                .isInstanceOf(IllegalOperationException.class);
    }

    /* ------------------------------------------------------ bypass + non-regression */

    @Test
    void superAdmin_bypassesTheCeiling() {
        seedCohort(subA, 1);
        authenticate(UserRole.SUPER_ADMIN);

        assertThatCode(() -> cohortService.create(subA.getId(), req("Operator seeded")))
                .doesNotThrowAnyException();
    }

    /**
     * Ruling: enforcement is SOFT — refuse creation, never touch what exists.
     * A refusal must leave the roster byte-identical.
     */
    @Test
    void aRefusalNeverDisturbsExistingCohorts() {
        seedCohort(subA, 10);
        List<UUID> before = cohorts.findByOrgIdOrderByPositionAsc(subA.getId())
                .stream().map(Cohort::getId).toList();

        assertThatThrownBy(() -> cohortService.create(subA.getId(), req("Refused")))
                .isInstanceOf(IllegalOperationException.class);

        assertThat(cohorts.findByOrgIdOrderByPositionAsc(subA.getId())
                .stream().map(Cohort::getId).toList()).isEqualTo(before);
    }

    /** An org with no history creates its first cohort on any tier, including FREE. */
    @Test
    void theFirstCohortIsAlwaysAllowed() {
        setTier(root, SubscriptionTier.FREE);

        assertThatCode(() -> cohortService.create(subA.getId(), req("First ever")))
                .doesNotThrowAnyException();
    }

    /* --------------------------------------------------------------- helpers */

    private static CreateCohortRequest req(String name) {
        return new CreateCohortRequest(name, false);
    }

    private Organization saveOrg(String name, SubscriptionTier tier, Organization parent) {
        Organization o = new Organization();
        o.setName(name);
        o.setSubscriptionTier(tier);
        o.setActive(true);
        o.setParentOrganization(parent);
        return orgs.saveAndFlush(o);
    }

    private void setTier(Organization org, SubscriptionTier tier) {
        org.setSubscriptionTier(tier);
        orgs.saveAndFlush(org);
    }

    /** A cohort created {@code daysAgo} days ago. Flushed: the meter is a NATIVE query. */
    private void seedCohort(Organization org, int daysAgo) {
        Cohort c = new Cohort();
        c.setOrgId(org.getId());
        c.setName("Seeded " + daysAgo + "d ago");
        c.setCreatedAt(OffsetDateTime.now().minusDays(daysAgo));
        cohorts.saveAndFlush(c);
    }

    /** Authenticate the way the JWT filters do: User principal carrying the role. */
    private static void authenticate(UserRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setRole(role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority(role.name()))));
    }
}
