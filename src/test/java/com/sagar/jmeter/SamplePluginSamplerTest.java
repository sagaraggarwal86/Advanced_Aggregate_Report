package com.sagar.jmeter;

import com.sagar.jmeter.sampler.SamplePluginSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SamplePluginSamplerTest {

    private SamplePluginSampler sampler;

    @BeforeEach
    void setUp() {
        sampler = new SamplePluginSampler();
        sampler.setName("Test Sampler");
    }

    @Test
    void testDefaultValues() {
        assertEquals("https://example.com", sampler.getTargetUrl());
        assertEquals(5000, sampler.getTimeoutMs());
        assertEquals("", sampler.getPayload());
    }

    @Test
    void testSetUrl() {
        sampler.setTargetUrl("https://myapi.com");
        assertEquals("https://myapi.com", sampler.getTargetUrl());
    }

    @Test
    void testSampleReturnsSuccess() {
        SampleResult result = sampler.sample(null);
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals("200", result.getResponseCode());
    }

    @Test
    void testSampleLabelMatchesName() {
        sampler.setName("My Sampler");
        assertEquals("My Sampler", sampler.sample(null).getSampleLabel());
    }

    @Test
    void testResponseContainsUrl() {
        sampler.setTargetUrl("https://test.com");
        String response = new String(sampler.sample(null).getResponseData());
        assertTrue(response.contains("https://test.com"));
    }
}