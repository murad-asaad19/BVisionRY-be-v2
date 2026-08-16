package com.bvisionry.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every link this backend hands to a HUMAN must be a path the web app actually
 * serves.
 *
 * <h2>Why this exists</h2>
 * It did not, and the cost was the whole founder loop. Eleven call sites emitted
 * {@code "/my/assessments/..."} as a web link — in three in-app notifications and,
 * worse, in the assessment-assigned, reminder and results-ready EMAILS. There has
 * never been a {@code /my} route in the web app: no page, no {@code redirects()}
 * entry in {@code next.config.ts}, no rewrite in {@code proxy.ts}. Every founder
 * who clicked through from those emails got a 404, in the channel where a broken
 * link is least recoverable, and it shipped because <b>nothing tested it</b>.
 *
 * <p>The API prefix is the trap. {@code /api/my/assessments/{id}} is a real and
 * correct endpoint, so the string looks right in isolation and reads right in
 * review; only the missing {@code /api} tells you it is a browser URL rather than
 * a fetch target. Both spellings coexist in this codebase on purpose, which is
 * exactly why a human diff review kept passing over it.
 *
 * <h2>What this pins, and what it cannot</h2>
 * A source scan, not a route check — the backend cannot see the Next.js route
 * tree, so this cannot prove a link RESOLVES. It proves the narrower thing that
 * would have caught the actual bug: no production source emits a browser path
 * under a prefix the web app does not have. Adding a new bad prefix still needs a
 * human; re-introducing this one does not.
 *
 * <p>If the web app ever genuinely serves {@code /my/**}, delete this test rather
 * than working around it — a guard nobody can satisfy gets disabled, and a
 * disabled guard is worse than none.
 */
class FrontendLinkPathsTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /**
     * A string literal opening with {@code /my/} — i.e. a browser path, since the
     * API spelling is {@code "/api/my/..."} and does not match. The leading quote
     * is load-bearing: it anchors to the start of the literal so the API form,
     * and any interpolation of it, is left alone.
     */
    private static final Pattern BROWSER_PATH_UNDER_MY = Pattern.compile("\"/my/");

    /**
     * A Spring route DECLARATION, which is the one legitimate way {@code "/my/}
     * appears in this codebase: {@code @GetMapping("/my/enrollments")} declares a
     * path this backend SERVES (mounted under the {@code /api} context), it does
     * not hand anyone a link. Excluding these is what keeps the guard specific
     * enough to stay enabled — see the class javadoc on disabled guards.
     */
    private static final Pattern ROUTE_DECLARATION = Pattern.compile("@\\w*Mapping\\s*\\(");

    @Test
    @DisplayName("no production source hands a human a /my/** link — the web app has no such route")
    void noSourceEmitsAFrontendPathUnderMy() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (ROUTE_DECLARATION.matcher(line).find()) {
                        continue;
                    }
                    Matcher matcher = BROWSER_PATH_UNDER_MY.matcher(line);
                    if (matcher.find()) {
                        offenders.add("%s:%d  %s".formatted(
                                SOURCE_ROOT.relativize(file), i + 1, lines.get(i).trim()));
                    }
                }
            }
        }

        assertThat(offenders)
                .describedAs("""
                        These emit a browser path under /my/, which the web app does not serve — \
                        a founder following one gets a 404. The real routes are \
                        /app/assessments/{id}, /app/assessments/{id}/results and \
                        /app/assessments/{id}/post-completion-survey. If you meant the API \
                        endpoint, that spelling is "/api/my/..." and this test ignores it.""")
                .isEmpty();
    }
}
