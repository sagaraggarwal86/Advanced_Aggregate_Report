package com.personal.jmeter.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AiReportService#extractAndValidateContent(String)}.
 *
 * <p>No HTTP client is involved — the method is exercised directly
 * (package-private) using hand-crafted JSON response strings that simulate
 * the OpenAI-compatible /v1/messages schema.</p>
 */
@DisplayName("AiReportService — extractAndValidateContent")
class AiReportServiceTest {

    private AiReportService service;

    @BeforeEach
    void setUp() {
        // Minimal config sufficient for the method under test.
        // Constructor order: providerKey, displayName, apiKey, model, baseUrl,
        //                    timeoutSeconds, maxTokens, temperature
        AiProviderConfig config = new AiProviderConfig(
                "gemini", "Gemini (Free)", "fake-key",
                "gemini-2.0-flash", "https://example.com/v1/chat/completions",
                120, 1500, 0.3);
        service = new AiReportService(config);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Normal (non-truncated) responses")
    class NormalResponses {

        @Test
        @DisplayName("finish_reason=stop returns content unchanged")
        void finishReasonStop_returnsContentUnchanged() throws AiServiceException {
            String json = buildResponse("Hello world", "stop");
            String result = service.extractAndValidateContent(json);
            assertEquals("Hello world", result);
        }

        @Test
        @DisplayName("finish_reason=end_turn returns content unchanged")
        void finishReasonEndTurn_returnsContentUnchanged() throws AiServiceException {
            String json = buildResponse("Analysis complete", "end_turn");
            String result = service.extractAndValidateContent(json);
            assertEquals("Analysis complete", result);
        }

        @Test
        @DisplayName("missing finish_reason field returns content unchanged")
        void missingFinishReason_returnsContentUnchanged() throws AiServiceException {
            String json = "{"
                    + "\"choices\":[{"
                    + "\"message\":{\"content\":\"No finish reason field\"},"
                    + "\"index\":0"
                    + "}]}";
            String result = service.extractAndValidateContent(json);
            assertEquals("No finish reason field", result);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Truncation detection
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Truncation detection (finish_reason=length)")
    class TruncationDetection {

        @Test
        @DisplayName("finish_reason=length appends truncation notice to content")
        void finishReasonLength_appendsTruncationNotice() throws AiServiceException {
            String json = buildResponse("## Executive Summary\nSome analysis...", "length");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.startsWith("## Executive Summary\nSome analysis..."),
                    "Original content must be preserved at the start");
            assertTrue(result.contains("⚠ Report truncated"),
                    "Truncation notice must be appended");
            assertTrue(result.contains("max_tokens"),
                    "Notice must mention max_tokens so user knows what to change");
            assertTrue(result.contains("gemini"),
                    "Notice must name the provider key");
        }

        @Test
        @DisplayName("truncation notice is rendered as a Markdown blockquote")
        void truncationNotice_isMarkdownBlockquote() throws AiServiceException {
            String json = buildResponse("content", "length");
            String result = service.extractAndValidateContent(json);
            // The notice must start with "> " so CommonMark renders it as a blockquote
            assertTrue(result.contains("\n> "),
                    "Truncation notice must be a Markdown blockquote (starts with '> ')");
        }

        @Test
        @DisplayName("original content is not modified — only appended to")
        void originalContent_notModified() throws AiServiceException {
            String original = "# Report\n\nSome data | with | pipes\n|---|---|---\n| a | b | c";
            String json = buildResponse(original, "length");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.startsWith(original),
                    "Original content must appear verbatim at the start of the result");
        }

        // ── Usage-based fallback (Gemini non-standard behaviour) ──────────────

        @Test
        @DisplayName("usage.completion_tokens == max_tokens with finish_reason=stop triggers truncation notice")
        void usageAtLimit_withFinishStop_appendsTruncationNotice() throws AiServiceException {
            // Gemini returns finish_reason="stop" even when the model hit the token limit.
            // The usage fallback must detect this case and inject the notice.
            String json = buildResponseWithUsage("## Some content...", "stop", 1500, 1500);
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("⚠ Report truncated"),
                    "Truncation notice must be appended when completion_tokens == max_tokens");
        }

        @Test
        @DisplayName("usage.completion_tokens > max_tokens with finish_reason=stop triggers truncation notice")
        void usageOverLimit_withFinishStop_appendsTruncationNotice() throws AiServiceException {
            // Defensive: some providers may report slightly over the configured limit.
            String json = buildResponseWithUsage("Some content", "stop", 1502, 1500);
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("⚠ Report truncated"),
                    "Truncation notice must be appended when completion_tokens > max_tokens");
        }

