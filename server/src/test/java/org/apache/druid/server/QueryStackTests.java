/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.inject.Injector;
import org.apache.druid.client.cache.Cache;
import org.apache.druid.client.cache.CacheConfig;
import org.apache.druid.guice.DruidInjectorBuilder;
import org.apache.druid.guice.ExpressionModule;
import org.apache.druid.guice.SegmentWranglerModule;
import org.apache.druid.guice.StartupInjectorBuilder;
import org.apache.druid.initialization.CoreInjectorBuilder;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.BrokerParallelMergeConfig;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.DefaultGenericQueryMetricsFactory;
import org.apache.druid.query.DefaultQueryRunnerFactoryConglomerate;
import org.apache.druid.query.DruidProcessingConfig;
import org.apache.druid.query.FrameBasedInlineDataSource;
import org.apache.druid.query.InlineDataSource;
import org.apache.druid.query.LookupDataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.RetryQueryRunnerConfig;
import org.apache.druid.query.TestBufferPool;
import org.apache.druid.query.expression.LookupEnabledTestExprMacroTable;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.groupby.GroupByQueryConfig;
import org.apache.druid.query.groupby.GroupByQueryRunnerFactory;
import org.apache.druid.query.groupby.GroupByQueryRunnerTest;
import org.apache.druid.query.groupby.TestGroupByBuffers;
import org.apache.druid.query.lookup.LookupExtractorFactoryContainerProvider;
import org.apache.druid.query.metadata.SegmentMetadataQueryConfig;
import org.apache.druid.query.metadata.SegmentMetadataQueryQueryToolChest;
import org.apache.druid.query.metadata.SegmentMetadataQueryRunnerFactory;
import org.apache.druid.query.metadata.metadata.SegmentMetadataQuery;
import org.apache.druid.query.operator.WindowOperatorQuery;
import org.apache.druid.query.operator.WindowOperatorQueryQueryRunnerFactory;
import org.apache.druid.query.operator.WindowOperatorQueryQueryToolChest;
import org.apache.druid.query.policy.NoopPolicyEnforcer;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.scan.ScanQueryConfig;
import org.apache.druid.query.scan.ScanQueryEngine;
import org.apache.druid.query.scan.ScanQueryQueryToolChest;
import org.apache.druid.query.scan.ScanQueryRunnerFactory;
import org.apache.druid.query.search.SearchQuery;
import org.apache.druid.query.search.SearchQueryConfig;
import org.apache.druid.query.search.SearchQueryQueryToolChest;
import org.apache.druid.query.search.SearchQueryRunnerFactory;
import org.apache.druid.query.search.SearchStrategySelector;
import org.apache.druid.query.timeboundary.TimeBoundaryQuery;
import org.apache.druid.query.timeboundary.TimeBoundaryQueryRunnerFactory;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.timeseries.TimeseriesQueryEngine;
import org.apache.druid.query.timeseries.TimeseriesQueryQueryToolChest;
import org.apache.druid.query.timeseries.TimeseriesQueryRunnerFactory;
import org.apache.druid.query.topn.TopNQuery;
import org.apache.druid.query.topn.TopNQueryConfig;
import org.apache.druid.query.topn.TopNQueryQueryToolChest;
import org.apache.druid.query.topn.TopNQueryRunnerFactory;
import org.apache.druid.query.union.UnionQuery;
import org.apache.druid.query.union.UnionQueryLogic;
import org.apache.druid.segment.ReferenceCountedSegmentProvider;
import org.apache.druid.segment.SegmentWrangler;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.join.FrameBasedInlineJoinableFactory;
import org.apache.druid.segment.join.InlineJoinableFactory;
import org.apache.druid.segment.join.JoinableFactory;
import org.apache.druid.segment.join.JoinableFactoryWrapper;
import org.apache.druid.segment.join.LookupJoinableFactory;
import org.apache.druid.segment.join.MapJoinableFactory;
import org.apache.druid.server.initialization.ServerConfig;
import org.apache.druid.server.metrics.SubqueryCountStatsProvider;
import org.apache.druid.server.scheduling.ManualQueryPrioritizationStrategy;
import org.apache.druid.server.scheduling.NoQueryLaningStrategy;
import org.apache.druid.sql.calcite.util.CacheTestHelperModule;
import org.apache.druid.sql.calcite.util.CacheTestHelperModule.ResultCacheMode;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.utils.JvmUtils;
import org.junit.Assert;
import org.junit.rules.ExternalResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for creating query-stack objects for tests.
 */
