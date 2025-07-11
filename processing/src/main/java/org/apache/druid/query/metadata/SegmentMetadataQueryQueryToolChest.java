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

package org.apache.druid.query.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import org.apache.druid.common.guava.CombiningSequence;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.JodaUtils;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.java.util.common.guava.MappedSequence;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.query.BySegmentSkippingQueryRunner;
import org.apache.druid.query.CacheStrategy;
import org.apache.druid.query.DefaultGenericQueryMetricsFactory;
import org.apache.druid.query.GenericQueryMetricsFactory;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.AggregatorFactoryNotMergeableException;
import org.apache.druid.query.aggregation.MetricManipulationFn;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.metadata.metadata.AggregatorMergeStrategy;
import org.apache.druid.query.metadata.metadata.ColumnAnalysis;
import org.apache.druid.query.metadata.metadata.SegmentAnalysis;
import org.apache.druid.query.metadata.metadata.SegmentMetadataQuery;
import org.apache.druid.segment.AggregateProjectionMetadata;
import org.apache.druid.timeline.LogicalSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.utils.CollectionUtils;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;

public class SegmentMetadataQueryQueryToolChest extends QueryToolChest<SegmentAnalysis, SegmentMetadataQuery>
{
  private static final TypeReference<SegmentAnalysis> TYPE_REFERENCE = new TypeReference<>() {};
  private static final byte SEGMENT_METADATA_CACHE_PREFIX = 0x4;
  private static final byte SEGMENT_METADATA_QUERY = 0x16;
  private static final Function<SegmentAnalysis, SegmentAnalysis> MERGE_TRANSFORM_FN =
      SegmentMetadataQueryQueryToolChest::finalizeAnalysis;

  private final SegmentMetadataQueryConfig config;
  private final GenericQueryMetricsFactory queryMetricsFactory;

  @VisibleForTesting
  public SegmentMetadataQueryQueryToolChest(SegmentMetadataQueryConfig config)
  {
    this(config, DefaultGenericQueryMetricsFactory.instance());
  }

  @Inject
  public SegmentMetadataQueryQueryToolChest(
      SegmentMetadataQueryConfig config,
      GenericQueryMetricsFactory queryMetricsFactory
  )
  {
    this.config = config;
    this.queryMetricsFactory = queryMetricsFactory;
  }

  @Override
  public QueryRunner<SegmentAnalysis> mergeResults(final QueryRunner<SegmentAnalysis> runner)
  {
    return new BySegmentSkippingQueryRunner<>(runner)
    {
      @Override
      public Sequence<SegmentAnalysis> doRun(
          QueryRunner<SegmentAnalysis> baseRunner,
          QueryPlus<SegmentAnalysis> queryPlus,
          ResponseContext context
      )
      {
        SegmentMetadataQuery updatedQuery = ((SegmentMetadataQuery) queryPlus.getQuery()).withFinalizedAnalysisTypes(
            config);
        QueryPlus<SegmentAnalysis> updatedQueryPlus = queryPlus.withQuery(updatedQuery);
        return new MappedSequence<>(
            CombiningSequence.create(
                baseRunner.run(updatedQueryPlus, context),
                makeOrdering(updatedQuery),
                createMergeFn(updatedQuery)
            ),
            MERGE_TRANSFORM_FN::apply
        );
      }

      private Ordering<SegmentAnalysis> makeOrdering(SegmentMetadataQuery query)
      {
        return (Ordering<SegmentAnalysis>) SegmentMetadataQueryQueryToolChest.this.createResultComparator(query);
      }

      private BinaryOperator<SegmentAnalysis> createMergeFn(final SegmentMetadataQuery inQ)
      {
        return SegmentMetadataQueryQueryToolChest.this.createMergeFn(inQ);
      }
    };
  }

  @Override
  public BinaryOperator<SegmentAnalysis> createMergeFn(Query<SegmentAnalysis> query)
  {
    return (arg1, arg2) -> mergeAnalyses(
        query.getDataSource().getTableNames(),
        arg1,
        arg2,
        ((SegmentMetadataQuery) query).getAggregatorMergeStrategy()
    );
  }

