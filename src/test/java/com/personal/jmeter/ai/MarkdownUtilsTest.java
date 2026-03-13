package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MarkdownUtils}.
 *
 * <p>Covers every branch of {@link MarkdownUtils#extractVerdict(String)} and
 * {@link MarkdownUtils#stripVerdictLine(String)}, including the edge cases
 * that arise when the AI response is truncated before emitting the verdict.</p>
 */
@DisplayName("MarkdownUtils")
class MarkdownUtilsTest {

    // ─────────────────────────────────────────────────────────────
    // extractVerdict
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractVerdict")
    class ExtractVerdict {

        @Test
        @DisplayName("VERDICT:PASS on last non-blank line returns PASS")
        void lastLine_verdictPass() {
            String md = "## Verdict\nAll SLAs met.\nVERDICT:PASS";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("VERDICT:FAIL on last non-blank line returns FAIL")
        void lastLine_verdictFail() {
            String md = "## Verdict\nError rate exceeded SLA.\nVERDICT:FAIL";
            assertEquals("FAIL", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("trailing blank lines after VERDICT token are ignored")
        void trailingBlankLines_afterVerdict() {
            String md = "Some analysis.\nVERDICT:PASS\n\n  \n";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("no VERDICT token present returns UNDECISIVE")
        void noVerdictToken_returnsUndecisive() {
            String md = "## Root Cause Hypotheses\nServer saturated — infrastructure/";
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("null input returns UNDECISIVE")
        void nullInput_returnsUndecisive() {
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict(null));
        }

        @Test
        @DisplayName("blank input returns UNDECISIVE")
        void blankInput_returnsUndecisive() {
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict("   \n  \n"));
        }

        @Test
        @DisplayName("VERDICT:UNKNOWN (unrecognised token) returns UNDECISIVE")
        void unknownVerdictToken_returnsUndecisive() {
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict("VERDICT:UNKNOWN"));
        }

        @Test
        @DisplayName("VERDICT token not on last non-blank line returns UNDECISIVE")
        void verdictNotOnLastLine_returnsUndecisive() {
            // AI emitted VERDICT mid-response, then continued — treat as absent
            String md = "VERDICT:PASS\nThis is extra text after the verdict.";
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict(md));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // stripVerdictLine
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("stripVerdictLine")
    class StripVerdictLine {

        @Test
        @DisplayName("VERDICT:PASS line is removed from end of markdown")
        void stripVerdictPass() {
            String md = "## Verdict\nAll metrics within SLA.\nVERDICT:PASS";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT:PASS"), "VERDICT line must be removed");
            assertTrue(result.contains("All metrics within SLA."), "preceding content preserved");
        }

        @Test
        @DisplayName("VERDICT:FAIL line is removed from end of markdown")
        void stripVerdictFail() {
            String md = "## Verdict\nError rate breached SLA.\nVERDICT:FAIL";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT:FAIL"), "VERDICT line must be removed");
            assertTrue(result.contains("Error rate breached SLA."), "preceding content preserved");
        }

        @Test
        @DisplayName("trailing blank lines after VERDICT are stripped; preceding content preserved")
        void trailingBlankLinesStripped() {
            String md = "Some content.\nVERDICT:FAIL\n\n\n";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT"), "VERDICT line must be removed");
            assertTrue(result.endsWith("Some content."), "no trailing whitespace after strip");
        }

        @Test
        @DisplayName("no VERDICT line present returns original markdown unchanged")
        void noVerdictLine_returnsOriginal() {
            // Simulates truncated response (Gemini / missing verdict)
            String md = "## Root Cause Hypotheses\nServer saturated — infrastructure/";
            assertEquals(md, MarkdownUtils.stripVerdictLine(md));
        }

        @Test
        @DisplayName("null input returns null without throwing")
        void nullInput_returnsNull() {
            assertNull(MarkdownUtils.stripVerdictLine(null));
        }

        @Test
        @DisplayName("blank input returns blank without throwing")
        void blankInput_returnsBlank() {
            String blank = "  \n  ";
            assertEquals(blank, MarkdownUtils.stripVerdictLine(blank));
        }

        @Test
        @DisplayName("only the verdict line is removed; all other content intact")
        void onlyVerdictLineRemoved() {
            String body = "## Executive Summary\nTest ran.\n\n## Verdict\nFAIL by error rate.";
            String md   = body + "\nVERDICT:FAIL";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertTrue(result.contains("## Executive Summary"), "earlier content preserved");
            assertTrue(result.contains("## Verdict"), "Verdict section heading preserved");
            assertTrue(result.contains("FAIL by error rate."), "verdict prose preserved");
            assertFalse(result.contains("VERDICT:FAIL"), "machine token removed");
        }

        @Test
        @DisplayName("VERDICT token mid-document (not last non-blank line) is left untouched")
        void verdictMidDocument_leftUntouched() {
            String md = "VERDICT:PASS\nThis is extra text after the verdict.\n";
            // Last non-blank line is "This is extra text…" — not a VERDICT line.
            // stripVerdictLine must leave the document unchanged.
            assertEquals(md, MarkdownUtils.stripVerdictLine(md));
        }
    }
}