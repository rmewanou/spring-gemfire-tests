/*
 * Copyright 2014-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pivotal.gemfire.cache.client;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionEvent;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ClientRegionFactory;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.cache.util.CacheListenerAdapter;
import com.gemstone.gemfire.distributed.ServerLauncher;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;

import org.codeprimate.lang.concurrent.ThreadUtils;
import org.codeprimate.lang.concurrent.ThreadUtils.CompletableTask;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.spring.data.gemfire.AbstractGemFireIntegrationTest;

/**
 * The DurableClientCacheIntegrationTest class is a test suite of test cases testing the contract and behavior
 * of Pivotal GemFire's Durable Client functionality.
 *
 * @author John Blum
 * @see org.junit.Test
 * @see org.spring.data.gemfire.AbstractGemFireIntegrationTest
 * @see com.gemstone.gemfire.cache.Cache
 * @see com.gemstone.gemfire.cache.Declarable
 * @see com.gemstone.gemfire.cache.Region
 * @see com.gemstone.gemfire.cache.client.ClientCache
 * @see com.gemstone.gemfire.cache.util.CacheListenerAdapter
 * @see com.gemstone.gemfire.distributed.ServerLauncher
 * @since 1.0.0
 */
public class DurableClientCacheIntegrationTest extends AbstractGemFireIntegrationTest {

  private static final AtomicInteger RUN_COUNT = new AtomicInteger(1);

  private static final int SERVER_PORT = 12480;

  private static final String SERVER_HOST = "localhost";

  private static ServerLauncher serverLauncher;

  private ClientCache clientCache;

  private List<Integer> regionCacheListenerEventValues = Collections.synchronizedList(new ArrayList<Integer>(5));

  private Region<String, Integer> example;

  @BeforeClass
  public static void setupGemFireServer() throws IOException {
    String cacheXmlPathname = "durable-client-server-cache.xml";
    String gemfireMemberName = DurableClientCacheIntegrationTest.class.getSimpleName().concat("Server");

    Properties gemfireProperties = new Properties();

    gemfireProperties.setProperty(DistributionConfig.NAME_NAME, gemfireMemberName);
    gemfireProperties.setProperty(DistributionConfig.HTTP_SERVICE_PORT_NAME, "0");
    gemfireProperties.setProperty(DistributionConfig.JMX_MANAGER_NAME, "false");
    gemfireProperties.setProperty(DistributionConfig.LOG_LEVEL_NAME, "config");
    gemfireProperties.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    gemfireProperties.setProperty(DistributionConfig.USE_CLUSTER_CONFIGURATION_NAME, "false");

    serverLauncher = startGemFireServer(TimeUnit.SECONDS.toMillis(30), cacheXmlPathname, gemfireProperties);
  }

  @AfterClass
  public static void tearDownGemFireServer() {
    stopGemFireServer(serverLauncher);
    serverLauncher = null;
  }

  @Before
  public void setup() {
    clientCache = registerInterests(createClientCache(true));

    assertThat(clientCache, is(notNullValue()));

    example = clientCache.getRegion(toRegionPath("Example"));

    assertRegion(example, "Example", DataPolicy.NORMAL);
  }

  @After
  public void tearDown() {
    closeClientCache(clientCache, true);
    regionCacheListenerEventValues.clear();
    clientCache = null;
    example = null;

    if (RUN_COUNT.get() == 1) {
      runClientCacheProducer();
      RUN_COUNT.incrementAndGet();
    }
  }

  protected ClientCache createClientCache() {
    return createClientCache(false);
  }

  protected ClientCache createClientCache(final boolean durable) {
    String gemfireClientName = DurableClientCacheIntegrationTest.class.getSimpleName().concat("Client");

    ClientCacheFactory clientCacheFactory = new ClientCacheFactory()
      .addPoolServer(SERVER_HOST, SERVER_PORT)
      .setPoolSubscriptionEnabled(true)
      .set("name", gemfireClientName)
      .set("mcast-port", "0")
      .set("log-level", "config");

    if (durable) {
      clientCacheFactory.set("durable-client-id", gemfireClientName.concat("Id"));
      clientCacheFactory.set("durable-client-timeout", "300");
    }

    ClientCache clientCache = clientCacheFactory.create();

    assertThat(clientCache, is(notNullValue()));

    ClientRegionFactory<String, Integer> clientRegionFactory = clientCache.createClientRegionFactory(
      ClientRegionShortcut.CACHING_PROXY);

    clientRegionFactory.setKeyConstraint(String.class);
    clientRegionFactory.setValueConstraint(Integer.class);

    if (durable) {
      clientRegionFactory.addCacheListener(new CacheListenerAdapter<String, Integer>() {
        @Override public void afterCreate(final EntryEvent<String, Integer> event) {
          System.out.printf("Created new entry in Region (%1$s) with key (%2$s) and value (%3$s)%n",
            event.getRegion().getFullPath(), event.getKey(), event.getNewValue());
          regionCacheListenerEventValues.add(event.getNewValue());
        }

        @Override public void afterRegionLive(final RegionEvent<String, Integer> event) {
          System.out.printf("Region (%1$s) is live!%n", event.getRegion().getName());
        }
      });
    }

    Region<String, Integer> example = clientRegionFactory.create("Example");

    assertRegion(example, "Example", DataPolicy.NORMAL);

    return clientCache;
  }

