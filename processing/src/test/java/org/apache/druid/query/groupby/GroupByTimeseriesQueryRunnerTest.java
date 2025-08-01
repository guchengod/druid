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

package org.apache.druid.query.groupby;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.data.input.MapBasedRow;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.HumanReadableBytes;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.PeriodGranularity;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.query.Druids;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.Result;
import org.apache.druid.query.aggregation.DoubleMaxAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleMinAggregatorFactory;
import org.apache.druid.query.aggregation.ExpressionLambdaAggregatorFactory;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.expression.TestExprMacroTable;
import org.apache.druid.query.filter.NotDimFilter;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.timeseries.TimeseriesQueryRunnerTest;
import org.apache.druid.query.timeseries.TimeseriesResultValue;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.VirtualColumn;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;
import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is for testing both timeseries and groupBy queries with the same set of queries.
 */
@RunWith(Parameterized.class)
public class GroupByTimeseriesQueryRunnerTest extends TimeseriesQueryRunnerTest
{
  private static TestGroupByBuffers BUFFER_POOLS = null;

  @BeforeClass
  public static void setUpClass()
  {
    BUFFER_POOLS = TestGroupByBuffers.createDefault();
  }

  @AfterClass
  public static void tearDownClass()
  {
    BUFFER_POOLS.close();
    BUFFER_POOLS = null;
  }

  @SuppressWarnings("unchecked")
  @Parameterized.Parameters(name = "{0}, vectorize = {1}")
  public static Iterable<Object[]> constructorFeeder()
  {
    setUpClass();
    GroupByQueryConfig config = new GroupByQueryConfig();
    final GroupByQueryRunnerFactory factory = GroupByQueryRunnerTest.makeQueryRunnerFactory(config, BUFFER_POOLS);

    final List<Object[]> constructors = new ArrayList<>();

    for (QueryRunner<ResultRow> runner : QueryRunnerTestHelper.makeQueryRunnersToMerge(factory, false)) {
      final QueryRunner modifiedRunner = new QueryRunner()
      {
        @Override
        public Sequence run(QueryPlus queryPlus, ResponseContext responseContext)
        {
          queryPlus = GroupByQueryRunnerTestHelper.populateResourceId(queryPlus);
          final ObjectMapper jsonMapper = TestHelper.makeJsonMapper();
          TimeseriesQuery tsQuery = (TimeseriesQuery) queryPlus.getQuery();

          final String timeDimension = tsQuery.getTimestampResultField();
          final List<VirtualColumn> virtualColumns = new ArrayList<>(
              Arrays.asList(tsQuery.getVirtualColumns().getVirtualColumns())
          );
          Map<String, Object> theContext = tsQuery.getContext();
          if (timeDimension != null) {
            theContext = new HashMap<>(tsQuery.getContext());
            final PeriodGranularity granularity = (PeriodGranularity) tsQuery.getGranularity();
            virtualColumns.add(
                new ExpressionVirtualColumn(
                    "v0",
                    StringUtils.format("timestamp_floor(__time, '%s')", granularity.getPeriod()),
                    ColumnType.LONG,
                    TestExprMacroTable.INSTANCE
                )
            );

            theContext.put(GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD, timeDimension);
            try {
              theContext.put(
                  GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD_GRANULARITY,
                  jsonMapper.writeValueAsString(granularity)
              );
            }
            catch (JsonProcessingException e) {
              throw new RuntimeException(e);
            }
            theContext.put(GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD_INDEX, 0);
          }

          GroupByQuery newQuery = GroupByQuery
              .builder()
              .setDataSource(tsQuery.getDataSource())
              .setQuerySegmentSpec(tsQuery.getQuerySegmentSpec())
              .setGranularity(tsQuery.getGranularity())
              .setDimFilter(tsQuery.getDimensionsFilter())
              .setDimensions(
                  timeDimension == null
                  ? ImmutableList.of()
                  : ImmutableList.of(new DefaultDimensionSpec("v0", timeDimension, ColumnType.LONG))
              )
              .setAggregatorSpecs(tsQuery.getAggregatorSpecs())
              .setPostAggregatorSpecs(tsQuery.getPostAggregatorSpecs())
              .setVirtualColumns(VirtualColumns.create(virtualColumns))
              .setLimit(tsQuery.getLimit())
              .setContext(theContext)
              .build();

          return Sequences.map(
              runner.run(queryPlus.withQuery(newQuery), responseContext),
              new Function<ResultRow, Result<TimeseriesResultValue>>()
              {
                @Override
                public Result<TimeseriesResultValue> apply(final ResultRow input)
                {
                  final MapBasedRow mapBasedRow = input.toMapBasedRow(newQuery);

                  return new Result<>(
                      mapBasedRow.getTimestamp(),
                      new TimeseriesResultValue(mapBasedRow.getEvent())
                  );
                }
              }
          );
        }

        @Override
        public String toString()
        {
          return runner.toString();
        }
      };

      for (boolean vectorize : ImmutableList.of(false, true)) {
        // Add vectorization tests for any indexes that support it.
        if (!vectorize || QueryRunnerTestHelper.isTestRunnerVectorizable(runner)) {
          constructors.add(new Object[]{modifiedRunner, vectorize});
        }
      }
    }

    return constructors;
  }

