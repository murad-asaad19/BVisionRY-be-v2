import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Changed-line coverage gate for the backend.
 *
 * <pre>
 *   GATE COMMAND (run this verbatim):  ./mvnw -q test &amp;&amp; java tools/DiffCoverage.java
 *   Analysis only (report must exist): java tools/DiffCoverage.java
 * </pre>
 *
 * Fails (exit 1) when fewer than --min percent of the changed, coverable lines are
 * covered by the JaCoCo report {@code ./mvnw test} already writes. Prints every
 * uncovered changed line. Exits 0 with an explicit message when the diff contains
 * no measurable lines (migrations, resources, docs, pom).
 *
 * <p>Options: {@code --base <ref>} (default {@code $DIFF_BASE} or the repo's base
 * branch) — changed lines are everything from {@code git merge-base <ref> HEAD} to
 * the working tree, including uncommitted and untracked files; stacked branches
 * should pass their own base. {@code --min <pct>} (default {@code
 * $DIFF_COVERAGE_MIN} or 70). {@code --xml <path>} (default {@code
 * target/site/jacoco/jacoco.xml}).
 *
 * <p>What counts: exactly the source files JaCoCo reports on, i.e. {@code
 * src/main/java}. Tests, migrations and resources are absent from the report and
 * are therefore skipped, as are non-executable lines (imports, blank lines, plain
 * field declarations) — they can neither help nor hurt the ratio. There is
 * deliberately no second exclusion list here; the report is the one source of
 * truth. NOTE this file is a standalone JDK program on purpose: backend CI has a
 * JDK and nothing else, so the gate adds no toolchain and no dependency.
 *
 * <p>The global JaCoCo floor in pom.xml is a SEPARATE, deliberately low ratchet —
 * this gate does not touch it (roadmap section 11).
 */
public final class DiffCoverage {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\S+ \\+(\\d+)(?:,(\\d+))? @@");

    public static void main(String[] args) throws Exception {
        String base = opt(args, "--base", envOr("DIFF_BASE", "claude/production-roadmap-requirements-xp8zsf"));
        double min = Double.parseDouble(opt(args, "--min", envOr("DIFF_COVERAGE_MIN", "70")));
        Path xml = Path.of(opt(args, "--xml", "target/site/jacoco/jacoco.xml"));

        if (!Files.isRegularFile(xml)) {
            System.err.println("diff-coverage: " + xml + " not found - run `./mvnw test` first.");
            System.exit(2);
        }

        String mergeBase;
        try {
            mergeBase = git("merge-base", base, "HEAD").trim();
        } catch (IllegalStateException e) {
            System.err.println("diff-coverage: cannot resolve a merge base with \"" + base + "\" - pass --base <ref>.");
            System.exit(2);
            return;
        }

        Map<String, TreeSet<Integer>> changed = changedLines(mergeBase);
        Map<String, Map<Integer, Integer>> coverage = parseJacoco(xml);

        System.out.printf("Changed-line coverage - base %s (%s), threshold %.0f%%%n",
                base, mergeBase.substring(0, 8), min);

        int total = 0;
        int covered = 0;
        List<String> lines = new ArrayList<>();

        for (Map.Entry<String, TreeSet<Integer>> entry : new TreeMap<>(changed).entrySet()) {
            Map<Integer, Integer> hits = coverage.get(entry.getKey());
            if (hits == null) {
                continue; // not in the JaCoCo report - not measurable
            }
            TreeSet<Integer> uncovered = new TreeSet<>();
            int fileTotal = 0;
            for (int line : entry.getValue()) {
                Integer ci = hits.get(line);
                if (ci == null) {
                    continue; // not an executable line
                }
                fileTotal++;
                if (ci > 0) {
                    covered++;
                } else {
                    uncovered.add(line);
                }
            }
            if (fileTotal == 0) {
                continue;
            }
            total += fileTotal;
            int hit = fileTotal - uncovered.size();
            lines.add(String.format("  %d/%d (%.1f%%)  %s", hit, fileTotal, 100.0 * hit / fileTotal, entry.getKey()));
            if (!uncovered.isEmpty()) {
                lines.add("      uncovered: " + ranges(uncovered));
            }
        }

        if (total == 0) {
            System.out.println("No measurable changed lines (nothing in the diff is in the JaCoCo report). PASS");
            return;
        }
        lines.forEach(System.out::println);

        double pct = 100.0 * covered / total;
        System.out.printf("TOTAL %d/%d changed lines covered = %.1f%%%n", covered, total, pct);
        if (pct + 1e-9 < min) {
            System.err.printf("FAIL: changed-line coverage %.1f%% is below the %.0f%% threshold.%n", pct, min);
            System.exit(1);
        }
        System.out.println("PASS");
    }

