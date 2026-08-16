package com.bvisionry.programflow.web;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.LaunchQuotaExceededException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.domain.CohortStatus;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.repository.CohortLaunchLedgerRepository;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §8's transactional-safety clause under a REAL race: two threads, two
 * separate transactions, one remaining Starter slot. The billing-root
 * {@code SELECT ... FOR UPDATE} must serialize them — the loser re-reads the
 * winner's committed ledger row and gets the 409, never a second ledger row.
 *
 * <p>Deliberately NOT {@code @Transactional}: the race needs two committing
 * transactions. Rows are committed and left behind (fresh UUID org per run;
 * the fixed-email test users are upserted by {@code TestAuthentication}).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class CohortLaunchConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CohortService cohortService;
    @Autowired private CohortLaunchLedgerRepository ledger;
    @Autowired private CohortRepository cohorts;
    @Autowired private OrganizationRepository orgs;
    @Autowired private UserRepository users;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void twoConcurrentLaunches_exactlyOneWins() throws Exception {
        Organization root = new Organization();
        root.setName("Race Root " + UUID.randomUUID());
        root.setSubscriptionTier(SubscriptionTier.STARTER);
        root.setActive(true);
        root = orgs.saveAndFlush(root);
        User admin = TestAuthentication.authenticateAsOrgAdmin(users, root);
        UUID orgId = root.getId();

        UUID cohortA = cohortService.create(new CreateCohortRequest("Racer A")).id();
        UUID cohortB = cohortService.create(new CreateCohortRequest("Racer B")).id();
        cohortService.assignOrg(cohortA, new com.bvisionry.programflow.dto.AssignOrgRequest(
                orgId, false, java.util.List.of(), false));
        cohortService.assignOrg(cohortB, new com.bvisionry.programflow.dto.AssignOrgRequest(
                orgId, false, java.util.List.of(), false));

        AtomicInteger launched = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(racer(admin, orgId, cohortA, start, launched, refused));
            Future<?> b = pool.submit(racer(admin, orgId, cohortB, start, launched, refused));
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(launched.get()).as("exactly one launch wins").isEqualTo(1);
        assertThat(refused.get()).as("the other gets the quota 409").isEqualTo(1);
        assertThat(ledger.countByOrgId(orgId)).as("one ledger row, never two").isEqualTo(1);
    }

    /**
     * The double-click: two threads launch the SAME cohort. On an unlimited
     * tier the quota verdict never refuses, so only the cohort's own row lock
     * (and V181's UNIQUE ledger backstop) stands between a granted-or-unmetered
     * family and a double charge plus a doubled enrollment notification. The
     * loser must land on the ordinary "only a draft can be launched" 409 with
     * exactly one ledger row behind it.
     */
    @Test
    void twoConcurrentLaunchesOfTheSameCohort_chargeAndNotifyOnce() throws Exception {
        Organization root = new Organization();
        root.setName("Double-click Root " + UUID.randomUUID());
        root.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        root.setActive(true);
        root = orgs.saveAndFlush(root);
        User admin = TestAuthentication.authenticateAsOrgAdmin(users, root);
        UUID orgId = root.getId();

        UUID cohortId = cohortService.create(new CreateCohortRequest("Double-click")).id();
        cohortService.assignOrg(cohortId, new com.bvisionry.programflow.dto.AssignOrgRequest(
                orgId, false, java.util.List.of(), false));

        AtomicInteger launched = new AtomicInteger();
        AtomicInteger alreadyLaunched = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(doubleClicker(admin, cohortId, start, launched, alreadyLaunched));
            Future<?> b = pool.submit(doubleClicker(admin, cohortId, start, launched, alreadyLaunched));
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(launched.get()).as("exactly one launch wins").isEqualTo(1);
        assertThat(alreadyLaunched.get()).as("the other sees an already-launched cohort").isEqualTo(1);
        assertThat(ledger.countByOrgId(orgId)).as("one ledger row, never two").isEqualTo(1);
    }

    /**
     * An unlaunch racing a launch on a LAUNCHED cohort. Both transitions read
     * through the same {@code PESSIMISTIC_WRITE} row lock, so they serialize:
     * the second reads the first's committed status, never a lost update. Two
     * legal histories exist — launch loses on the still-LAUNCHED cohort (final
     * DRAFT), or unlaunch commits first and the relaunch wins (final LAUNCHED)
     * — but the ledger carries exactly one row (a relaunch is free for a
     * family that already paid) and launched_at survives either way.
     */
    @Test
    void concurrentLaunchAndUnlaunch_serializeOnTheRowLock() throws Exception {
        Organization root = new Organization();
        root.setName("Toggle Root " + UUID.randomUUID());
        root.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        root.setActive(true);
        root = orgs.saveAndFlush(root);
        User admin = TestAuthentication.authenticateAsOrgAdmin(users, root);
        UUID orgId = root.getId();

        UUID cohortId = cohortService.create(new CreateCohortRequest("Toggle")).id();
        cohortService.assignOrg(cohortId, new com.bvisionry.programflow.dto.AssignOrgRequest(
                orgId, false, java.util.List.of(), false));
        cohortService.launch(cohortId);

        AtomicInteger relaunched = new AtomicInteger();
        AtomicInteger launchRefused = new AtomicInteger();
        AtomicInteger unlaunched = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(doubleClicker(admin, cohortId, start, relaunched, launchRefused));
            Future<?> b = pool.submit(() -> {
                TestAuthentication.authenticate(admin);
                try {
                    start.await();
                    cohortService.unlaunch(cohortId);
                    unlaunched.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(unlaunched.get()).as("the unlaunch always lands").isEqualTo(1);
        assertThat(relaunched.get() + launchRefused.get())
                .as("the launch either wins after the unlaunch or gets the ordinary 409")
                .isEqualTo(1);
        Cohort finalState = cohorts.findById(cohortId).orElseThrow();
        assertThat(finalState.getStatus())
                .as("final status is whichever transition committed last, never a lost update")
                .isEqualTo(relaunched.get() == 1 ? CohortStatus.LAUNCHED : CohortStatus.DRAFT);
        assertThat(finalState.getLaunchedAt()).as("first-launch stamp survives").isNotNull();
        assertThat(ledger.countByOrgId(orgId)).as("relaunch never re-charges").isEqualTo(1);
    }

    private Runnable doubleClicker(User admin, UUID cohortId, CountDownLatch start,
            AtomicInteger launched, AtomicInteger alreadyLaunched) {
        return () -> {
            TestAuthentication.authenticate(admin);
            try {
                start.await();
                cohortService.launch(cohortId);
                launched.incrementAndGet();
            } catch (IllegalOperationException e) {
                alreadyLaunched.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private Runnable racer(User admin, UUID orgId, UUID cohortId, CountDownLatch start,
            AtomicInteger launched, AtomicInteger refused) {
        return () -> {
            // SecurityContext is thread-local: each racer authenticates itself.
            TestAuthentication.authenticate(admin);
            try {
                start.await();
                cohortService.launch(cohortId);
                launched.incrementAndGet();
            } catch (LaunchQuotaExceededException e) {
                refused.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