  @Override
  public Comparator<SegmentAnalysis> createResultComparator(Query<SegmentAnalysis> query)
  {
    SegmentMetadataQuery segmentMetadataQuery = (SegmentMetadataQuery) query;
    if (segmentMetadataQuery.isMerge()) {
      // Merge everything always
      return Comparators.alwaysEqual();
    }

    return segmentMetadataQuery.getResultOrdering(); // No two elements should be equal, so it should never merge
  }

  @Override
  public QueryMetrics<Query<?>> makeMetrics(SegmentMetadataQuery query)
  {
    return queryMetricsFactory.makeMetrics(query);
  }

  @Override
  public Function<SegmentAnalysis, SegmentAnalysis> makePreComputeManipulatorFn(
      SegmentMetadataQuery query,
      MetricManipulationFn fn
  )
  {
    return Functions.identity();
  }

  @Override
  public TypeReference<SegmentAnalysis> getResultTypeReference()
  {
    return TYPE_REFERENCE;
  }

  @Override
  public CacheStrategy<SegmentAnalysis, SegmentAnalysis, SegmentMetadataQuery> getCacheStrategy(final SegmentMetadataQuery query)
  {
    return getCacheStrategy(query, null);
  }

  @Override
  public CacheStrategy<SegmentAnalysis, SegmentAnalysis, SegmentMetadataQuery> getCacheStrategy(
      final SegmentMetadataQuery query,
      @Nullable final ObjectMapper objectMapper
  )
  {
    return new CacheStrategy<>()
    {
      @Override
      public boolean isCacheable(SegmentMetadataQuery query, boolean willMergeRunners, boolean bySegment)
      {
        return true;
      }

      @Override
      public byte[] computeCacheKey(SegmentMetadataQuery query)
      {
        SegmentMetadataQuery updatedQuery = query.withFinalizedAnalysisTypes(config);
        return new CacheKeyBuilder(SEGMENT_METADATA_CACHE_PREFIX).appendCacheable(updatedQuery.getToInclude())
                                                                 .appendCacheables(updatedQuery.getAnalysisTypes())
                                                                 .build();
      }

      @Override
      public byte[] computeResultLevelCacheKey(SegmentMetadataQuery query)
      {
        // need to include query "merge" and "lenientAggregatorMerge" for result level cache key
        return new CacheKeyBuilder(SEGMENT_METADATA_QUERY).appendByteArray(computeCacheKey(query))
                                                          .appendBoolean(query.isMerge())
                                                          .build();
      }

      @Override
      public TypeReference<SegmentAnalysis> getCacheObjectClazz()
      {
        return getResultTypeReference();
      }

      @Override
      public Function<SegmentAnalysis, SegmentAnalysis> prepareForCache(boolean isResultLevelCache)
      {
        return input -> input;
      }

      @Override
      public Function<SegmentAnalysis, SegmentAnalysis> pullFromCache(boolean isResultLevelCache)
      {
        return input -> input;
      }
    };
  }

  @Override
  public <T extends LogicalSegment> List<T> filterSegments(SegmentMetadataQuery query, List<T> segments)
  {
    if (!query.isUsingDefaultInterval()) {
      return segments;
    }
    if (segments.size() <= 1) {
      return segments;
    }

    final T max = segments.get(segments.size() - 1);

    DateTime targetEnd = max.getInterval().getEnd();
    final Interval targetInterval = new Interval(config.getDefaultHistory(), targetEnd);

    return Lists.newArrayList(
        Iterables.filter(
            segments,
            input -> (input.getInterval().overlaps(targetInterval))
        )
    );
  }

