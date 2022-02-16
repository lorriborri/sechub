// SPDX-License-Identifier: MIT
package com.daimler.sechub.adapter.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.daimler.sechub.adapter.AbstractAdapter;
import com.daimler.sechub.adapter.AdapterConfig;
import com.daimler.sechub.adapter.AdapterContext;
import com.daimler.sechub.adapter.AdapterException;
import com.daimler.sechub.adapter.AdapterMetaData;
import com.daimler.sechub.adapter.AdapterRuntimeContext;
import com.daimler.sechub.adapter.support.MockSupport;

/**
 * Abstract base class for mocked adapters. Will rely on
 * {@link MockedAdapterSetupService} to support automated results depending on
 * target urls... So the adapter is configurable by a config file! For demo mode
 * and integration testing this is very nice, we just have to setup the path to
 * config file!
 *
 * @author Albert Tregnaghi
 *
 * @param <A>
 * @param <C>
 */
public abstract class AbstractMockedAdapter<A extends AdapterContext<C>, C extends AdapterConfig> extends AbstractAdapter<A, C> implements MockedAdapter<C> {

    /**
     * In tests we want to have the possibility to check if adapter meta data is
     * really reused and appended on resilience retries (but only for same SecHub
     * JobUUID and product executor configuration!)<br>
     * <br>
     * To do this we have the special key "reused" - each mocked adapter will append
     * "+1" two times on every adapter call. So when a adapter call has failed and
     * will be called again (resilience) we are able to check if adapter meta data
     * is handled as expected.
     *
     * Here an example:
     *
     * <pre>
     *  null -> "+1" -> "+1+1"
     * </pre>
     *
     * When adapter is started . Another start will transform:
     *
     * <pre>
     *
     * "+1+1" -> "+1+1+1" ->"+1+1+1+1"
     * </pre>
     *
     * and so on.
     */
    public static final String KEY_METADATA_REUSED = "reused";

