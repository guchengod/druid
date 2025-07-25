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

package org.apache.druid.segment.realtime.appenderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.druid.data.input.Committer;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.indexing.overlord.SegmentPublishResult;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.segment.SegmentSchemaMapping;
import org.apache.druid.segment.handoff.SegmentHandoffNotifier;
import org.apache.druid.segment.handoff.SegmentHandoffNotifierFactory;
import org.apache.druid.segment.loading.DataSegmentKiller;
import org.apache.druid.segment.realtime.SegmentGenerationMetrics;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class StreamAppenderatorDriverTest extends EasyMockSupport
{
  private static final String DATA_SOURCE = "foo";
  private static final String VERSION = "abc123";
  private static final String UPGRADED_VERSION = "xyz456";
  private static final ObjectMapper OBJECT_MAPPER = new DefaultObjectMapper();
  private static final int MAX_ROWS_IN_MEMORY = 100;
  private static final int MAX_ROWS_PER_SEGMENT = 3;
  private static final long PUBLISH_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
  private static final long HANDOFF_CONDITION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(1);

  private static final List<InputRow> ROWS = Arrays.asList(
      new MapBasedInputRow(
          DateTimes.of("2000"),
          ImmutableList.of("dim1"),
          ImmutableMap.of("dim1", "foo", "met1", "1")
      ),
      new MapBasedInputRow(
          DateTimes.of("2000T01"),
          ImmutableList.of("dim1"),
          ImmutableMap.of("dim1", "foo", "met1", 2.0)
      ),
      new MapBasedInputRow(
          DateTimes.of("2000T01"),
          ImmutableList.of("dim2"),
          ImmutableMap.of("dim2", "bar", "met1", 2.0)
      )
  );

  private SegmentAllocator allocator;
  private StreamAppenderatorTester streamAppenderatorTester;
  private TestSegmentHandoffNotifierFactory segmentHandoffNotifierFactory;
  private StreamAppenderatorDriver driver;
  private DataSegmentKiller dataSegmentKiller;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void setUp() throws Exception
  {
    streamAppenderatorTester =
        new StreamAppenderatorTester.Builder()
            .maxRowsInMemory(MAX_ROWS_IN_MEMORY)
            .basePersistDirectory(temporaryFolder.newFolder())
            .build();
    allocator = new TestSegmentAllocator(DATA_SOURCE, Granularities.HOUR);
    segmentHandoffNotifierFactory = new TestSegmentHandoffNotifierFactory();
    dataSegmentKiller = createStrictMock(DataSegmentKiller.class);
    driver = new StreamAppenderatorDriver(
        streamAppenderatorTester.getAppenderator(),
        allocator,
        segmentHandoffNotifierFactory,
        new TestPublishedSegmentRetriever(streamAppenderatorTester.getPushedSegments()),
        dataSegmentKiller,
        OBJECT_MAPPER,
        new SegmentGenerationMetrics()
    );

    EasyMock.replay(dataSegmentKiller);
  }

  @After
  public void tearDown() throws Exception
  {
    EasyMock.verify(dataSegmentKiller);

    driver.clear();
    driver.close();
  }

  @Test(timeout = 60_000L)
  public void testSimple() throws Exception
  {
    final TestCommitterSupplier<Integer> committerSupplier = new TestCommitterSupplier<>();

    Assert.assertNull(driver.startJob(null));

    for (int i = 0; i < ROWS.size(); i++) {
      committerSupplier.setMetadata(i + 1);
      Assert.assertTrue(driver.add(ROWS.get(i), "dummy", committerSupplier, false, true).isOk());
    }

    final SegmentsAndCommitMetadata published = driver.publish(
        makeOkPublisher(),
        committerSupplier.get(),
        ImmutableList.of("dummy")
    ).get(PUBLISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    while (driver.getSegments().containsKey("dummy")) {
      Thread.sleep(100);
    }

    final SegmentsAndCommitMetadata segmentsAndCommitMetadata = driver.registerHandoff(published)
                                                                      .get(HANDOFF_CONDITION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    Assert.assertEquals(
        ImmutableSet.of(
            new SegmentIdWithShardSpec(DATA_SOURCE, Intervals.of("2000/PT1H"), VERSION, new NumberedShardSpec(0, 0)),
            new SegmentIdWithShardSpec(DATA_SOURCE, Intervals.of("2000T01/PT1H"), VERSION, new NumberedShardSpec(0, 0))
        ),
        asIdentifiers(segmentsAndCommitMetadata.getSegments())
    );

    Assert.assertEquals(3, segmentsAndCommitMetadata.getCommitMetadata());
  }

  @Test
  public void testMaxRowsPerSegment() throws Exception
  {
    final int numSegments = 3;
    final TestCommitterSupplier<Integer> committerSupplier = new TestCommitterSupplier<>();
    Assert.assertNull(driver.startJob(null));

    for (int i = 0; i < numSegments * MAX_ROWS_PER_SEGMENT; i++) {
      committerSupplier.setMetadata(i + 1);
      InputRow row = new MapBasedInputRow(
          DateTimes.of("2000T01"),
          ImmutableList.of("dim2"),
          ImmutableMap.of(
              "dim2",
              StringUtils.format("bar-%d", i),
              "met1",
              2.0
          )
      );
      final AppenderatorDriverAddResult addResult = driver.add(row, "dummy", committerSupplier, false, true);
      Assert.assertTrue(addResult.isOk());
      if (addResult.getNumRowsInSegment() > MAX_ROWS_PER_SEGMENT) {
        driver.moveSegmentOut("dummy", ImmutableList.of(addResult.getSegmentIdentifier()));
      }
    }

    final SegmentsAndCommitMetadata published = driver.publish(
        makeOkPublisher(),
        committerSupplier.get(),
        ImmutableList.of("dummy")
    ).get(PUBLISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    while (driver.getSegments().containsKey("dummy")) {
      Thread.sleep(100);
    }

    final SegmentsAndCommitMetadata segmentsAndCommitMetadata = driver.registerHandoff(published)
                                                                      .get(HANDOFF_CONDITION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    Assert.assertEquals(numSegments, segmentsAndCommitMetadata.getSegments().size());
    Assert.assertEquals(numSegments * MAX_ROWS_PER_SEGMENT, segmentsAndCommitMetadata.getCommitMetadata());
  }

  @Test(timeout = 60_000L, expected = TimeoutException.class)
  public void testHandoffTimeout() throws Exception
  {
    final TestCommitterSupplier<Integer> committerSupplier = new TestCommitterSupplier<>();
    segmentHandoffNotifierFactory.disableHandoff();

    Assert.assertNull(driver.startJob(null));

    for (int i = 0; i < ROWS.size(); i++) {
      committerSupplier.setMetadata(i + 1);
      Assert.assertTrue(driver.add(ROWS.get(i), "dummy", committerSupplier, false, true).isOk());
    }

    final SegmentsAndCommitMetadata published = driver.publish(
        makeOkPublisher(),
        committerSupplier.get(),
        ImmutableList.of("dummy")
    ).get(PUBLISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    while (driver.getSegments().containsKey("dummy")) {
      Thread.sleep(100);
    }

    driver.registerHandoff(published).get(HANDOFF_CONDITION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
  }

  @Test
  public void testHandoffUpgradedSegments()
      throws IOException, InterruptedException, TimeoutException, ExecutionException
  {
    final TestCommitterSupplier<Integer> committerSupplier = new TestCommitterSupplier<>();

    Assert.assertNull(driver.startJob(null));

    for (int i = 0; i < ROWS.size(); i++) {
      committerSupplier.setMetadata(i + 1);
      Assert.assertTrue(driver.add(ROWS.get(i), "dummy", committerSupplier, false, true).isOk());
    }

    driver.persist(committerSupplier.get());

    // There is no remaining rows in the driver, and thus the result must be empty
    final SegmentsAndCommitMetadata segmentsAndCommitMetadata = driver.publishAndRegisterHandoff(
        makeUpgradingPublisher(),
        committerSupplier.get(),
        ImmutableList.of("dummy")
    ).get(PUBLISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    Assert.assertNotNull(segmentsAndCommitMetadata.getUpgradedSegments());
    Assert.assertEquals(
        segmentsAndCommitMetadata.getSegments().size(),
        segmentsAndCommitMetadata.getUpgradedSegments().size()
    );

    Set<SegmentDescriptor> expectedHandedOffSegments = new HashSet<>();
    for (DataSegment segment : segmentsAndCommitMetadata.getSegments()) {
      expectedHandedOffSegments.add(segment.toDescriptor());
    }
    for (DataSegment segment : segmentsAndCommitMetadata.getUpgradedSegments()) {
      expectedHandedOffSegments.add(segment.toDescriptor());
    }
    Assert.assertEquals(expectedHandedOffSegments, segmentHandoffNotifierFactory.getHandedOffSegmentDescriptors());
  }

  @Test
  public void testPublishPerRow() throws IOException, InterruptedException, TimeoutException, ExecutionException
  {
    final TestCommitterSupplier<Integer> committerSupplier = new TestCommitterSupplier<>();

    Assert.assertNull(driver.startJob(null));

    // Add the first row and publish immediately
    {
      committerSupplier.setMetadata(1);
      Assert.assertTrue(driver.add(ROWS.get(0), "dummy", committerSupplier, false, true).isOk());

      final SegmentsAndCommitMetadata segmentsAndCommitMetadata = driver.publishAndRegisterHandoff(
          makeOkPublisher(),
          committerSupplier.get(),
          ImmutableList.of("dummy")
      ).get(PUBLISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

      Assert.assertEquals(
          ImmutableSet.of(
              new SegmentIdWithShardSpec(DATA_SOURCE, Intervals.of("2000/PT1H"), VERSION, new NumberedShardSpec(0, 0))
          ),
          asIdentifiers(segmentsAndCommitMetadata.getSegments())
      );

      Assert.assertEquals(1, segmentsAndCommitMetadata.getCommitMetadata());
    }

    // Add the second and third rows and publish immediately
    for (int i = 1; i < ROWS.size(); i++) {
      committerSupplier.setMetadata(i + 1);
      Assert.assertTrue(driver.add(ROWS.get(i), "dummy", committerSupplier, false, true).isOk());

      final SegmentsAndCommitMetadata segmentsAndCommitMetadata = driver.publishAndRegisterHandoff(
          makeOkPublisher(),
          committerSupplier.get(),
          ImmutableList.of("dummy")
      ).get(PUBLISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

      Assert.assertEquals(
          ImmutableSet.of(
              // The second and third rows have the same dataSource, interval, and version, but different shardSpec of
              // different partitionNum
              new SegmentIdWithShardSpec(DATA_SOURCE, Intervals.of("2000T01/PT1H"), VERSION, new NumberedShardSpec(i - 1, 0))
          ),
          asIdentifiers(segmentsAndCommitMetadata.getSegments())
      );

      Assert.assertEquals(i + 1, segmentsAndCommitMetadata.getCommitMetadata());
    }

    driver.persist(committerSupplier.get());

    // There is no remaining rows in the driver, and thus the result must be empty
    final SegmentsAndCommitMetadata segmentsAndCommitMetadata = driver.publishAndRegisterHandoff(
        makeOkPublisher(),
        committerSupplier.get(),
        ImmutableList.of("dummy")
    ).get(PUBLISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

    Assert.assertEquals(
        ImmutableSet.of(),
        asIdentifiers(segmentsAndCommitMetadata.getSegments())
    );

    Assert.assertEquals(3, segmentsAndCommitMetadata.getCommitMetadata());
  }

  @Test
  public void testIncrementalHandoff() throws Exception
  {
    final TestCommitterSupplier<Integer> committerSupplier = new TestCommitterSupplier<>();

    Assert.assertNull(driver.startJob(null));

    committerSupplier.setMetadata(1);
    Assert.assertTrue(driver.add(ROWS.get(0), "sequence_0", committerSupplier, false, true).isOk());

    for (int i = 1; i < ROWS.size(); i++) {
      committerSupplier.setMetadata(i + 1);
      Assert.assertTrue(driver.add(ROWS.get(i), "sequence_1", committerSupplier, false, true).isOk());
    }

    final ListenableFuture<SegmentsAndCommitMetadata> futureForSequence0 = driver.publishAndRegisterHandoff(
        makeOkPublisher(),
        committerSupplier.get(),
        ImmutableList.of("sequence_0")
    );

    final ListenableFuture<SegmentsAndCommitMetadata> futureForSequence1 = driver.publishAndRegisterHandoff(
        makeOkPublisher(),
        committerSupplier.get(),
        ImmutableList.of("sequence_1")
    );

    final SegmentsAndCommitMetadata handedoffFromSequence0 = futureForSequence0.get(
        HANDOFF_CONDITION_TIMEOUT_MILLIS,
        TimeUnit.MILLISECONDS
    );
    final SegmentsAndCommitMetadata handedoffFromSequence1 = futureForSequence1.get(
        HANDOFF_CONDITION_TIMEOUT_MILLIS,
        TimeUnit.MILLISECONDS
    );

    Assert.assertEquals(
        ImmutableSet.of(
            new SegmentIdWithShardSpec(DATA_SOURCE, Intervals.of("2000/PT1H"), VERSION, new NumberedShardSpec(0, 0))
        ),
        asIdentifiers(handedoffFromSequence0.getSegments())
    );

    Assert.assertEquals(
        ImmutableSet.of(
            new SegmentIdWithShardSpec(DATA_SOURCE, Intervals.of("2000T01/PT1H"), VERSION, new NumberedShardSpec(0, 0))
        ),
        asIdentifiers(handedoffFromSequence1.getSegments())
    );

    Assert.assertEquals(3, handedoffFromSequence0.getCommitMetadata());
    Assert.assertEquals(3, handedoffFromSequence1.getCommitMetadata());
  }

  private Set<SegmentIdWithShardSpec> asIdentifiers(Iterable<DataSegment> segments)
  {
    return ImmutableSet.copyOf(Iterables.transform(segments, SegmentIdWithShardSpec::fromDataSegment));
  }

  static TransactionalSegmentPublisher makeOkPublisher()
  {
    return makePublisher(
        (segmentsToPublish) -> SegmentPublishResult.ok(Set.of())
    );
  }

  private TransactionalSegmentPublisher makeUpgradingPublisher()
  {
    return makePublisher((segmentsToPublish) -> {
      Set<DataSegment> allSegments = new HashSet<>(segmentsToPublish);
      int id = 0;
      for (DataSegment segment : segmentsToPublish) {
        DataSegment upgradedSegment = DataSegment.builder(segment)
                                                 .shardSpec(new NumberedShardSpec(id, 0))
                                                 .dataSource(DATA_SOURCE)
                                                 .interval(Intervals.ETERNITY)
                                                 .version(UPGRADED_VERSION)
                                                 .lastCompactionState(null)
                                                 .build();

        id++;
        allSegments.add(upgradedSegment);
      }
      return SegmentPublishResult.ok(allSegments);
    });
  }

  static TransactionalSegmentPublisher makeFailingPublisher(boolean failWithException)
  {
    return makePublisher((segmentsToPublish) -> {
      final RuntimeException exception = new RuntimeException("test");
      if (failWithException) {
        throw exception;
      }
      return SegmentPublishResult.fail(exception.getMessage());
    });
  }

  private static TransactionalSegmentPublisher makePublisher(
      Function<Set<DataSegment>, SegmentPublishResult> publishFunction
  )
  {
    return new TransactionalSegmentPublisher()
    {
      @Override
      public SegmentPublishResult publishAnnotatedSegments(
          @Nullable Set<DataSegment> segmentsToBeOverwritten,
          Set<DataSegment> segmentsToPublish,
          @Nullable Object commitMetadata,
          @Nullable SegmentSchemaMapping segmentSchemaMapping
      )
      {
        return publishFunction.apply(segmentsToPublish);
      }
    };
  }

  static class TestCommitterSupplier<T> implements Supplier<Committer>
  {
    private final AtomicReference<T> metadata = new AtomicReference<>();

    public void setMetadata(T newMetadata)
    {
      metadata.set(newMetadata);
    }

    @Override
    public Committer get()
    {
      final T currentMetadata = metadata.get();
      return new Committer()
      {
        @Override
        public Object getMetadata()
        {
          return currentMetadata;
        }

        @Override
        public void run()
        {
          // Do nothing
        }
      };
    }
  }

  static class TestSegmentAllocator implements SegmentAllocator
  {
    private final String dataSource;
    private final Granularity granularity;
    private final Map<Long, AtomicInteger> counters = new HashMap<>();

    public TestSegmentAllocator(String dataSource, Granularity granularity)
    {
      this.dataSource = dataSource;
      this.granularity = granularity;
    }

    @Override
    public SegmentIdWithShardSpec allocate(
        final InputRow row,
        final String sequenceName,
        final String previousSegmentId,
        final boolean skipSegmentLineageCheck
    )
    {
      synchronized (counters) {
        DateTime dateTimeTruncated = granularity.bucketStart(row.getTimestamp());
        final long timestampTruncated = dateTimeTruncated.getMillis();
        counters.putIfAbsent(timestampTruncated, new AtomicInteger());
        final int partitionNum = counters.get(timestampTruncated).getAndIncrement();
        return new SegmentIdWithShardSpec(
            dataSource,
            granularity.bucket(dateTimeTruncated),
            VERSION,
            new NumberedShardSpec(partitionNum, 0)
        );
      }
    }
  }

  static class TestSegmentHandoffNotifierFactory implements SegmentHandoffNotifierFactory
  {
    private boolean handoffEnabled = true;
    private long handoffDelay;
    private final Set<SegmentDescriptor> handedOffSegmentDescriptors = new HashSet<>();

    public void disableHandoff()
    {
      handoffEnabled = false;
    }

    public void setHandoffDelay(long delay)
    {
      handoffDelay = delay;
    }

    public Set<SegmentDescriptor> getHandedOffSegmentDescriptors()
    {
      synchronized (handedOffSegmentDescriptors) {
        return ImmutableSet.copyOf(handedOffSegmentDescriptors);
      }
    }

    @Override
    public SegmentHandoffNotifier createSegmentHandoffNotifier(String dataSource, String taskId)
    {
      return new SegmentHandoffNotifier()
      {
        @Override
        public boolean registerSegmentHandoffCallback(
            final SegmentDescriptor descriptor,
            final Executor exec,
            final Runnable handOffRunnable
        )
        {
          if (handoffEnabled) {

            if (handoffDelay > 0) {
              try {
                Thread.sleep(handoffDelay);
              }
              catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }

            exec.execute(handOffRunnable);
            synchronized (handedOffSegmentDescriptors) {
              handedOffSegmentDescriptors.add(descriptor);
            }
          }
          return true;
        }

        @Override
        public void start()
        {
          // Do nothing
        }

        @Override
        public void close()
        {
          // Do nothing
        }
      };
    }
  }
}
