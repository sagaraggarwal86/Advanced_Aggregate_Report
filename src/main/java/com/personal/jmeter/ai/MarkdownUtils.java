package com.personal.jmeter.ai;

/**
 * Stateless utility methods for post-processing AI-generated Markdown.
 *
 * <p>Centralises the machine-verdict protocol so that both the CLI pipeline
 * ({@code CliReportPipeline}) and the Swing UI pipeline ({@code AiReportCoordinator})
 * share a single, tested implementation.</p>
 *
 * <p>The machine verdict token is a bare line emitted by the AI at the very end
 * of its response:
 * <pre>
 *   VERDICT:PASS
 *   VERDICT:FAIL
 * </pre>
 * It is never meant to be visible in the rendered HTML report. Before rendering,
 * callers must call {@link #stripVerdictLine(String)} to remove it. The CLI
 * additionally calls {@link #extractVerdict(String)} to map the token to an
 * exit code before stripping.</p>
 */
public final class MarkdownUtils {

    private MarkdownUtils() {}

    // ─────────────────────────────────────────────────────────────
    // Verdict extraction
    // ─────────────────────────────────────────────────────────────

    /**
     * Scans the last non-blank line of the AI markdown for a machine verdict token.
     *
     * <p>Returns {@code "UNDECISIVE"} when:</p>
     * <ul>
     *   <li>the markdown is null or blank,</li>
     *   <li>the last non-blank line is not a {@code VERDICT:} token (e.g. the AI
     *       omitted it or the response was truncated), or</li>
     *   <li>the token is not one of the two recognised values.</li>
     * </ul>
     *
     * @param markdown raw AI-generated markdown; may be null
     * @return {@code "PASS"}, {@code "FAIL"}, or {@code "UNDECISIVE"}
     */
    public static String extractVerdict(String markdown) {
        if (markdown == null || markdown.isBlank()) return "UNDECISIVE";
        String[] lines = markdown.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                if (line.equals("VERDICT:PASS")) return "PASS";
                if (line.equals("VERDICT:FAIL")) return "FAIL";
                return "UNDECISIVE";
            }
        }
        return "UNDECISIVE";
    }

    // ─────────────────────────────────────────────────────────────
    // Verdict stripping
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the markdown with the machine verdict line removed so it is never
     * rendered as visible HTML.
     *
     * <p>If no {@code VERDICT:} line is present — e.g. the AI was truncated before
     * emitting it — the original markdown is returned unchanged; the method never
     * throws.</p>
     *
     * @param markdown raw AI-generated markdown; may be null
     * @return markdown with the trailing {@code VERDICT:} line stripped and
     *         trailing whitespace trimmed; the original string if no verdict line
     *         is found; {@code markdown} itself if null or blank
     */
    public static String stripVerdictLine(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;
        String[] lines = markdown.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                if (line.startsWith("VERDICT:")) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < i; j++) {
                        sb.append(lines[j]).append("\n");
                    }
                    return sb.toString().stripTrailing();
                }
                break;
            }
        }
        return markdown;
    }
}