  @VisibleForTesting
  public static SegmentAnalysis mergeAnalyses(
      Set<String> dataSources,
      SegmentAnalysis arg1,
      SegmentAnalysis arg2,
      AggregatorMergeStrategy aggregatorMergeStrategy
  )
  {
    if (arg1 == null) {
      return arg2;
    }

    if (arg2 == null) {
      return arg1;
    }

    // This is a defensive check since SegementMetadata query instantiation guarantees this
    if (CollectionUtils.isNullOrEmpty(dataSources)) {
      throw DruidException.defensive("SegementMetadata queries require at least one datasource.");
    }

    SegmentId mergedSegmentId = null;

    // Union datasources can have multiple datasources. So we iterate through all the datasources to parse the segment id.
    for (String dataSource : dataSources) {
      final SegmentId id1 = SegmentId.tryParse(dataSource, arg1.getId());
      final SegmentId id2 = SegmentId.tryParse(dataSource, arg2.getId());

      // Swap arg1, arg2 so the later-ending interval is first. This ensures we prefer the latest column order.
      // We're preserving it so callers can see columns in their natural order.
      if (id1 != null && id2 != null) {
        if (id2.getIntervalEnd().isAfter(id1.getIntervalEnd()) ||
            (id2.getIntervalEnd().isEqual(id1.getIntervalEnd()) && id2.getPartitionNum() > id1.getPartitionNum())) {
          mergedSegmentId = SegmentId.merged(dataSource, id2.getInterval(), id2.getPartitionNum());
          final SegmentAnalysis tmp = arg1;
          arg1 = arg2;
          arg2 = tmp;
        } else {
          mergedSegmentId = SegmentId.merged(dataSource, id1.getInterval(), id1.getPartitionNum());
        }
        break;
      }
    }

    List<Interval> newIntervals = null;
    if (arg1.getIntervals() != null) {
      newIntervals = new ArrayList<>(arg1.getIntervals());
    }
    if (arg2.getIntervals() != null) {
      if (newIntervals == null) {
        newIntervals = new ArrayList<>();
      }
      newIntervals.addAll(arg2.getIntervals());
    }

    final Map<String, ColumnAnalysis> leftColumns = arg1.getColumns();
    final Map<String, ColumnAnalysis> rightColumns = arg2.getColumns();
    final LinkedHashMap<String, ColumnAnalysis> columns = new LinkedHashMap<>();

    Set<String> rightColumnNames = Sets.newHashSet(rightColumns.keySet());
    for (Map.Entry<String, ColumnAnalysis> entry : leftColumns.entrySet()) {
      final String columnName = entry.getKey();
      columns.put(columnName, entry.getValue().fold(rightColumns.get(columnName)));
      rightColumnNames.remove(columnName);
    }

    for (String columnName : rightColumnNames) {
      columns.put(columnName, rightColumns.get(columnName));
    }

    final Map<String, AggregatorFactory> aggregators = new HashMap<>();

    if (AggregatorMergeStrategy.LENIENT == aggregatorMergeStrategy) {
      // Merge each aggregator individually, ignoring nulls
      for (SegmentAnalysis analysis : ImmutableList.of(arg1, arg2)) {
        if (analysis.getAggregators() != null) {
          for (Map.Entry<String, AggregatorFactory> entry : analysis.getAggregators().entrySet()) {
            final String aggregatorName = entry.getKey();
            final AggregatorFactory aggregator = entry.getValue();
            final boolean isMergedYet = aggregators.containsKey(aggregatorName);
            AggregatorFactory merged;

            if (!isMergedYet) {
              merged = aggregator;
            } else {
              merged = aggregators.get(aggregatorName);

              if (merged != null && aggregator != null) {
                try {
                  merged = merged.getMergingFactory(aggregator);
                }
                catch (AggregatorFactoryNotMergeableException e) {
                  merged = null;
                }
              } else {
                merged = null;
              }
            }

            aggregators.put(aggregatorName, merged);
          }
        }
      }
    } else if (AggregatorMergeStrategy.STRICT == aggregatorMergeStrategy) {
      final AggregatorFactory[] aggs1 = arg1.getAggregators() != null
                                        ? arg1.getAggregators()
                                              .values()
                                              .toArray(new AggregatorFactory[0])
                                        : null;
      final AggregatorFactory[] aggs2 = arg2.getAggregators() != null
                                        ? arg2.getAggregators()
                                              .values()
                                              .toArray(new AggregatorFactory[0])
                                        : null;
      final AggregatorFactory[] merged = AggregatorFactory.mergeAggregators(Arrays.asList(aggs1, aggs2));
      if (merged != null) {
        for (AggregatorFactory aggregator : merged) {
          aggregators.put(aggregator.getName(), aggregator);
        }
      }
    } else if (AggregatorMergeStrategy.EARLIEST == aggregatorMergeStrategy) {
      // The segment analyses are already ordered above, where arg1 is the analysis pertaining to the latest interval
      // followed by arg2. So for earliest strategy, the iteration order should be arg2 and arg1.
      for (SegmentAnalysis analysis : ImmutableList.of(arg2, arg1)) {
        if (analysis.getAggregators() != null) {
          for (Map.Entry<String, AggregatorFactory> entry : analysis.getAggregators().entrySet()) {
            aggregators.putIfAbsent(entry.getKey(), entry.getValue());
          }
        }
      }
    } else if (AggregatorMergeStrategy.LATEST == aggregatorMergeStrategy) {
      // The segment analyses are already ordered above, where arg1 is the analysis pertaining to the latest interval
      // followed by arg2. So for latest strategy, the iteration order should be arg1 and arg2.
      for (SegmentAnalysis analysis : ImmutableList.of(arg1, arg2)) {
        if (analysis.getAggregators() != null) {
          for (Map.Entry<String, AggregatorFactory> entry : analysis.getAggregators().entrySet()) {
            aggregators.putIfAbsent(entry.getKey(), entry.getValue());
          }
        }
      }
    } else {
      throw DruidException.defensive("[%s] merge strategy is not implemented.", aggregatorMergeStrategy);
    }

    final TimestampSpec timestampSpec = TimestampSpec.mergeTimestampSpec(
        Lists.newArrayList(
            arg1.getTimestampSpec(),
            arg2.getTimestampSpec()
        )
    );

    final Granularity queryGranularity = Granularity.mergeGranularities(
        Lists.newArrayList(
            arg1.getQueryGranularity(),
            arg2.getQueryGranularity()
        )
    );

    final String mergedId;

    if (arg1.getId() != null && arg2.getId() != null && arg1.getId().equals(arg2.getId())) {
      mergedId = arg1.getId();
    } else {
      mergedId = mergedSegmentId == null ? "merged" : mergedSegmentId.toString();
    }

    final Boolean rollup;

    if (arg1.isRollup() != null && arg2.isRollup() != null && arg1.isRollup().equals(arg2.isRollup())) {
      rollup = arg1.isRollup();
    } else {
      rollup = null;
    }

    final Map<String, AggregateProjectionMetadata> projections;
    if (arg1.getProjections() != null && arg2.getProjections() != null) {
      projections = new HashMap<>();
      // Merge two maps of AggregateProjectionMetadata, returning a new map with the same keys and merged metadata.
      // If the schemas do not match, the metadata is not merged and the key is not included in the result.
      for (String name : Sets.intersection(arg1.getProjections().keySet(), arg2.getProjections().keySet())) {
        AggregateProjectionMetadata spec1 = arg1.getProjections().get(name);
        AggregateProjectionMetadata spec2 = arg2.getProjections().get(name);
        if (spec1.getSchema().equals(spec2.getSchema())) {
          // If the schemas are equal, we can merge the metadata
          projections.put(
              name,
              new AggregateProjectionMetadata(spec1.getSchema(), spec1.getNumRows() + spec2.getNumRows())
          );
        }
      }
    } else {
      projections = null;
    }

    return new SegmentAnalysis(
        mergedId,
        newIntervals,
        columns,
        arg1.getSize() + arg2.getSize(),
        arg1.getNumRows() + arg2.getNumRows(),
        aggregators.isEmpty() ? null : aggregators,
        (projections == null || projections.isEmpty()) ? null : projections,
        timestampSpec,
        queryGranularity,
        rollup
    );
  }

  @VisibleForTesting
  public static SegmentAnalysis finalizeAnalysis(SegmentAnalysis analysis)
  {
    return new SegmentAnalysis(
        analysis.getId(),
        analysis.getIntervals() != null ? JodaUtils.condenseIntervals(analysis.getIntervals()) : null,
        analysis.getColumns(),
        analysis.getSize(),
        analysis.getNumRows(),
        analysis.getAggregators(),
        analysis.getProjections(),
        analysis.getTimestampSpec(),
        analysis.getQueryGranularity(),
        analysis.isRollup()
    );
  }

  public SegmentMetadataQueryConfig getConfig()
  {
    return this.config;
  }
}