    private static final String KEY_METADATA_INITIAL = "initial";
    private static final String KEY_METADATA_COMBINED = "combined";
    private static final String KEY_METADATA_BEFORE_WAIT = "before_wait";
    private static final String DEFAULT_METADATA_MOCK_BEFORE_WAIT = "before-wait";
    private static final String DEFAULT_METADATA_MOCK_INITIAL_VALUE = "initial-value";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractMockedAdapter.class);
    final MockSupport mockSupport = new MockSupport();

    @Value("${sechub.adapter.mock.sanitycheck.enabled:false}")
    private boolean mockSanityCheckEnabled;

    @Autowired
    protected MockedAdapterSetupService setupService;

    @Override
    protected final String getAPIPrefix() {
        return "mockAdapterAPICall";
    }

    @Override
    public int getAdapterVersion() {
        return 1;
    }

    public final String execute(C config, AdapterRuntimeContext runtimeContext) throws AdapterException {
        long timeStarted = System.currentTimeMillis();
        if (mockSanityCheckEnabled) {
            executeMockSanityCheck(config);
        }

        MockedAdapterSetupEntry setup = setupService.getSetupFor(this, config);
        if (setup == null) {
            LOG.info("did not found adapter setup so returning empty string");
            return "";
        }

        String target = config.getTargetAsString();

        /* first meta data persistence call - write some test meta data... */
        writeInitialAndReusedMetaData(config, runtimeContext);

        /*
         * throw an error - if we have configured the adapter to throw an error to
         * simulate product failures...
         */
        throwExceptionIfConfigured(config, setup, target);

        /* no error wanted, so load result.... */
        String result = loadResultAsConfigured(setup, target);

        /* second meta data persistence call - write/update some test meta data... */
        writeBeforeWaitAndReusedMetaData(config, runtimeContext);

        /* wait configured time (simulates product elapsing time) */
        waitIfConfigured(timeStarted, setup, target);

        assertMetaDataHandledAsExpected(config, runtimeContext);

        LOG.trace("Returning content:{}", result);

        return result;
    }

    protected void writeInitialAndReusedMetaData(C config, AdapterRuntimeContext runtimeContext) {
        AdapterMetaData metaData = assertMetaData(runtimeContext);
        metaData.setValue(KEY_METADATA_INITIAL, DEFAULT_METADATA_MOCK_INITIAL_VALUE);
        metaData.setValue(KEY_METADATA_COMBINED, "1");

        updateReusedEntryAndPersistMetaData(metaData, runtimeContext);
    }

    protected void writeBeforeWaitAndReusedMetaData(C config, AdapterRuntimeContext runtimeContext) {
        AdapterMetaData metaData = assertMetaData(runtimeContext);
        metaData.setValue(KEY_METADATA_BEFORE_WAIT, DEFAULT_METADATA_MOCK_BEFORE_WAIT);

        String value = metaData.getValue(KEY_METADATA_COMBINED);
        metaData.setValue(KEY_METADATA_COMBINED, value + "+2");

        updateReusedEntryAndPersistMetaData(metaData, runtimeContext);
    }

    protected void assertMetaDataHandledAsExpected(C config, AdapterRuntimeContext runtimeContext) {
        AdapterMetaData metaData = assertMetaData(runtimeContext);
        metaData.setValue(KEY_METADATA_BEFORE_WAIT, DEFAULT_METADATA_MOCK_BEFORE_WAIT);

        String combined = metaData.getValue(KEY_METADATA_COMBINED);
        String initial = metaData.getValue(KEY_METADATA_INITIAL);
        String before = metaData.getValue(KEY_METADATA_BEFORE_WAIT);

        if (!"1+2".equals(combined)) {
            throw new IllegalStateException("meta data for combined value was not as expected ,but:" + combined);
        }
        if (!DEFAULT_METADATA_MOCK_INITIAL_VALUE.equals(initial)) {
            throw new IllegalStateException("meta data for initial value was not as expected ,but:" + initial);
        }
        if (!DEFAULT_METADATA_MOCK_BEFORE_WAIT.equals(before)) {
            throw new IllegalStateException("meta data for before value was not as expected ,but:" + before);
        }
    }

    private void updateReusedEntryAndPersistMetaData(AdapterMetaData metaData, AdapterRuntimeContext runtimeContext) {
        String value = metaData.getValue(KEY_METADATA_REUSED);
        if (value == null) {
            value = "";
        }
        metaData.setValue(KEY_METADATA_REUSED, value + "+1");
        runtimeContext.getCallback().persist(metaData);

    }

    private AdapterMetaData assertMetaData(AdapterRuntimeContext runtimeContext) {
        AdapterMetaData metaData = runtimeContext.getMetaData();
        if (metaData == null) {
            throw new IllegalStateException("Meta data may not be null inside adapter!");
        }
        return metaData;
    }

    private String loadResultAsConfigured(MockedAdapterSetupEntry setup, String target) {
        String resultFilePath = setup.getResultFilePathFor(target);
        LOG.info("adapter instance {} will use result file path :{} for targeturl:{}", hashCode(), resultFilePath, target);
        if (resultFilePath == null) {
            throw new IllegalStateException("result file path not configured!");
        }

        /* load the result file from disk: */
        String resource = mockSupport.loadResourceString(resultFilePath);
        return resource;
    }

    private void throwExceptionIfConfigured(C config, MockedAdapterSetupEntry setup, String target) throws AdapterException {
        if (setup.isThrowingAdapterExceptionFor(target)) {
            LOG.info("adapter instance {} setup wants an error, so start throwing", hashCode());
            throw asAdapterException("Wanted mock failure for " + target, config);
        }
    }

    private void waitIfConfigured(long timeStarted, MockedAdapterSetupEntry setup, String target) {
        long wantedMs = setup.getTimeToElapseInMilliseconds(target);
        LOG.debug("adapter instance {}, wanted {} milliseconds to elapse for target {}", hashCode(), wantedMs, target);
        long elapsedMs = System.currentTimeMillis() - timeStarted;
        long timeToWait = wantedMs - elapsedMs;
        LOG.debug("apter instance {}, will wait {} milliseconds to elapse for target {}", hashCode(), wantedMs, target);
        if (timeToWait > 0) {
            try {
                Thread.sleep(timeToWait);
                LOG.debug("adapter instance {}, waited {} milliseconds for target {}", hashCode(), timeToWait, target);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected abstract void executeMockSanityCheck(C config);

    public String getAdapterId() {
        return createAdapterId();
    }

    @Override
    protected String createAdapterId() {
        /*
         * currently same as in normal adapters but to ensure we got simple names here
         * always - even when changed in adapters - this is overridden
         */
        return getClass().getSimpleName();
    }

    /**
     * Standard implementation will return path like
     * <code>"/adapter/mockdata/v$versionNr/$SimpleNameOfMockedAdapterClass/$TrafficLightName.xml"</code><br>
     * <br>
     * An
     * example:<code>"/adapter/mockdata/v1/MockedNetspakerAdapter/green.xml</code>
     *
     * @param wantedTrafficLight
     * @return
     */
    protected String getPathToMockResultFile(String wantedTrafficLight) {
        return "/adapter/mockdata/" + createAdapterId() + "/v" + getAdapterVersion() + "/" + wantedTrafficLight + "." + getMockDataFileEnding();
    }

    protected String getMockDataFileEnding() {
        return "xml";
    }

    protected void handleSanityFailure(String message) {
        LOG.error("SANITY CHECK FAILURE for {},{}", getClass().getSimpleName(), message);
        throw new IllegalStateException("Sanity check failed:" + message);
    }
}
