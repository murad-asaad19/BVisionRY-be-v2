package com.bvisionry.programflow.web;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.LaunchQuotaExceededException;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.repository.CohortLaunchLedgerRepository;
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