public class QueryStackTests
{
  public static class Junit4ConglomerateRule extends ExternalResource
  {
    private Closer closer;
    private QueryRunnerFactoryConglomerate conglomerate;

    @Override
    protected void before()
    {
      closer = Closer.create();
      conglomerate = QueryStackTests.createQueryRunnerFactoryConglomerate(closer);
    }

    @Override
    protected void after()
    {
      try {
        closer.close();
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      conglomerate = null;
      closer = null;
    }

    public QueryRunnerFactoryConglomerate getConglomerate()
    {
      return conglomerate;
    }
  }


  public static final QueryScheduler DEFAULT_NOOP_SCHEDULER = new QueryScheduler(
      0,
      ManualQueryPrioritizationStrategy.INSTANCE,
      NoQueryLaningStrategy.INSTANCE,
      new ServerConfig()
  );

  public static final int DEFAULT_NUM_MERGE_BUFFERS = -1;

  private static final int COMPUTE_BUFFER_SIZE = 10 * 1024 * 1024;

  private QueryStackTests()
  {
    // No instantiation.
  }

  public static ClientQuerySegmentWalker createClientQuerySegmentWalker(
      final Injector injector,
      final QuerySegmentWalker clusterWalker,
      final QuerySegmentWalker localWalker,
      final QueryRunnerFactoryConglomerate conglomerate,
      final JoinableFactory joinableFactory,
      final ServerConfig serverConfig,
      final ServiceEmitter emitter
  )
  {
    return new ClientQuerySegmentWalker(
        emitter,
        clusterWalker,
        localWalker,
        conglomerate,
        joinableFactory,
        new RetryQueryRunnerConfig(),
        injector.getInstance(ObjectMapper.class),
        serverConfig,
        injector.getInstance(Cache.class),
        injector.getInstance(CacheConfig.class),
        new SubqueryGuardrailHelper(null, JvmUtils.getRuntimeInfo().getMaxHeapSizeBytes(), 1),
        new SubqueryCountStatsProvider(),
        new DefaultGenericQueryMetricsFactory()
    );
  }

  public static TestClusterQuerySegmentWalker createClusterQuerySegmentWalker(
      Map<String, VersionedIntervalTimeline<String, ReferenceCountedSegmentProvider>> timelines,
      QueryRunnerFactoryConglomerate conglomerate,
      @Nullable QueryScheduler scheduler,
      Injector injector
  )
  {
    return new TestClusterQuerySegmentWalker(timelines, conglomerate, scheduler, injector.getInstance(EtagProvider.KEY));
  }

  public static LocalQuerySegmentWalker createLocalQuerySegmentWalker(
      final QueryRunnerFactoryConglomerate conglomerate,
      final SegmentWrangler segmentWrangler,
      final JoinableFactoryWrapper joinableFactoryWrapper,
      final QueryScheduler scheduler,
      final ServiceEmitter emitter
  )
  {
    return new LocalQuerySegmentWalker(
        conglomerate,
        segmentWrangler,
        joinableFactoryWrapper,
        scheduler,
        NoopPolicyEnforcer.instance(),
        emitter
    );
  }

  public static BrokerParallelMergeConfig getParallelMergeConfig(
      boolean useParallelMergePoolConfigured
  )
  {
    return new BrokerParallelMergeConfig() {
      @Override
      public boolean useParallelMergePool()
      {
        return useParallelMergePoolConfigured;
      }
    };
  }
  public static DruidProcessingConfig getProcessingConfig(final int mergeBuffers)
  {
    return new DruidProcessingConfig()
    {
      @Override
      public String getFormatString()
      {
        return null;
      }

      @Override
      public int intermediateComputeSizeBytes()
      {
        return COMPUTE_BUFFER_SIZE;
      }

      @Override
      public int getNumThreads()
      {
        // Only use 1 thread for tests.
        return 1;
      }

      @Override
      public int getNumMergeBuffers()
      {
        if (mergeBuffers == DEFAULT_NUM_MERGE_BUFFERS) {
          return 2;
        }
        return mergeBuffers;
      }
    };
  }

