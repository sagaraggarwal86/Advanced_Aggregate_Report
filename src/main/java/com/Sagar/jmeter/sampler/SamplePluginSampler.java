package com.sagar.jmeter.sampler;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SamplePluginSampler extends AbstractSampler {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SamplePluginSampler.class);

    // Keys used to store properties (shown in GUI)
    public static final String TARGET_URL = "SamplePlugin.targetUrl";
    public static final String TIMEOUT_MS = "SamplePlugin.timeoutMs";
    public static final String PAYLOAD    = "SamplePlugin.payload";

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setDataType(SampleResult.TEXT);

        String url     = getTargetUrl();
        int    timeout = getTimeoutMs();
        String payload = getPayload();

        log.info("SamplePlugin → URL: {}, Timeout: {}ms", url, timeout);

        result.sampleStart(); // ← start measuring time

        try {
            // 👇 Replace this with your real logic:
            //    e.g., call a custom API, message queue, database, etc.
            Thread.sleep(50); // simulate processing

            String response = String.format(
                    "Plugin OK!\nURL: %s\nTimeout: %dms\nPayload: %s",
                    url, timeout, payload
            );

            result.sampleEnd();
            result.setSuccessful(true);
            result.setResponseCodeOK();
            result.setResponseMessage("OK");
            result.setResponseData(response, "UTF-8");

        } catch (InterruptedException e) {
            result.sampleEnd();
            result.setSuccessful(false);
            result.setResponseCode("500");
            result.setResponseMessage("Interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            result.sampleEnd();
            result.setSuccessful(false);
            result.setResponseCode("500");
            result.setResponseMessage("Error: " + e.getMessage());
            log.error("SamplePlugin error", e);
        }

        return result;
    }

    // Getters & setters bound to JMeter's property store
    public String getTargetUrl() { return getPropertyAsString(TARGET_URL, "https://example.com"); }
    public void   setTargetUrl(String v)  { setProperty(TARGET_URL, v); }

    public int    getTimeoutMs() { return getPropertyAsInt(TIMEOUT_MS, 5000); }
    public void   setTimeoutMs(int v)     { setProperty(TIMEOUT_MS, v); }

    public String getPayload()   { return getPropertyAsString(PAYLOAD, ""); }
    public void   setPayload(String v)    { setProperty(PAYLOAD, v); }
}