        @Test
        @DisplayName("usage.completion_tokens below max_tokens does not trigger truncation notice")
        void usageBelowLimit_noTruncationNotice() throws AiServiceException {
            // finish_reason=stop and completion_tokens well below the limit — normal completion.
            String json = buildResponseWithUsage("Complete report content.", "stop", 900, 1500);
            String result = service.extractAndValidateContent(json);
            assertFalse(result.contains("⚠ Report truncated"),
                    "No truncation notice expected when response completed normally");
            assertEquals("Complete report content.", result);
        }

        @Test
        @DisplayName("finish_reason=length takes precedence regardless of usage field")
        void finishReasonLength_precedesUsageCheck() throws AiServiceException {
            // Both signals agree — must still inject notice exactly once.
            String json = buildResponseWithUsage("Truncated content", "length", 1500, 1500);
            String result = service.extractAndValidateContent(json);
            assertTrue(result.startsWith("Truncated content"),
                    "Original content must be preserved");
            assertEquals(1, countOccurrences(result, "⚠ Report truncated"),
                    "Truncation notice must appear exactly once");
        }

        @Test
        @DisplayName("malformed usage object does not cause exception — falls back gracefully")
        void malformedUsage_noException() throws AiServiceException {
            // usage present but completion_tokens is missing — must not throw.
            String json = "{"
                    + "\"choices\":[{"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Some content\"},"
                    + "\"finish_reason\":\"stop\","
                    + "\"index\":0"
                    + "}],"
                    + "\"usage\":{\"prompt_tokens\":200}"  // completion_tokens absent
                    + "}";
            String result = service.extractAndValidateContent(json);
            assertEquals("Some content", result,
                    "Malformed usage must be ignored; content returned unchanged");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Error paths
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Error paths")
    class ErrorPaths {

        @Test
        @DisplayName("blank content throws AiServiceException")
        void blankContent_throwsException() {
            String json = buildResponse("   ", "stop");
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent(json),
                    "Blank content must throw AiServiceException");
        }

        @Test
        @DisplayName("malformed JSON throws AiServiceException")
        void malformedJson_throwsException() {
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent("{not valid json}"),
                    "Malformed JSON must throw AiServiceException");
        }

        @Test
        @DisplayName("empty choices array throws AiServiceException")
        void emptyChoices_throwsException() {
            String json = "{\"choices\":[]}";
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent(json),
                    "Empty choices array must throw AiServiceException");
        }

        @Test
        @DisplayName("missing choices field throws AiServiceException")
        void missingChoices_throwsException() {
            String json = "{\"id\":\"abc\",\"model\":\"gemini\"}";
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent(json),
                    "Missing choices field must throw AiServiceException");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal OpenAI-compatible choices JSON string.
     *
     * @param content      the message content
     * @param finishReason the finish_reason value
     * @return JSON string
     */
    private static String buildResponse(String content, String finishReason) {
        // Escape content for embedding in JSON
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{"
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"},"
                + "\"finish_reason\":\"" + finishReason + "\","
                + "\"index\":0"
                + "}]"
                + "}";
    }

    /**
     * Builds a minimal OpenAI-compatible JSON response that includes a {@code usage} block.
     * Used to test the usage-based truncation fallback.
     *
     * @param content           the message content
     * @param finishReason      the finish_reason value
     * @param completionTokens  completion_tokens to report in the usage block
     * @param configMaxTokens   max_tokens the service config was initialised with
     * @return JSON string
     */
    private static String buildResponseWithUsage(String content, String finishReason,
                                                 int completionTokens, int configMaxTokens) {
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{"
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"},"
                + "\"finish_reason\":\"" + finishReason + "\","
                + "\"index\":0"
                + "}],"
                + "\"usage\":{"
                + "\"prompt_tokens\":500,"
                + "\"completion_tokens\":" + completionTokens + ","
                + "\"total_tokens\":" + (500 + completionTokens)
                + "}"
                + "}";
    }

    /** Returns the number of non-overlapping occurrences of {@code sub} in {@code str}. */
    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx   = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}