  /**
   * Returns a new {@link QueryRunnerFactoryConglomerate}. Adds relevant closeables to the passed-in {@link Closer}.
   */
  public static QueryRunnerFactoryConglomerate createQueryRunnerFactoryConglomerate(final Closer closer)
  {
    return createQueryRunnerFactoryConglomerate(closer, TopNQueryConfig.DEFAULT_MIN_TOPN_THRESHOLD);
  }

  public static QueryRunnerFactoryConglomerate createQueryRunnerFactoryConglomerate(
      final Closer closer,
      final Integer minTopNThreshold
  )
  {
    return createQueryRunnerFactoryConglomerate(
        closer,
        getProcessingConfig(
            DEFAULT_NUM_MERGE_BUFFERS
        ),
        minTopNThreshold,
        TestHelper.makeJsonMapper()
    );
  }

  public static QueryRunnerFactoryConglomerate createQueryRunnerFactoryConglomerate(
      final Closer closer,
      final DruidProcessingConfig processingConfig
  )
  {
    return createQueryRunnerFactoryConglomerate(
        closer,
        processingConfig,
        TopNQueryConfig.DEFAULT_MIN_TOPN_THRESHOLD,
        TestHelper.makeJsonMapper()
    );
  }

  public static TestBufferPool makeTestBufferPool(final Closer closer)
  {
    final TestBufferPool testBufferPool = TestBufferPool.offHeap(COMPUTE_BUFFER_SIZE, Integer.MAX_VALUE);
    closer.register(() -> {
      // Verify that all objects have been returned to the pool.
      Assert.assertEquals(0, testBufferPool.getOutstandingObjectCount());
    });
    return testBufferPool;
  }

  public static TestGroupByBuffers makeGroupByBuffers(final Closer closer, final DruidProcessingConfig processingConfig)
  {
    final TestGroupByBuffers groupByBuffers =
        closer.register(TestGroupByBuffers.createFromProcessingConfig(processingConfig));
    return groupByBuffers;
  }

  public static QueryRunnerFactoryConglomerate createQueryRunnerFactoryConglomerate(
      final Closer closer,
      final DruidProcessingConfig processingConfig,
      final Integer minTopNThreshold,
      final ObjectMapper jsonMapper
  )
  {
    final TestBufferPool testBufferPool = makeTestBufferPool(closer);
    final TestGroupByBuffers groupByBuffers = makeGroupByBuffers(closer, processingConfig);

    return createQueryRunnerFactoryConglomerate(
        processingConfig,
        minTopNThreshold,
        jsonMapper,
        testBufferPool,
        groupByBuffers);
  }


  public static QueryRunnerFactoryConglomerate createQueryRunnerFactoryConglomerate(
      final DruidProcessingConfig processingConfig,
      final Integer minTopNThreshold,
      final ObjectMapper jsonMapper,
      final TestBufferPool testBufferPool,
      final TestGroupByBuffers groupByBuffers)
  {
    ImmutableMap<Class<? extends Query>, QueryRunnerFactory> factories = makeDefaultQueryRunnerFactories(
        processingConfig,
        minTopNThreshold,
        jsonMapper,
        testBufferPool,
        groupByBuffers
    );
    UnionQueryLogic unionQueryLogic = new UnionQueryLogic();
    final QueryRunnerFactoryConglomerate conglomerate = new DefaultQueryRunnerFactoryConglomerate(
        factories,
        Maps.transformValues(factories, f -> f.getToolchest()),
        ImmutableMap.of(UnionQuery.class, unionQueryLogic)
    );
    unionQueryLogic.initialize(conglomerate);

    return conglomerate;
  }

