package com.personal.jmeter.ai;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PromptBuilder#build}.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification.
 * Confirms that {@code build} returns a well-formed {@link PromptContent}
 * whose system prompt is non-blank and whose user message embeds the
 * supplied scenario context and aggregated statistics.</p>
 */
@DisplayName("PromptBuilder — build")
class PromptBuilderTest {

    /**
     * Initialise {@link JMeterUtils#appProperties} once for this test class.
     * {@code SampleResult}'s constructor calls {@code JMeterUtils.getPropDefault()},
     * which logs a WARN and falls back gracefully when properties are absent,
     * but loading the bundled {@code jmeter.properties} suppresses those warnings.
     */
    @BeforeAll
    static void initJMeter() {
        URL propsUrl = PromptBuilderTest.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Creates a passed {@link SampleResult} with the given elapsed time in ms. */
    private static SampleResult passedSample(long elapsedMs) {
        SampleResult sr = new SampleResult();
        sr.setStampAndTime(sr.getTimeStamp(), elapsedMs);
        sr.setSuccessful(true);
        return sr;
    }

    /** Creates a failed {@link SampleResult} with the given elapsed time in ms. */
    private static SampleResult failedSample(long elapsedMs) {
        SampleResult sr = new SampleResult();
        sr.setStampAndTime(sr.getTimeStamp(), elapsedMs);
        sr.setSuccessful(false);
        return sr;
    }

    /**
     * Builds a minimal results map with a TOTAL row and one labelled transaction.
     * The TOTAL accumulates both samples; the labelled entry holds only the first.
     */
    private static Map<String, SamplingStatCalculator> minimalResults() {
        SamplingStatCalculator total = new SamplingStatCalculator("TOTAL");
        SamplingStatCalculator login = new SamplingStatCalculator("Login");

        SampleResult s1 = passedSample(300L);
        SampleResult s2 = passedSample(700L);

        total.addSample(s1);
        total.addSample(s2);
        login.addSample(s1);

        Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
        results.put("TOTAL", total);
        results.put("Login", login);
        return results;
    }

    // ─────────────────────────────────────────────────────────────
    // PromptContent contract
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PromptContent contract")
    class PromptContentContractTests {

        @Test
        @DisplayName("returns non-null PromptContent")
        void returnsNonNull() {
            PromptContent content = new PromptBuilder()
                    .build(minimalResults(), 90, PromptRequest.empty());
            assertNotNull(content);
        }

        @Test
        @DisplayName("system prompt is non-blank")
        void systemPromptNonBlank() {
            PromptContent content = new PromptBuilder()
                    .build(minimalResults(), 90, PromptRequest.empty());
            assertFalse(content.systemPrompt().isBlank(),
                    "systemPrompt must not be blank");
        }

        @Test
        @DisplayName("user message is non-blank")
        void userMessageNonBlank() {
            PromptContent content = new PromptBuilder()
                    .build(minimalResults(), 90, PromptRequest.empty());
            assertFalse(content.userMessage().isBlank(),
                    "userMessage must not be blank");
        }

        @Test
        @DisplayName("system prompt is identical across two calls — it is a constant")
        void systemPromptIsConstant() {
            PromptBuilder builder = new PromptBuilder();
            String first  = builder.build(minimalResults(), 90, PromptRequest.empty()).systemPrompt();
            String second = builder.build(minimalResults(), 50, PromptRequest.empty()).systemPrompt();
            assertEquals(first, second,
                    "systemPrompt must be the static constant regardless of input");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // User message content
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("User message content")
    class UserMessageContentTests {

        @Test
        @DisplayName("user message embeds scenario name from PromptRequest")
        void embedsScenarioName() {
            PromptRequest req = new PromptRequest("50", "Checkout Flow", "", "", "");
            String msg = new PromptBuilder()
                    .build(minimalResults(), 90, req)
                    .userMessage();
            assertTrue(msg.contains("Checkout Flow"),
                    "userMessage must contain the scenario name");
        }

        @Test
        @DisplayName("user message embeds user count from PromptRequest")
        void embedsUserCount() {
            PromptRequest req = new PromptRequest("200", "", "", "", "");
            String msg = new PromptBuilder()
                    .build(minimalResults(), 90, req)
                    .userMessage();
            assertTrue(msg.contains("200"),
                    "userMessage must contain the user count");
        }

        @Test
        @DisplayName("user message contains globalStats JSON section")
        void containsGlobalStatsJson() {
            String msg = new PromptBuilder()
                    .build(minimalResults(), 90, PromptRequest.empty())
                    .userMessage();
            assertTrue(msg.contains("globalStats"),
                    "userMessage must include the globalStats JSON section");
        }

        @Test
        @DisplayName("blank PromptRequest fields render as 'Not provided'")
        void blankFieldsRenderAsNotProvided() {
            String msg = new PromptBuilder()
                    .build(minimalResults(), 90, PromptRequest.empty())
                    .userMessage();
            assertTrue(msg.contains("Not provided"),
                    "blank PromptRequest fields must appear as 'Not provided'");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Edge cases
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("empty results map (no TOTAL row) — returns valid PromptContent without throwing")
        void emptyResultsNoThrow() {
            assertDoesNotThrow(() ->
                    new PromptBuilder().build(new LinkedHashMap<>(), 90, PromptRequest.empty()));
        }

        @Test
        @DisplayName("results with errors — errorEndpoints section is included in user message")
        void errorSamplesIncludedInMessage() {
            SamplingStatCalculator total = new SamplingStatCalculator("TOTAL");
            SamplingStatCalculator search = new SamplingStatCalculator("Search");

            SampleResult failed = failedSample(500L);
            total.addSample(failed);
            search.addSample(failed);

            Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("TOTAL", total);
            results.put("Search", search);

            String msg = new PromptBuilder()
                    .build(results, 90, PromptRequest.empty())
                    .userMessage();

            assertTrue(msg.contains("errorEndpoints"),
                    "userMessage must include the errorEndpoints JSON section");
        }

        @Test
        @DisplayName("build rejects null results with NullPointerException")
        void nullResultsThrows() {
            assertThrows(NullPointerException.class, () ->
                    new PromptBuilder().build(null, 90, PromptRequest.empty()));
        }

        @Test
        @DisplayName("build rejects null PromptRequest with NullPointerException")
        void nullRequestThrows() {
            assertThrows(NullPointerException.class, () ->
                    new PromptBuilder().build(minimalResults(), 90, null));
        }
    }
}