  public GroupByTimeseriesQueryRunnerTest(QueryRunner runner, boolean vectorize)
  {
    super(runner, false, vectorize, QueryRunnerTestHelper.COMMON_DOUBLE_AGGREGATORS);
  }

  // GroupBy handles timestamps differently when granularity is ALL
  @Override
  @Test
  public void testFullOnTimeseriesMaxMin()
  {
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
                                  .granularity(Granularities.ALL)
                                  .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
                                  .aggregators(
                                      new DoubleMaxAggregatorFactory("maxIndex", "index"),
                                      new DoubleMinAggregatorFactory("minIndex", "index")
                                  )
                                  .descending(descending)
                                  .build();

    DateTime expectedEarliest = DateTimes.of("1970-01-01");
    DateTime expectedLast = DateTimes.of("2011-04-15");

    Iterable<Result<TimeseriesResultValue>> results = runner.run(QueryPlus.wrap(query)).toList();
    Result<TimeseriesResultValue> result = results.iterator().next();

    Assert.assertEquals(expectedEarliest, result.getTimestamp());
    Assert.assertFalse(
        StringUtils.format("Timestamp[%s] > expectedLast[%s]", result.getTimestamp(), expectedLast),
        result.getTimestamp().isAfter(expectedLast)
    );

    final TimeseriesResultValue value = result.getValue();

    Assert.assertEquals(result.toString(), 1870.061029, value.getDoubleMetric("maxIndex"), 1870.061029 * 1e-6);
    Assert.assertEquals(result.toString(), 59.021022, value.getDoubleMetric("minIndex"), 59.021022 * 1e-6);
  }

  // GroupBy handles timestamps differently when granularity is ALL
  @Override
  @Test
  public void testFullOnTimeseriesMinMaxAggregators()
  {
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
                                  .granularity(Granularities.ALL)
                                  .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
                                  .aggregators(
                                      QueryRunnerTestHelper.INDEX_LONG_MIN,
                                      QueryRunnerTestHelper.INDEX_LONG_MAX,
                                      QueryRunnerTestHelper.INDEX_DOUBLE_MIN,
                                      QueryRunnerTestHelper.INDEX_DOUBLE_MAX,
                                      QueryRunnerTestHelper.INDEX_FLOAT_MIN,
                                      QueryRunnerTestHelper.INDEX_FLOAT_MAX
                                  )
                                  .descending(descending)
                                  .build();

    DateTime expectedEarliest = DateTimes.of("1970-01-01");
    DateTime expectedLast = DateTimes.of("2011-04-15");


    Iterable<Result<TimeseriesResultValue>> results = runner.run(QueryPlus.wrap(query)).toList();
    Result<TimeseriesResultValue> result = results.iterator().next();

    Assert.assertEquals(expectedEarliest, result.getTimestamp());
    Assert.assertFalse(
        StringUtils.format("Timestamp[%s] > expectedLast[%s]", result.getTimestamp(), expectedLast),
        result.getTimestamp().isAfter(expectedLast)
    );
    Assert.assertEquals(59L, (long) result.getValue().getLongMetric(QueryRunnerTestHelper.LONG_MIN_INDEX_METRIC));
    Assert.assertEquals(1870, (long) result.getValue().getLongMetric(QueryRunnerTestHelper.LONG_MAX_INDEX_METRIC));
    Assert.assertEquals(
        59.021022D,
        result.getValue().getDoubleMetric(QueryRunnerTestHelper.DOUBLE_MIN_INDEX_METRIC),
        0
    );
    Assert.assertEquals(
        1870.061029D,
        result.getValue().getDoubleMetric(QueryRunnerTestHelper.DOUBLE_MAX_INDEX_METRIC),
        0
    );
    Assert.assertEquals(59.021023F, result.getValue().getFloatMetric(QueryRunnerTestHelper.FLOAT_MIN_INDEX_METRIC), 0);
    Assert.assertEquals(1870.061F, result.getValue().getFloatMetric(QueryRunnerTestHelper.FLOAT_MAX_INDEX_METRIC), 0);
  }