  @SuppressWarnings("rawtypes")
  public static ImmutableMap<Class<? extends Query>, QueryRunnerFactory> makeDefaultQueryRunnerFactories(
      final DruidProcessingConfig processingConfig,
      final Integer minTopNThreshold,
      final ObjectMapper jsonMapper,
      final TestBufferPool testBufferPool,
      final TestGroupByBuffers groupByBuffers)
  {
    final GroupByQueryRunnerFactory groupByQueryRunnerFactory = GroupByQueryRunnerTest.makeQueryRunnerFactory(
        jsonMapper,
        new GroupByQueryConfig()
        {
        },
        groupByBuffers,
        processingConfig
    );

    return ImmutableMap.<Class<? extends Query>, QueryRunnerFactory>builder()
        .put(
            SegmentMetadataQuery.class,
            new SegmentMetadataQueryRunnerFactory(
                new SegmentMetadataQueryQueryToolChest(
                    new SegmentMetadataQueryConfig("P1W")
                ),
                QueryRunnerTestHelper.NOOP_QUERYWATCHER
            )
        )
        .put(
            SearchQuery.class,
            new SearchQueryRunnerFactory(
                new SearchStrategySelector(Suppliers.ofInstance(new SearchQueryConfig())),
                new SearchQueryQueryToolChest(new SearchQueryConfig()),
                QueryRunnerTestHelper.NOOP_QUERYWATCHER
            )
        )
        .put(
            ScanQuery.class,
            new ScanQueryRunnerFactory(
                new ScanQueryQueryToolChest(DefaultGenericQueryMetricsFactory.instance()),
                new ScanQueryEngine(),
                new ScanQueryConfig()
            )
        )
        .put(
            TimeseriesQuery.class,
            new TimeseriesQueryRunnerFactory(
                new TimeseriesQueryQueryToolChest(),
                new TimeseriesQueryEngine(),
                QueryRunnerTestHelper.NOOP_QUERYWATCHER
            )
        )
        .put(
            TopNQuery.class,
            new TopNQueryRunnerFactory(
                testBufferPool,
                new TopNQueryQueryToolChest(new TopNQueryConfig()
                {
                  @Override
                  public int getMinTopNThreshold()
                  {
                    return minTopNThreshold;
                  }
                }),
                QueryRunnerTestHelper.NOOP_QUERYWATCHER
            )
        )
        .put(GroupByQuery.class, groupByQueryRunnerFactory)
        .put(TimeBoundaryQuery.class, new TimeBoundaryQueryRunnerFactory(QueryRunnerTestHelper.NOOP_QUERYWATCHER))
        .put(
            WindowOperatorQuery.class,
            new WindowOperatorQueryQueryRunnerFactory(
                new WindowOperatorQueryQueryToolChest(DefaultGenericQueryMetricsFactory.instance())
            )
        )
        .build();
  }

  public static JoinableFactory makeJoinableFactoryForLookup(
      LookupExtractorFactoryContainerProvider lookupProvider
  )
  {
    return makeJoinableFactoryFromDefault(lookupProvider, null, null);
  }

  public static JoinableFactory makeJoinableFactoryFromDefault(
      @Nullable LookupExtractorFactoryContainerProvider lookupProvider,
      @Nullable Set<JoinableFactory> customFactories,
      @Nullable Map<Class<? extends JoinableFactory>, Class<? extends DataSource>> customMappings
  )
  {
    ImmutableSet.Builder<JoinableFactory> setBuilder = ImmutableSet.builder();
    ImmutableMap.Builder<Class<? extends JoinableFactory>, Class<? extends DataSource>> mapBuilder =
        ImmutableMap.builder();
    setBuilder.add(new InlineJoinableFactory(), new FrameBasedInlineJoinableFactory());
    mapBuilder.put(InlineJoinableFactory.class, InlineDataSource.class);
    mapBuilder.put(FrameBasedInlineJoinableFactory.class, FrameBasedInlineDataSource.class);
    if (lookupProvider != null) {
      setBuilder.add(new LookupJoinableFactory(lookupProvider));
      mapBuilder.put(LookupJoinableFactory.class, LookupDataSource.class);
    }
    if (customFactories != null) {
      setBuilder.addAll(customFactories);
    }
    if (customMappings != null) {
      mapBuilder.putAll(customMappings);
    }

    return new MapJoinableFactory(setBuilder.build(), mapBuilder.build());
  }

  public static DruidInjectorBuilder defaultInjectorBuilder()
  {
    Injector startupInjector = new StartupInjectorBuilder()
        .build();

    DruidInjectorBuilder injectorBuilder = new CoreInjectorBuilder(startupInjector)
        .ignoreLoadScopes()
        .addModule(new ExpressionModule())
        .addModule(new SegmentWranglerModule())
        .addModule(new CacheTestHelperModule(ResultCacheMode.DISABLED));

    return injectorBuilder;
  }

  public static Injector injectorWithLookup()
  {

    final LookupExtractorFactoryContainerProvider lookupProvider;
    lookupProvider = LookupEnabledTestExprMacroTable.createTestLookupProvider(Collections.emptyMap());

    return defaultInjectorBuilder()
        .addModule(binder -> binder.bind(LookupExtractorFactoryContainerProvider.class).toInstance(lookupProvider))
        .build();
  }
}