    /** path -> line numbers added or modified since the merge base (working tree included). */
    private static Map<String, TreeSet<Integer>> changedLines(String mergeBase) throws Exception {
        Map<String, TreeSet<Integer>> changed = new LinkedHashMap<>();
        String file = null;
        for (String line : git("diff", "-U0", "--no-color", "--diff-filter=ACMR", mergeBase, "--").split("\n")) {
            if (line.startsWith("+++ ")) {
                String p = line.substring(4).trim();
                file = p.equals("/dev/null") ? null : p.replaceFirst("^b/", "");
            } else if (file != null && line.startsWith("@@")) {
                Matcher m = HUNK.matcher(line);
                if (m.find()) {
                    int from = Integer.parseInt(m.group(1));
                    int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                    add(changed, file, from, count);
                }
            }
        }
        // A brand-new file is invisible to `git diff` until staged, yet every one of
        // its lines is new - without this the gate has a hole exactly where new,
        // untested code lands.
        for (String p : git("ls-files", "--others", "--exclude-standard").split("\n")) {
            String path = p.trim();
            if (path.isEmpty() || !Files.isRegularFile(Path.of(path))) {
                continue;
            }
            // ISO-8859-1, not UTF-8: an untracked BINARY file (e.g. the osv-scanner
            // binary CI downloads into the workspace) throws MalformedInputException
            // under UTF-8. Only the line COUNT matters here, and a binary file is
            // never in the JaCoCo report, so its lines are skipped as unmeasurable.
            add(changed, path, 1,
                    Files.readAllLines(Path.of(path), StandardCharsets.ISO_8859_1).size());
        }
        return changed;
    }

    private static void add(Map<String, TreeSet<Integer>> changed, String file, int from, int count) {
        if (count <= 0) {
            return;
        }
        TreeSet<Integer> set = changed.computeIfAbsent(file, k -> new TreeSet<>());
        for (int i = 0; i < count; i++) {
            set.add(from + i);
        }
    }

    /** repo-relative java path -> (line number -> covered instructions). */
    private static Map<String, Map<Integer, Integer>> parseJacoco(Path xml) throws Exception {
        Map<String, Map<Integer, Integer>> out = new LinkedHashMap<>();
        SAXParserFactory factory = SAXParserFactory.newInstance();
        // The report carries a DOCTYPE pointing at report.dtd, which is not shipped
        // next to it. Resolve every external entity to nothing: no fetch, no XXE.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        SAXParser parser = factory.newSAXParser();

        parser.parse(xml.toFile(), new DefaultHandler() {
            private String pkg;
            private Map<Integer, Integer> current;

            @Override
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes a) {
                switch (qName) {
                    case "package" -> pkg = a.getValue("name");
                    case "sourcefile" ->
                        current = out.computeIfAbsent(
                                "src/main/java/" + pkg + "/" + a.getValue("name"), k -> new TreeMap<>());
                    case "line" -> {
                        if (current != null) {
                            current.merge(
                                    Integer.parseInt(a.getValue("nr")),
                                    Integer.parseInt(a.getValue("ci")),
                                    Integer::max);
                        }
                    }
                    default -> { }
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if (qName.equals("sourcefile")) {
                    current = null;
                }
            }
        });
        return out;
    }

    /** Collapse [1,2,3,7] into "1-3, 7" so a long failure stays readable. */
    private static String ranges(TreeSet<Integer> nums) {
        StringBuilder sb = new StringBuilder();
        Integer start = null;
        Integer prev = null;
        for (int n : nums) {
            if (start == null) {
                start = n;
            } else if (n != prev + 1) {
                appendRange(sb, start, prev);
                start = n;
            }
            prev = n;
        }
        if (start != null) {
            appendRange(sb, start, prev);
        }
        return sb.toString();
    }

    private static void appendRange(StringBuilder sb, int a, int b) {
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append(a == b ? String.valueOf(a) : a + "-" + b);
    }

    private static String opt(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
        return out;
    }

    private DiffCoverage() {
    }
}