  @Override
  public void testTimeseriesNullableLongMax()
  {
    // Skip this test because the timeseries test expects a null value to be returned for the aggregator even if
    // there's no data for a period in query, but group by doesn't return data for periods that have no data.
  }

  @Override
  public void testTimeseriesProjections()
  {
    // Skip this test because the timeseries test expects a null value to be returned for the aggregator even if
    // there's no data for a period in query, but group by doesn't return data for periods that have no data.
  }

  @Override
  public void testTimeseriesProjectionsCounts()
  {
    // Skip this test because the timeseries test expects a 0 value to be returned for the count aggregator even if
    // there's no data for a period in query, but group by doesn't return data for periods that have no data.
  }

  @Override
  public void testEmptyTimeseries()
  {
    // Skip this test because the timeseries test expects the empty range to have one entry, but group by
    // does not expect anything
  }

  @Override
  public void testFullOnTimeseries()
  {
    // Skip this test because the timeseries test expects a skipped day to be filled in, but group by doesn't
    // fill anything in.
  }

  @Override
  public void testFullOnTimeseriesWithFilter()
  {
    // Skip this test because the timeseries test expects a skipped day to be filled in, but group by doesn't
    // fill anything in.
  }

  @Override
  public void testTimeseriesQueryZeroFilling()
  {
    // Skip this test because the timeseries test expects skipped hours to be filled in, but group by doesn't
    // fill anything in.
  }

  @Override
  public void testTimeseriesWithNonExistentFilter()
  {
    // Skip this test because the timeseries test expects a day that doesn't have a filter match to be filled in,
    // but group by just doesn't return a value if the filter doesn't match.
  }

  @Override
  public void testTimeseriesWithNonExistentFilterAndMultiDim()
  {
    // Skip this test because the timeseries test expects a day that doesn't have a filter match to be filled in,
    // but group by just doesn't return a value if the filter doesn't match.
  }

  @Override
  public void testTimeseriesWithFilterOnNonExistentDimension()
  {
    // Skip this test because the timeseries test expects a day that doesn't have a filter match to be filled in,
    // but group by just doesn't return a value if the filter doesn't match.
  }

  @Override
  public void testTimeseriesWithExpressionAggregatorTooBig()
  {
    cannotVectorize();
    if (!vectorize) {
      // size bytes when it overshoots varies slightly between algorithms
      expectedException.expectMessage("Unable to serialize [ARRAY<STRING>]");
    }
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
                                  .granularity(Granularities.DAY)
                                  .intervals(QueryRunnerTestHelper.FIRST_TO_THIRD)
                                  .aggregators(
                                      Collections.singletonList(
                                          new ExpressionLambdaAggregatorFactory(
                                              "array_agg_distinct",
                                              ImmutableSet.of(QueryRunnerTestHelper.MARKET_DIMENSION),
                                              "acc",
                                              "[]",
                                              null,
                                              null,
                                              true,
                                              false,
                                              "array_set_add(acc, market)",
                                              "array_set_add_all(acc, array_agg_distinct)",
                                              null,
                                              null,
                                              HumanReadableBytes.valueOf(10),
                                              TestExprMacroTable.INSTANCE
                                          )
                                      )
                                  )
                                  .descending(descending)
                                  .context(makeContext())
                                  .build();

    runner.run(QueryPlus.wrap(query)).toList();
  }

  @Override
  @Test
  public void testTimeseriesWithInvertedFilterOnNonExistentDimension()
  {
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
                                  .granularity(QueryRunnerTestHelper.DAY_GRAN)
                                  .filters(new NotDimFilter(new SelectorDimFilter("bobby", "sally", null)))
                                  .intervals(QueryRunnerTestHelper.FIRST_TO_THIRD)
                                  .aggregators(aggregatorFactoryList)
                                  .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
                                  .descending(descending)
                                  .context(makeContext())
                                  .build();


    Iterable<Result<TimeseriesResultValue>> results = runner.run(QueryPlus.wrap(query))
                                                            .toList();
    // group by query results are empty instead of day bucket results with zeros and nulls
    Assert.assertEquals(Collections.emptyList(), results);
  }
}