  protected ClientCache registerInterests(final ClientCache clientCache) {
    Region<String, Integer> example = clientCache.getRegion(toRegionPath("Example"));

    assertRegion(example, "Example", DataPolicy.NORMAL);

    example.registerInterestRegex(".*", InterestResultPolicy.KEYS_VALUES, true);

    //assertThat(clientCache.getDefaultPool().getPendingEventCount(), is(equalTo(RUN_COUNT.get() == 1 ? -2 : 2)));

    clientCache.readyForEvents();

    return clientCache;
  }

  protected void closeClientCache(final ClientCache clientCache) {
    closeClientCache(clientCache, false);
  }

  protected void closeClientCache(final ClientCache clientCache, final boolean keepAlive) {
    if (clientCache != null) {
      clientCache.close(keepAlive);
    }

    assertThat(clientCache == null || clientCache.isClosed(), is(true));
  }

  protected void runClientCacheProducer() {
    ClientCache localClientCache = null;

    try {
      localClientCache = createClientCache();

      Region<String, Integer> example = localClientCache.getRegion(toRegionPath("Example"));

      assertRegion(example, "Example");

      example.put("four", 4);
      example.put("five", 5);
    }
    finally {
      closeClientCache(localClientCache);
    }
  }

  protected void waitForRegionEntryEvents() {
    ThreadUtils.waitFor(TimeUnit.SECONDS.toMillis(20)).checkEvery(TimeUnit.MILLISECONDS.toMillis(500)).on(
      new CompletableTask() {
        @Override public boolean isComplete() {
          return (regionCacheListenerEventValues.size() >= 2);
        }
      }
    );
  }

  protected void assertRegionContents(Region<?, ?> region, Object... values) {
    assertThat(region.size(), is(equalTo(values.length)));

    for (Object value : values) {
      assertThat(region.containsValue(value), is(true));
    }
  }

  @Test
  public void durableClientGetsInitializedWithDataOnServer() {
    assumeThat(RUN_COUNT.get(), is(equalTo(1)));
    assertRegionContents(example, 1, 2, 3);
    assertThat(regionCacheListenerEventValues.isEmpty(), is(true));
  }

  @Test
  public void durableClientGetsUpdatesFromServerWhileClientWasOffline() {
    assumeThat(RUN_COUNT.get(), is(equalTo(2)));
    assertRegionContents(example, 1, 2, 3, 4, 5);

    // the wait is necessary since the GemFire Server will not have propagated the events in the durable client's queue
    // immediately after the client signals ready-for-events in a timely fashion before the test case
    // makes the following assertions...
    waitForRegionEntryEvents();

    assertThat(regionCacheListenerEventValues.size(), is(equalTo(2)));
    assertThat(regionCacheListenerEventValues, is(equalTo(Arrays.asList(4, 5))));
  }

  public static class RegionDataLoadingInitializer<K, V> implements Declarable {

    protected static final String DATA_POLICY_PARAMETER_NAME = "dataPolicy";
    protected static final String REGION_PARAMETER_NAME = "region";

    @SuppressWarnings("unchecked")
    protected Map<K, V> getRegionData() {
      Map<Object, Object> regionData = new HashMap<>(3);
      regionData.put("one", 1);
      regionData.put("two", 2);
      regionData.put("three", 3);
      return (Map<K, V>) regionData;
    }

    protected DataPolicy resolveDataPolicy(final String dataPolicyName) {
      try {
        for (byte ordinal = 0; ordinal < Byte.MAX_VALUE; ordinal++) {
          DataPolicy dataPolicy = DataPolicy.fromOrdinal(ordinal);

          if (dataPolicy.toString().equalsIgnoreCase(dataPolicyName)) {
            return dataPolicy;
          }
        }
      }
      catch (Exception ignore) {
      }

      return null;
    }

    @Override
    public void init(final Properties parameters) {
      String regionName = parameters.getProperty(REGION_PARAMETER_NAME);
      String dataPolicyName = parameters.getProperty(DATA_POLICY_PARAMETER_NAME);

      Cache gemfireCache = CacheFactory.getAnyInstance();

      assertThat(gemfireCache, is(notNullValue()));

      Region<K, V> region = gemfireCache.getRegion(toRegionPath(regionName));

      assertRegion(region, regionName, resolveDataPolicy(dataPolicyName));

      region.putAll(getRegionData());
    }
  }

}
