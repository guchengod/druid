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

package org.apache.druid.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import org.apache.druid.common.utils.IdUtils;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.error.DruidExceptionMatcher;
import org.apache.druid.error.ExceptionMatcher;
import org.apache.druid.indexing.overlord.DataSourceMetadata;
import org.apache.druid.indexing.overlord.ObjectMetadata;
import org.apache.druid.indexing.overlord.SegmentCreateRequest;
import org.apache.druid.indexing.overlord.SegmentPublishResult;
import org.apache.druid.indexing.overlord.Segments;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.metrics.StubServiceEmitter;
import org.apache.druid.metadata.segment.SegmentMetadataTransaction;
import org.apache.druid.metadata.segment.SqlSegmentMetadataTransactionFactory;
import org.apache.druid.metadata.segment.cache.HeapMemorySegmentMetadataCache;
import org.apache.druid.metadata.segment.cache.Metric;
import org.apache.druid.metadata.segment.cache.SegmentMetadataCache;
import org.apache.druid.segment.SegmentSchemaMapping;
import org.apache.druid.segment.TestDataSource;
import org.apache.druid.segment.metadata.CentralizedDatasourceSchemaConfig;
import org.apache.druid.segment.metadata.FingerprintGenerator;
import org.apache.druid.segment.metadata.NoopSegmentSchemaCache;
import org.apache.druid.segment.metadata.SegmentSchemaManager;
import org.apache.druid.segment.metadata.SegmentSchemaTestUtils;
import org.apache.druid.segment.realtime.appenderator.SegmentIdWithShardSpec;
import org.apache.druid.server.coordinator.CreateDataSegments;
import org.apache.druid.server.coordinator.simulate.BlockingExecutorService;
import org.apache.druid.server.coordinator.simulate.TestDruidLeaderSelector;
import org.apache.druid.server.coordinator.simulate.WrappingScheduledExecutorService;
import org.apache.druid.server.http.DataSegmentPlus;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.SegmentTimeline;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.HashBasedNumberedPartialShardSpec;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.apache.druid.timeline.partition.NumberedOverwritePartialShardSpec;
import org.apache.druid.timeline.partition.NumberedOverwriteShardSpec;
import org.apache.druid.timeline.partition.NumberedPartialShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartialShardSpec;
import org.apache.druid.timeline.partition.PartitionIds;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.apache.druid.timeline.partition.TombstoneShardSpec;
import org.assertj.core.api.Assertions;
import org.hamcrest.MatcherAssert;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.skife.jdbi.v2.exceptions.CallbackFailedException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public class IndexerSQLMetadataStorageCoordinatorTest extends IndexerSqlMetadataStorageCoordinatorTestBase
{
  private static final String SUPERVISOR_ID = "supervisor";
  @Rule
  public final TestDerbyConnector.DerbyConnectorRule derbyConnectorRule = new TestDerbyConnector.DerbyConnectorRule();

  private TestDruidLeaderSelector leaderSelector;
  private SegmentMetadataCache segmentMetadataCache;
  private StubServiceEmitter emitter;
  private SqlSegmentMetadataTransactionFactory transactionFactory;
  private BlockingExecutorService cachePollExecutor;

  private final SegmentMetadataCache.UsageMode cacheMode;

  @Parameterized.Parameters(name = "cacheMode = {0}")
  public static Object[][] testParameters()
  {
    return new Object[][]{
        {SegmentMetadataCache.UsageMode.ALWAYS},
        {SegmentMetadataCache.UsageMode.NEVER},
        {SegmentMetadataCache.UsageMode.IF_SYNCED}
    };
  }

  public IndexerSQLMetadataStorageCoordinatorTest(SegmentMetadataCache.UsageMode cacheMode)
  {
    this.cacheMode = cacheMode;
  }

  @Before
  public void setUp()
  {
    derbyConnector = derbyConnectorRule.getConnector();
    segmentsTable = derbyConnectorRule.segments();
    mapper.registerSubtypes(LinearShardSpec.class, NumberedShardSpec.class, HashBasedNumberedShardSpec.class);
    derbyConnector.createDataSourceTable();
    derbyConnector.createTaskTables();
    derbyConnector.createSegmentTable();
    derbyConnector.createUpgradeSegmentsTable();
    derbyConnector.createPendingSegmentsTable();
    metadataUpdateCounter.set(0);
    segmentTableDropUpdateCounter.set(0);

    fingerprintGenerator = new FingerprintGenerator(mapper);
    segmentSchemaManager = new SegmentSchemaManager(derbyConnectorRule.metadataTablesConfigSupplier().get(), mapper, derbyConnector);
    segmentSchemaTestUtils = new SegmentSchemaTestUtils(derbyConnectorRule, derbyConnector, mapper);

    emitter = new StubServiceEmitter();
    leaderSelector = new TestDruidLeaderSelector();

    cachePollExecutor = new BlockingExecutorService("test-cache-poll-exec");

    segmentMetadataCache = new HeapMemorySegmentMetadataCache(
        mapper,
        () -> new SegmentsMetadataManagerConfig(null, cacheMode, null),
        derbyConnectorRule.metadataTablesConfigSupplier(),
        new NoopSegmentSchemaCache(),
        derbyConnector,
        (corePoolSize, nameFormat) -> new WrappingScheduledExecutorService(
            nameFormat,
            cachePollExecutor,
            false
        ),
        emitter
    );

    leaderSelector.becomeLeader();

    // Get the cache ready if required
    if (isCacheEnabled()) {
      segmentMetadataCache.start();
      segmentMetadataCache.becomeLeader();
      refreshCache();
      refreshCache();
    }

    transactionFactory = new SqlSegmentMetadataTransactionFactory(
        mapper,
        derbyConnectorRule.metadataTablesConfigSupplier().get(),
        derbyConnector,
        leaderSelector,
        segmentMetadataCache,
        emitter
    )
    {
      @Override
      public int getMaxRetries()
      {
        return MAX_SQL_MEATADATA_RETRY_FOR_TEST;
      }
    };
    coordinator = new IndexerSQLMetadataStorageCoordinator(
        transactionFactory,
        mapper,
        derbyConnectorRule.metadataTablesConfigSupplier().get(),
        derbyConnector,
        segmentSchemaManager,
        CentralizedDatasourceSchemaConfig.create()
    )
    {
      @Override
      protected SegmentPublishResult updateDataSourceMetadataInTransaction(
          SegmentMetadataTransaction transaction,
          String supervisorId,
          String dataSource,
          DataSourceMetadata startMetadata,
          DataSourceMetadata endMetadata
      ) throws IOException
      {
        // Count number of times this method is called.
        metadataUpdateCounter.getAndIncrement();
        return super.updateDataSourceMetadataInTransaction(transaction, supervisorId, dataSource, startMetadata, endMetadata);
      }
    };
  }

  @After
  public void tearDown()
  {
    segmentMetadataCache.stopBeingLeader();
    segmentMetadataCache.stop();
    leaderSelector.stopBeingLeader();
  }

  private void refreshCache()
  {
    if (isCacheEnabled()) {
      cachePollExecutor.finishNextPendingTasks(2);
    }
  }
  
  private boolean isCacheEnabled()
  {
    return cacheMode != SegmentMetadataCache.UsageMode.NEVER;
  }

  @Test
  public void testCommitAppendSegments()
  {
    final String v1 = "2023-01-01";
    final String v2 = "2023-01-02";
    final String v3 = "2023-01-03";
    final String alreadyUpgradedVersion = "2023-02-01";
    final String lockVersion = "2024-01-01";

    final String taskAllocatorId = "appendTask";
    final String replaceTaskId = "replaceTask1";
    final ReplaceTaskLock replaceLock = new ReplaceTaskLock(
        replaceTaskId,
        Intervals.of("2023-01-01/2023-01-03"),
        lockVersion
    );

    final Set<DataSegment> appendSegments = new HashSet<>();
    final List<PendingSegmentRecord> pendingSegmentsForTask = new ArrayList<>();
    final Set<DataSegment> expectedSegmentsToUpgrade = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      final DataSegment segment = createSegment(
          Intervals.of("2023-01-01/2023-01-02"),
          v1,
          new LinearShardSpec(i)
      );
      appendSegments.add(segment);
      expectedSegmentsToUpgrade.add(segment);
      // Add the same segment
      pendingSegmentsForTask.add(
          PendingSegmentRecord.create(
              SegmentIdWithShardSpec.fromDataSegment(segment),
              v1,
              segment.getId().toString(),
              null,
              taskAllocatorId
          )
      );
      // Add upgraded pending segment
      pendingSegmentsForTask.add(
          PendingSegmentRecord.create(
              new SegmentIdWithShardSpec(
                  TestDataSource.WIKI,
                  Intervals.of("2023-01-01/2023-02-01"),
                  alreadyUpgradedVersion,
                  new NumberedShardSpec(i, 0)
              ),
              alreadyUpgradedVersion,
              segment.getId().toString(),
              segment.getId().toString(),
              taskAllocatorId
          )
      );
    }

    for (int i = 0; i < 10; i++) {
      final DataSegment segment = createSegment(
          Intervals.of("2023-01-02/2023-01-03"),
          v2,
          new LinearShardSpec(i)
      );
      appendSegments.add(segment);
      expectedSegmentsToUpgrade.add(segment);
      // Add the same segment
      pendingSegmentsForTask.add(
          PendingSegmentRecord.create(
              SegmentIdWithShardSpec.fromDataSegment(segment),
              v2,
              segment.getId().toString(),
              null,
              taskAllocatorId
          )
      );
      // Add upgraded pending segment
      pendingSegmentsForTask.add(
          PendingSegmentRecord.create(
              new SegmentIdWithShardSpec(
                  TestDataSource.WIKI,
                  Intervals.of("2023-01-01/2023-02-01"),
                  alreadyUpgradedVersion,
                  new NumberedShardSpec(10 + i, 0)
              ),
              alreadyUpgradedVersion,
              segment.getId().toString(),
              segment.getId().toString(),
              taskAllocatorId
          )
      );
    }

    for (int i = 0; i < 10; i++) {
      final DataSegment segment = createSegment(
          Intervals.of("2023-01-03/2023-01-04"),
          v3,
          new LinearShardSpec(i)
      );
      appendSegments.add(segment);
      // Add the same segment
      pendingSegmentsForTask.add(
          PendingSegmentRecord.create(
              SegmentIdWithShardSpec.fromDataSegment(segment),
              v3,
              segment.getId().toString(),
              null,
              taskAllocatorId
          )
      );
      // Add upgraded pending segment
      pendingSegmentsForTask.add(
          PendingSegmentRecord.create(
              new SegmentIdWithShardSpec(
                  TestDataSource.WIKI,
                  Intervals.of("2023-01-01/2023-02-01"),
                  alreadyUpgradedVersion,
                  new NumberedShardSpec(20 + i, 0)
              ),
              alreadyUpgradedVersion,
              segment.getId().toString(),
              segment.getId().toString(),
              taskAllocatorId
          )
      );
    }

    insertPendingSegments(TestDataSource.WIKI, pendingSegmentsForTask, false);

    final Map<DataSegment, ReplaceTaskLock> segmentToReplaceLock
        = expectedSegmentsToUpgrade.stream()
                                   .collect(Collectors.toMap(s -> s, s -> replaceLock));

    // Commit the segment and verify the results
    SegmentPublishResult commitResult
        = coordinator.commitAppendSegments(appendSegments, segmentToReplaceLock, taskAllocatorId, null);
    Assert.assertTrue(commitResult.isSuccess());

    Set<DataSegment> allCommittedSegments
        = new HashSet<>(retrieveUsedSegments(derbyConnectorRule.metadataTablesConfigSupplier().get()));
    Map<String, String> upgradedFromSegmentIdMap = coordinator.retrieveUpgradedFromSegmentIds(
        TestDataSource.WIKI,
        allCommittedSegments.stream().map(DataSegment::getId).map(SegmentId::toString).collect(Collectors.toSet())
    );
    // Verify the segments present in the metadata store
    Assert.assertTrue(allCommittedSegments.containsAll(appendSegments));
    for (DataSegment segment : appendSegments) {
      Assert.assertNull(upgradedFromSegmentIdMap.get(segment.getId().toString()));
    }
    allCommittedSegments.removeAll(appendSegments);

    // Verify the commit of upgraded pending segments
    Assert.assertEquals(appendSegments.size(), allCommittedSegments.size());
    Map<String, DataSegment> segmentMap = new HashMap<>();
    for (DataSegment segment : appendSegments) {
      segmentMap.put(segment.getId().toString(), segment);
    }
    for (DataSegment segment : allCommittedSegments) {
      for (PendingSegmentRecord pendingSegmentRecord : pendingSegmentsForTask) {
        if (pendingSegmentRecord.getId().asSegmentId().toString().equals(segment.getId().toString())) {
          DataSegment upgradedFromSegment = segmentMap.get(pendingSegmentRecord.getUpgradedFromSegmentId());
          Assert.assertNotNull(upgradedFromSegment);
          Assert.assertEquals(segment.getLoadSpec(), upgradedFromSegment.getLoadSpec());
          Assert.assertEquals(
              pendingSegmentRecord.getUpgradedFromSegmentId(),
              upgradedFromSegmentIdMap.get(segment.getId().toString())
          );
        }
      }
    }

    // Verify entries in the segment task lock table
    final Set<String> expectedUpgradeSegmentIds
        = expectedSegmentsToUpgrade.stream()
                                   .map(s -> s.getId().toString())
                                   .collect(Collectors.toSet());
    final Map<String, String> observedSegmentToLock = getSegmentsCommittedDuringReplaceTask(
        replaceTaskId,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(expectedUpgradeSegmentIds, observedSegmentToLock.keySet());

    final Set<String> observedLockVersions = new HashSet<>(observedSegmentToLock.values());
    Assert.assertEquals(1, observedLockVersions.size());
    Assert.assertEquals(replaceLock.getVersion(), Iterables.getOnlyElement(observedLockVersions));
  }

  @Test
  public void testCommitReplaceSegments_partiallyOverlappingPendingSegmentUnsupported()
  {
    final ReplaceTaskLock replaceLock = new ReplaceTaskLock("g1", Intervals.of("2023-01-01/2023-02-01"), "2023-02-01");
    final Set<DataSegment> segmentsAppendedWithReplaceLock = new HashSet<>();
    final Map<DataSegment, ReplaceTaskLock> appendedSegmentToReplaceLockMap = new HashMap<>();
    final PendingSegmentRecord pendingSegmentForInterval = PendingSegmentRecord.create(
        new SegmentIdWithShardSpec(
            "foo",
            Intervals.of("2023-01-01/2024-01-01"),
            "2023-01-02",
            new NumberedShardSpec(100, 0)
        ),
        "",
        "",
        null,
        "append"
    );
    for (int i = 1; i < 9; i++) {
      final DataSegment segment = new DataSegment(
          "foo",
          Intervals.of("2023-01-0" + i + "/2023-01-0" + (i + 1)),
          "2023-01-0" + i,
          ImmutableMap.of("path", "a-" + i),
          ImmutableList.of("dim1"),
          ImmutableList.of("m1"),
          new LinearShardSpec(0),
          9,
          100
      );
      segmentsAppendedWithReplaceLock.add(segment);
      appendedSegmentToReplaceLockMap.put(segment, replaceLock);
    }

    segmentSchemaTestUtils.insertUsedSegments(segmentsAppendedWithReplaceLock, Collections.emptyMap());
    insertPendingSegments("foo", List.of(pendingSegmentForInterval), true);
    insertIntoUpgradeSegmentsTable(appendedSegmentToReplaceLockMap, derbyConnectorRule.metadataTablesConfigSupplier().get());

    final Set<DataSegment> replacingSegments = new HashSet<>();
    for (int i = 1; i < 9; i++) {
      final DataSegment segment = new DataSegment(
          "foo",
          Intervals.of("2023-01-01/2023-02-01"),
          "2023-02-01",
          ImmutableMap.of("path", "b-" + i),
          ImmutableList.of("dim1"),
          ImmutableList.of("m1"),
          new NumberedShardSpec(i, 9),
          9,
          100
      );
      replacingSegments.add(segment);
    }

    Assert.assertFalse(
        coordinator.commitReplaceSegments(replacingSegments, ImmutableSet.of(replaceLock), null)
                   .isSuccess()
    );
  }

  @Test
  public void testCommitReplaceSegments()
  {
    final ReplaceTaskLock replaceLock = new ReplaceTaskLock("g1", Intervals.of("2023-01-01/2023-02-01"), "2023-02-01");
    final Set<DataSegment> segmentsAppendedWithReplaceLock = new HashSet<>();
    final Map<DataSegment, ReplaceTaskLock> appendedSegmentToReplaceLockMap = new HashMap<>();
    final PendingSegmentRecord pendingSegmentInInterval = PendingSegmentRecord.create(
        new SegmentIdWithShardSpec(
            "foo",
            Intervals.of("2023-01-01/2023-01-02"),
            "2023-01-02",
            new NumberedShardSpec(100, 0)
        ),
        "",
        "",
        null,
        "append"
    );
    final PendingSegmentRecord pendingSegmentOutsideInterval = PendingSegmentRecord.create(
        new SegmentIdWithShardSpec(
            "foo",
            Intervals.of("2023-04-01/2023-04-02"),
            "2023-01-02",
            new NumberedShardSpec(100, 0)
        ),
        "",
        "",
        null,
        "append"
    );
    for (int i = 1; i < 9; i++) {
      final DataSegment segment = new DataSegment(
          "foo",
          Intervals.of("2023-01-0" + i + "/2023-01-0" + (i + 1)),
          "2023-01-0" + i,
          ImmutableMap.of("path", "a-" + i),
          ImmutableList.of("dim1"),
          ImmutableList.of("m1"),
          new LinearShardSpec(0),
          9,
          100
      );
      segmentsAppendedWithReplaceLock.add(segment);
      appendedSegmentToReplaceLockMap.put(segment, replaceLock);
    }

    segmentSchemaTestUtils.insertUsedSegments(segmentsAppendedWithReplaceLock, Collections.emptyMap());
    insertPendingSegments(
        "foo",
        List.of(pendingSegmentInInterval, pendingSegmentOutsideInterval),
        true
    );
    insertIntoUpgradeSegmentsTable(appendedSegmentToReplaceLockMap, derbyConnectorRule.metadataTablesConfigSupplier().get());

    final Set<DataSegment> replacingSegments = new HashSet<>();
    for (int i = 1; i < 9; i++) {
      final DataSegment segment = new DataSegment(
          "foo",
          Intervals.of("2023-01-01/2023-02-01"),
          "2023-02-01",
          ImmutableMap.of("path", "b-" + i),
          ImmutableList.of("dim1"),
          ImmutableList.of("m1"),
          new NumberedShardSpec(i, 9),
          9,
          100
      );
      replacingSegments.add(segment);
    }

    coordinator.commitReplaceSegments(replacingSegments, ImmutableSet.of(replaceLock), null);

    Assert.assertEquals(
        2L * segmentsAppendedWithReplaceLock.size() + replacingSegments.size(),
        retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get()).size()
    );

    final Set<DataSegment> usedSegments
        = new HashSet<>(retrieveUsedSegments(derbyConnectorRule.metadataTablesConfigSupplier().get()));

    final Map<String, String> upgradedFromSegmentIdMap = coordinator.retrieveUpgradedFromSegmentIds(
        "foo",
        usedSegments.stream().map(DataSegment::getId).map(SegmentId::toString).collect(Collectors.toSet())
    );

    Assert.assertTrue(usedSegments.containsAll(segmentsAppendedWithReplaceLock));
    for (DataSegment appendSegment : segmentsAppendedWithReplaceLock) {
      Assert.assertNull(upgradedFromSegmentIdMap.get(appendSegment.getId().toString()));
    }
    usedSegments.removeAll(segmentsAppendedWithReplaceLock);

    Assert.assertTrue(usedSegments.containsAll(replacingSegments));
    for (DataSegment replaceSegment : replacingSegments) {
      Assert.assertNull(upgradedFromSegmentIdMap.get(replaceSegment.getId().toString()));
    }
    usedSegments.removeAll(replacingSegments);

    Assert.assertEquals(segmentsAppendedWithReplaceLock.size(), usedSegments.size());
    for (DataSegment segmentReplicaWithNewVersion : usedSegments) {
      boolean hasBeenCarriedForward = false;
      for (DataSegment appendedSegment : segmentsAppendedWithReplaceLock) {
        if (appendedSegment.getLoadSpec().equals(segmentReplicaWithNewVersion.getLoadSpec())) {
          Assert.assertEquals(
              appendedSegment.getId().toString(),
              upgradedFromSegmentIdMap.get(segmentReplicaWithNewVersion.getId().toString())
          );
          hasBeenCarriedForward = true;
          break;
        }
      }
      Assert.assertTrue(hasBeenCarriedForward);
    }

    List<PendingSegmentRecord> pendingSegmentsInInterval =
        coordinator.getPendingSegments("foo", Intervals.of("2023-01-01/2023-02-01"));
    Assert.assertEquals(2, pendingSegmentsInInterval.size());
    final SegmentId rootPendingSegmentId = pendingSegmentInInterval.getId().asSegmentId();
    if (pendingSegmentsInInterval.get(0).getUpgradedFromSegmentId() == null) {
      Assert.assertEquals(rootPendingSegmentId, pendingSegmentsInInterval.get(0).getId().asSegmentId());
      Assert.assertEquals(rootPendingSegmentId.toString(), pendingSegmentsInInterval.get(1).getUpgradedFromSegmentId());
    } else {
      Assert.assertEquals(rootPendingSegmentId, pendingSegmentsInInterval.get(1).getId().asSegmentId());
      Assert.assertEquals(rootPendingSegmentId.toString(), pendingSegmentsInInterval.get(0).getUpgradedFromSegmentId());
    }

    List<PendingSegmentRecord> pendingSegmentsOutsideInterval =
        coordinator.getPendingSegments("foo", Intervals.of("2023-04-01/2023-05-01"));
    Assert.assertEquals(1, pendingSegmentsOutsideInterval.size());
    Assert.assertEquals(
        pendingSegmentOutsideInterval.getId().asSegmentId(), pendingSegmentsOutsideInterval.get(0).getId().asSegmentId()
    );
  }

  @Test
  public void testDuplicatePendingSegmentEntriesAreNotInserted()
  {
    final PendingSegmentRecord pendingSegment0 = PendingSegmentRecord.create(
        new SegmentIdWithShardSpec("foo", Intervals.ETERNITY, "version", new NumberedShardSpec(0, 0)),
        "sequenceName0",
        "sequencePrevId0",
        null,
        "taskAllocatorId"
    );
    final PendingSegmentRecord pendingSegment1 = PendingSegmentRecord.create(
        new SegmentIdWithShardSpec("foo", Intervals.ETERNITY, "version", new NumberedShardSpec(1, 0)),
        "sequenceName1",
        "sequencePrevId1",
        null,
        "taskAllocatorId"
    );
    final int actualInserted = insertPendingSegments(
        "foo",
        List.of(pendingSegment0, pendingSegment0, pendingSegment1, pendingSegment1, pendingSegment1),
        true
    );
    Assert.assertEquals(2, actualInserted);
  }

  @Test
  public void testSimpleAnnounce() throws IOException
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    for (DataSegment segment : SEGMENTS) {
      Assert.assertArrayEquals(
          mapper.writeValueAsString(segment).getBytes(StandardCharsets.UTF_8),
          derbyConnector.lookup(
              derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
              "id",
              "payload",
              segment.getId().toString()
          )
      );
    }

    Assert.assertEquals(
        ImmutableList.of(defaultSegment.getId().toString(), defaultSegment2.getId().toString()),
        retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get())
    );

    // Should not update dataSource metadata.
    Assert.assertEquals(0, metadataUpdateCounter.get());
  }

  @Test
  public void testAnnounceHistoricalSegments() throws IOException
  {
    Set<DataSegment> segments = new HashSet<>();

    for (int i = 0; i < 105; i++) {
      DataSegment segment = new DataSegment(
          "fooDataSource",
          Intervals.of("2015-01-01T00Z/2015-01-02T00Z"),
          "version",
          ImmutableMap.of(),
          ImmutableList.of("dim1"),
          ImmutableList.of("m1"),
          new LinearShardSpec(i),
          9,
          100
      );
      segments.add(segment);
    }

    coordinator.commitSegments(segments, null);
    for (DataSegment segment : segments) {
      Assert.assertArrayEquals(
          mapper.writeValueAsString(segment).getBytes(StandardCharsets.UTF_8),
          derbyConnector.lookup(
              derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
              "id",
              "payload",
              segment.getId().toString()
          )
      );
    }

    List<String> segmentIds = segments.stream()
                                      .map(segment -> segment.getId().toString())
                                      .sorted(Comparator.naturalOrder())
                                      .collect(Collectors.toList());

    Assert.assertEquals(segmentIds, retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get()));

    // Should not update dataSource metadata.
    Assert.assertEquals(0, metadataUpdateCounter.get());
  }

  @Test
  public void testOvershadowingAnnounce() throws IOException
  {
    final ImmutableSet<DataSegment> segments = ImmutableSet.of(defaultSegment, defaultSegment2, defaultSegment4);

    coordinator.commitSegments(segments, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

    for (DataSegment segment : segments) {
      Assert.assertArrayEquals(
          mapper.writeValueAsString(segment).getBytes(StandardCharsets.UTF_8),
          derbyConnector.lookup(
              derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
              "id",
              "payload",
              segment.getId().toString()
          )
      );
    }

    Assert.assertEquals(ImmutableList.of(defaultSegment4.getId().toString()), retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get()));
  }

  @Test
  public void testTransactionalAnnounceSuccess() throws IOException
  {
    // Insert first segment.
    final SegmentPublishResult result1 = coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.ok(ImmutableSet.of(defaultSegment)), result1);

    Assert.assertArrayEquals(
        mapper.writeValueAsString(defaultSegment).getBytes(StandardCharsets.UTF_8),
        derbyConnector.lookup(
            derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
            "id",
            "payload",
            defaultSegment.getId().toString()
        )
    );

    // Insert second segment.
    final SegmentPublishResult result2 = coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment2),
        SUPERVISOR_ID,
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.ok(ImmutableSet.of(defaultSegment2)), result2);

    Assert.assertArrayEquals(
        mapper.writeValueAsString(defaultSegment2).getBytes(StandardCharsets.UTF_8),
        derbyConnector.lookup(
            derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
            "id",
            "payload",
            defaultSegment2.getId().toString()
        )
    );

    // Examine metadata.
    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );

    // Should only be tried once per call.
    Assert.assertEquals(2, metadataUpdateCounter.get());
  }

  @Test
  public void testTransactionalAnnounceRetryAndSuccess() throws IOException
  {
    final AtomicLong attemptCounter = new AtomicLong();

    final IndexerSQLMetadataStorageCoordinator failOnceCoordinator = new IndexerSQLMetadataStorageCoordinator(
        transactionFactory,
        mapper,
        derbyConnectorRule.metadataTablesConfigSupplier().get(),
        derbyConnector,
        segmentSchemaManager,
        CentralizedDatasourceSchemaConfig.create()
    )
    {
      @Override
      protected SegmentPublishResult updateDataSourceMetadataInTransaction(
          SegmentMetadataTransaction transaction,
          String supervisorId,
          String dataSource,
          DataSourceMetadata startMetadata,
          DataSourceMetadata endMetadata
      ) throws IOException
      {
        metadataUpdateCounter.getAndIncrement();
        if (attemptCounter.getAndIncrement() == 0) {
          return SegmentPublishResult.retryableFailure("this failure can be retried");
        } else {
          return super.updateDataSourceMetadataInTransaction(transaction, supervisorId, dataSource, startMetadata, endMetadata);
        }
      }
    };

    // Insert first segment.
    final SegmentPublishResult result1 = failOnceCoordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.retryableFailure("this failure can be retried"), result1);

    final SegmentPublishResult resultOnRetry = failOnceCoordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.ok(ImmutableSet.of(defaultSegment)), resultOnRetry);

    Assert.assertArrayEquals(
        mapper.writeValueAsString(defaultSegment).getBytes(StandardCharsets.UTF_8),
        derbyConnector.lookup(
            derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
            "id",
            "payload",
            defaultSegment.getId().toString()
        )
    );

    // Reset attempt counter to induce another failure.
    attemptCounter.set(0);

    // Insert second segment.
    final SegmentPublishResult result2 = failOnceCoordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment2),
        SUPERVISOR_ID,
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.retryableFailure("this failure can be retried"), result2);

    final SegmentPublishResult resultOnRetry2 = failOnceCoordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment2),
        SUPERVISOR_ID,
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.ok(ImmutableSet.of(defaultSegment2)), resultOnRetry2);

    Assert.assertArrayEquals(
        mapper.writeValueAsString(defaultSegment2).getBytes(StandardCharsets.UTF_8),
        derbyConnector.lookup(
            derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
            "id",
            "payload",
            defaultSegment2.getId().toString()
        )
    );

    // Examine metadata.
    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        failOnceCoordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );

    // Should be tried twice per call.
    Assert.assertEquals(4, metadataUpdateCounter.get());
  }

  @Test
  public void testTransactionalAnnounceFailDbNullWantNotNull()
  {
    final SegmentPublishResult result1 = coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(
        SegmentPublishResult.retryableFailure(
            "The new start metadata state[ObjectMetadata{theObject={foo=bar}}] is ahead of the last committed"
            + " end state[null]. Try resetting the supervisor."
        ),
        result1
    );

    // Should only be tried once.
    Assert.assertEquals(1, metadataUpdateCounter.get());
  }

  @Test
  public void testTransactionalAnnounceFailDbNotNullWantNull()
  {
    final SegmentPublishResult result1 = coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.ok(ImmutableSet.of(defaultSegment)), result1);

    final SegmentPublishResult result2 = coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment2),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(
        SegmentPublishResult.fail(
            "Inconsistency between stored metadata state[ObjectMetadata{theObject={foo=baz}}]"
            + " and target state[ObjectMetadata{theObject=null}]. Try resetting the supervisor."
        ),
        result2
    );

    // Should only be tried once per call.
    Assert.assertEquals(2, metadataUpdateCounter.get());
  }

  @Test
  public void test_commitSegmentsAndMetadata_isAtomic()
  {
    final String dataSource = defaultSegment.getDataSource();
    Assert.assertNull(coordinator.retrieveDataSourceMetadata(dataSource));

    // Create an instance which fails to insert segments but updates metadata successfully
    final AtomicBoolean isMetadataUpdated = new AtomicBoolean(false);
    final IndexerSQLMetadataStorageCoordinator storageCoordinator = new IndexerSQLMetadataStorageCoordinator(
        transactionFactory,
        mapper,
        derbyConnectorRule.metadataTablesConfigSupplier().get(),
        derbyConnector,
        segmentSchemaManager,
        CentralizedDatasourceSchemaConfig.create()
    )
    {
      @Override
      protected Set<DataSegment> insertSegments(
          SegmentMetadataTransaction transaction,
          Set<DataSegment> segments,
          SegmentSchemaMapping segmentSchemaMapping
      )
      {
        throw new RuntimeException("Fail segment insert");
      }

      @Override
      protected SegmentPublishResult updateDataSourceMetadataInTransaction(
          SegmentMetadataTransaction transaction,
          String supervisorId,
          String dataSource,
          DataSourceMetadata startMetadata,
          DataSourceMetadata endMetadata
      ) throws IOException
      {
        isMetadataUpdated.set(true);
        return super.updateDataSourceMetadataInTransaction(transaction, supervisorId, dataSource, startMetadata, endMetadata);
      }
    };

    MatcherAssert.assertThat(
        Assert.assertThrows(
            RuntimeException.class,
            () -> storageCoordinator.commitSegmentsAndMetadata(
                Set.of(defaultSegment),
                SUPERVISOR_ID,
                new ObjectMetadata(null),
                new ObjectMetadata(Map.of("foo", "baz")),
                null
            )
        ),
        ExceptionMatcher.of(RuntimeException.class)
                        .expectMessageIs("java.lang.RuntimeException: Fail segment insert")
    );

    // Verify that the datasource metadata update succeeded but was rolled back
    Assert.assertTrue(isMetadataUpdated.get());
    Assert.assertNull(coordinator.retrieveDataSourceMetadata(dataSource));
  }

  @Test
  public void testRetrieveUsedSegmentForId()
  {
    coordinator.commitSegments(Set.of(defaultSegment), null);
    Assert.assertEquals(
        defaultSegment,
        coordinator.retrieveUsedSegmentForId(defaultSegment.getId())
    );
  }

  @Test
  public void testRetrieveSegmentForId()
  {
    coordinator.commitSegments(Set.of(defaultSegment), null);
    coordinator.markSegmentAsUnused(defaultSegment.getId());
    Assert.assertEquals(
        defaultSegment,
        coordinator.retrieveSegmentForId(defaultSegment.getId())
    );
  }

  @Test
  public void testCleanUpgradeSegmentsTableForTask()
  {
    Assume.assumeFalse(isCacheEnabled());

    final String taskToClean = "taskToClean";
    final ReplaceTaskLock replaceLockToClean = new ReplaceTaskLock(
        taskToClean,
        Intervals.of("2023-01-01/2023-02-01"),
        "2023-03-01"
    );
    DataSegment segmentToClean0 = createSegment(
        Intervals.of("2023-01-01/2023-02-01"),
        "2023-02-01",
        new NumberedShardSpec(0, 0)
    );
    DataSegment segmentToClean1 = createSegment(
        Intervals.of("2023-01-01/2023-01-02"),
        "2023-01-02",
        new NumberedShardSpec(0, 0)
    );
    insertIntoUpgradeSegmentsTable(
        ImmutableMap.of(segmentToClean0, replaceLockToClean, segmentToClean1, replaceLockToClean),
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );

    // Unrelated task should not result in clean up
    Assert.assertEquals(0, coordinator.deleteUpgradeSegmentsForTask("someRandomTask"));
    // The two segment entries are deleted
    Assert.assertEquals(2, coordinator.deleteUpgradeSegmentsForTask(taskToClean));
    // Nothing further to delete
    Assert.assertEquals(0, coordinator.deleteUpgradeSegmentsForTask(taskToClean));
  }

  @Test
  public void testTransactionalAnnounceFailDbNotNullWantDifferent()
  {
    final SegmentPublishResult result1 = coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(SegmentPublishResult.ok(ImmutableSet.of(defaultSegment)), result1);

    final SegmentPublishResult result2 = coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment2),
        SUPERVISOR_ID,
        new ObjectMetadata(ImmutableMap.of("foo", "qux")),
        new ObjectMetadata(ImmutableMap.of("foo", "baz")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );
    Assert.assertEquals(
        SegmentPublishResult.fail(
            "Inconsistency between stored metadata state[ObjectMetadata{theObject={foo=baz}}] and "
            + "target state[ObjectMetadata{theObject={foo=qux}}]. Try resetting the supervisor."
        ),
        result2
    );

    // Should only be tried once per call.
    Assert.assertEquals(2, metadataUpdateCounter.get());
  }

  @Test
  public void testSimpleUsedList()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval(),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testMultiIntervalUsedList()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    coordinator.commitSegments(ImmutableSet.of(defaultSegment3), new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

    Assertions.assertThat(
        coordinator.retrieveUsedSegmentsForIntervals(
            defaultSegment.getDataSource(),
            ImmutableList.of(defaultSegment.getInterval()),
            Segments.ONLY_VISIBLE
        )
    ).containsOnlyOnce(SEGMENTS.toArray(new DataSegment[0]));

    Assertions.assertThat(
        coordinator.retrieveUsedSegmentsForIntervals(
            defaultSegment.getDataSource(),
            ImmutableList.of(defaultSegment3.getInterval()),
            Segments.ONLY_VISIBLE
        )
    ).containsOnlyOnce(defaultSegment3);

    Assertions.assertThat(
        coordinator.retrieveUsedSegmentsForIntervals(
            defaultSegment.getDataSource(),
            ImmutableList.of(defaultSegment.getInterval(), defaultSegment3.getInterval()),
            Segments.ONLY_VISIBLE
        )
    ).containsOnlyOnce(defaultSegment, defaultSegment2, defaultSegment3);

    //case to check no duplication if two intervals overlapped with the interval of same segment.
    Assertions.assertThat(
        coordinator.retrieveUsedSegmentsForIntervals(
            defaultSegment.getDataSource(),
            ImmutableList.of(
                Intervals.of("2015-01-03T00Z/2015-01-03T05Z"),
                Intervals.of("2015-01-03T09Z/2015-01-04T00Z")
            ),
            Segments.ONLY_VISIBLE
        )
    ).containsOnlyOnce(defaultSegment3);
  }

  @Test
  public void testRetrieveUsedSegmentsUsingMultipleIntervals()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    final List<Interval> intervals = segments.stream().map(DataSegment::getInterval).collect(Collectors.toList());

    final Collection<DataSegment> actualUsedSegments = coordinator.retrieveUsedSegmentsForIntervals(
        TestDataSource.WIKI,
        intervals,
        Segments.ONLY_VISIBLE
    );

    Assert.assertEquals(segments.size(), actualUsedSegments.size());
    Assert.assertTrue(actualUsedSegments.containsAll(segments));
  }

  @Test
  public void testRetrieveAllUsedSegmentsUsingIntervalsOutOfRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1905, 1910);

    final Interval outOfRangeInterval = Intervals.of("1700/1800");
    Assert.assertTrue(segments.stream()
                              .anyMatch(segment -> !segment.getInterval().overlaps(outOfRangeInterval)));

    final Collection<DataSegment> actualUsedSegments = coordinator.retrieveUsedSegmentsForIntervals(
        TestDataSource.WIKI,
        ImmutableList.of(outOfRangeInterval),
        Segments.ONLY_VISIBLE
    );

    Assert.assertEquals(0, actualUsedSegments.size());
  }

  @Test
  public void testRetrieveAllUsedSegmentsUsingNoIntervals()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);

    final Collection<DataSegment> actualUsedSegments = coordinator.retrieveAllUsedSegments(
        TestDataSource.WIKI,
        Segments.ONLY_VISIBLE
    );

    Assert.assertEquals(segments.size(), actualUsedSegments.size());
    Assert.assertTrue(actualUsedSegments.containsAll(segments));
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingSingleIntervalAndNoLimit()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    markAllSegmentsUnused(new HashSet<>(segments), DateTimes.nowUtc());

    final List<DataSegment> actualUnusedSegments = coordinator.retrieveUnusedSegmentsForInterval(
        TestDataSource.WIKI,
        Intervals.of("1900/3000"),
        null,
        null
    );

    Assert.assertEquals(segments.size(), actualUnusedSegments.size());
    Assert.assertTrue(actualUnusedSegments.containsAll(segments));
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingSingleIntervalAndLimitAtRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    markAllSegmentsUnused(new HashSet<>(segments), DateTimes.nowUtc());

    final int requestedLimit = segments.size();
    final List<DataSegment> actualUnusedSegments = coordinator.retrieveUnusedSegmentsForInterval(
        TestDataSource.WIKI,
        Intervals.of("1900/3000"),
        requestedLimit,
        null
    );

    Assert.assertEquals(requestedLimit, actualUnusedSegments.size());
    Assert.assertTrue(actualUnusedSegments.containsAll(segments));
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingSingleIntervalAndLimitInRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    markAllSegmentsUnused(new HashSet<>(segments), DateTimes.nowUtc());

    final int requestedLimit = segments.size() - 1;
    final List<DataSegment> actualUnusedSegments = coordinator.retrieveUnusedSegmentsForInterval(
        TestDataSource.WIKI,
        Intervals.of("1900/3000"),
        requestedLimit,
        null
    );

    Assert.assertEquals(requestedLimit, actualUnusedSegments.size());
    Assert.assertTrue(actualUnusedSegments.containsAll(segments.stream().limit(requestedLimit).collect(Collectors.toList())));
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingSingleIntervalAndLimitOutOfRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    markAllSegmentsUnused(new HashSet<>(segments), DateTimes.nowUtc());

    final int limit = segments.size() + 1;
    final List<DataSegment> actualUnusedSegments = coordinator.retrieveUnusedSegmentsForInterval(
        TestDataSource.WIKI,
        Intervals.of("1900/3000"),
        limit,
        null
    );
    Assert.assertEquals(segments.size(), actualUnusedSegments.size());
    Assert.assertTrue(actualUnusedSegments.containsAll(segments));
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingSingleIntervalOutOfRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1905, 1910);
    markAllSegmentsUnused(new HashSet<>(segments), DateTimes.nowUtc());

    final Interval outOfRangeInterval = Intervals.of("1700/1800");
    Assert.assertTrue(segments.stream()
                              .anyMatch(segment -> !segment.getInterval().overlaps(outOfRangeInterval)));
    final int limit = segments.size() + 1;

    final List<DataSegment> actualUnusedSegments = coordinator.retrieveUnusedSegmentsForInterval(
        TestDataSource.WIKI,
        outOfRangeInterval,
        limit,
        null
    );
    Assert.assertEquals(0, actualUnusedSegments.size());
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingMultipleIntervalsAndNoLimit()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        null,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegments.size());
    Assert.assertTrue(segments.containsAll(actualUnusedSegments));

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        null,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegmentsPlus.size());
    verifyContainsAllSegmentsPlus(segments, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingNoIntervalsNoLimitAndNoLastSegmentId()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        ImmutableList.of(),
        null,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegments.size());
    Assert.assertTrue(segments.containsAll(actualUnusedSegments));

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(),
        null,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegmentsPlus.size());
    verifyContainsAllSegmentsPlus(segments, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingNoIntervalsAndNoLimitAndNoLastSegmentId()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(2033, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    String lastSegmentId = segments.get(9).getId().toString();
    final List<DataSegment> expectedSegmentsAscOrder = segments.stream()
        .filter(s -> s.getId().toString().compareTo(lastSegmentId) > 0)
        .collect(Collectors.toList());
    ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        ImmutableList.of(),
        null,
        lastSegmentId,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(expectedSegmentsAscOrder.size(), actualUnusedSegments.size());
    Assert.assertTrue(expectedSegmentsAscOrder.containsAll(actualUnusedSegments));

    ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(),
        null,
        lastSegmentId,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(expectedSegmentsAscOrder.size(), actualUnusedSegmentsPlus.size());
    verifyContainsAllSegmentsPlus(expectedSegmentsAscOrder, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);

    actualUnusedSegments = retrieveUnusedSegments(
        ImmutableList.of(),
        null,
        lastSegmentId,
        SortOrder.ASC,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(expectedSegmentsAscOrder.size(), actualUnusedSegments.size());
    Assert.assertEquals(expectedSegmentsAscOrder, actualUnusedSegments);

    actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(),
        null,
        lastSegmentId,
        SortOrder.ASC,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(expectedSegmentsAscOrder.size(), actualUnusedSegmentsPlus.size());
    verifyEqualsAllSegmentsPlus(expectedSegmentsAscOrder, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);

    final List<DataSegment> expectedSegmentsDescOrder = segments.stream()
        .filter(s -> s.getId().toString().compareTo(lastSegmentId) < 0)
        .collect(Collectors.toList());
    Collections.reverse(expectedSegmentsDescOrder);

    actualUnusedSegments = retrieveUnusedSegments(
        ImmutableList.of(),
        null,
        lastSegmentId,
        SortOrder.DESC,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(expectedSegmentsDescOrder.size(), actualUnusedSegments.size());
    Assert.assertEquals(expectedSegmentsDescOrder, actualUnusedSegments);

    actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(),
        null,
        lastSegmentId,
        SortOrder.DESC,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(expectedSegmentsDescOrder.size(), actualUnusedSegmentsPlus.size());
    verifyEqualsAllSegmentsPlus(expectedSegmentsDescOrder, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingMultipleIntervalsAndLimitAtRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        segments.size(),
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegments.size());
    Assert.assertTrue(segments.containsAll(actualUnusedSegments));

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(),
        segments.size(),
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegmentsPlus.size());
    verifyContainsAllSegmentsPlus(segments, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingMultipleIntervalsAndLimitInRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final int requestedLimit = segments.size() - 1;
    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        requestedLimit,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    final List<DataSegment> expectedSegments = segments.stream().limit(requestedLimit).collect(Collectors.toList());
    Assert.assertEquals(requestedLimit, actualUnusedSegments.size());
    Assert.assertTrue(actualUnusedSegments.containsAll(expectedSegments));

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(),
        requestedLimit,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(requestedLimit, actualUnusedSegmentsPlus.size());
    verifyContainsAllSegmentsPlus(expectedSegments, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingMultipleIntervalsInSingleBatchLimitAndLastSegmentId()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(2034, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final int requestedLimit = segments.size();
    final String lastSegmentId = segments.get(4).getId().toString();
    final List<DataSegment> expectedSegments = segments.stream()
        .filter(s -> s.getId().toString().compareTo(lastSegmentId) > 0)
        .limit(requestedLimit)
        .collect(Collectors.toList());
    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        requestedLimit,
        lastSegmentId,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size() - 5, actualUnusedSegments.size());
    Assert.assertEquals(actualUnusedSegments, expectedSegments);

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(),
        requestedLimit,
        lastSegmentId,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size() - 5, actualUnusedSegmentsPlus.size());
    verifyEqualsAllSegmentsPlus(expectedSegments, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingMultipleIntervalsLimitAndLastSegmentId()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final int requestedLimit = segments.size() - 1;
    final String lastSegmentId = segments.get(4).getId().toString();
    final List<DataSegment> expectedSegments = segments.stream()
        .filter(s -> s.getId().toString().compareTo(lastSegmentId) > 0)
        .limit(requestedLimit)
        .collect(Collectors.toList());
    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        requestedLimit,
        lastSegmentId,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(requestedLimit - 4, actualUnusedSegments.size());
    Assert.assertEquals(actualUnusedSegments, expectedSegments);

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        requestedLimit,
        lastSegmentId,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(requestedLimit - 4, actualUnusedSegmentsPlus.size());
    verifyEqualsAllSegmentsPlus(expectedSegments, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingMultipleIntervals()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 2133);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        segments.size() + 1,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegments.size());
    Assert.assertTrue(actualUnusedSegments.containsAll(segments));

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        segments.stream().map(DataSegment::getInterval).collect(Collectors.toList()),
        segments.size() + 1,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegmentsPlus.size());
    verifyContainsAllSegmentsPlus(segments, actualUnusedSegmentsPlus, usedStatusLastUpdatedTime);
  }

  @Test
  public void testRetrieveUnusedSegmentsUsingIntervalOutOfRange()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1905, 1910);
    markAllSegmentsUnused(new HashSet<>(segments), DateTimes.nowUtc());

    final Interval outOfRangeInterval = Intervals.of("1700/1800");
    Assert.assertTrue(segments.stream()
                              .anyMatch(segment -> !segment.getInterval().overlaps(outOfRangeInterval)));

    final ImmutableList<DataSegment> actualUnusedSegments = retrieveUnusedSegments(
        ImmutableList.of(outOfRangeInterval),
        null,
        null,
        null,
         null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(0, actualUnusedSegments.size());

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(outOfRangeInterval),
        null,
        null,
        null,
        null,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(0, actualUnusedSegmentsPlus.size());
  }

  @Test
  public void testRetrieveUnusedSegmentsWithMaxUsedStatusLastUpdatedTime()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1905, 1910);
    DateTime usedStatusLastUpdatedTime = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(segments), usedStatusLastUpdatedTime);

    final Interval interval = Intervals.of("1905/1920");

    final ImmutableList<DataSegment> actualUnusedSegments1 = retrieveUnusedSegments(
        ImmutableList.of(interval),
        null,
        null,
        null,
        DateTimes.nowUtc(),
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(5, actualUnusedSegments1.size());

    ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(interval),
        null,
        null,
        null,
        DateTimes.nowUtc(),
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(5, actualUnusedSegmentsPlus.size());

    final ImmutableList<DataSegment> actualUnusedSegments2 = retrieveUnusedSegments(
        ImmutableList.of(interval),
        null,
        null,
        null,
        DateTimes.nowUtc().minusHours(1),
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(0, actualUnusedSegments2.size());

    actualUnusedSegmentsPlus = retrieveUnusedSegmentsPlus(
        ImmutableList.of(interval),
        null,
        null,
        null,
        DateTimes.nowUtc().minusHours(1),
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(0, actualUnusedSegmentsPlus.size());
  }

  @Test
  public void testRetrieveUnusedSegmentsWithMaxUsedStatusLastUpdatedTime2()
  {
    final List<DataSegment> segments = createAndGetUsedYearSegments(1900, 1950);
    final List<DataSegment> evenYearSegments = new ArrayList<>();
    final List<DataSegment> oddYearSegments = new ArrayList<>();

    for (int i = 0; i < segments.size(); i++) {
      DataSegment dataSegment = segments.get(i);
      if (i % 2 == 0) {
        evenYearSegments.add(dataSegment);
      } else {
        oddYearSegments.add(dataSegment);
      }
    }

    final DateTime maxUsedStatusLastUpdatedTime1 = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(oddYearSegments), maxUsedStatusLastUpdatedTime1);

    final DateTime maxUsedStatusLastUpdatedTime2 = DateTimes.nowUtc();
    markAllSegmentsUnused(new HashSet<>(evenYearSegments), maxUsedStatusLastUpdatedTime2);

    final Interval interval = Intervals.of("1900/1950");

    final ImmutableList<DataSegment> actualUnusedSegments1 = retrieveUnusedSegments(
        ImmutableList.of(interval),
        null,
        null,
        null,
        maxUsedStatusLastUpdatedTime1,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(oddYearSegments.size(), actualUnusedSegments1.size());

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus1 = retrieveUnusedSegmentsPlus(
        ImmutableList.of(interval),
        null,
        null,
        null,
        maxUsedStatusLastUpdatedTime1,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(oddYearSegments.size(), actualUnusedSegmentsPlus1.size());

    final ImmutableList<DataSegment> actualUnusedSegments2 = retrieveUnusedSegments(
        ImmutableList.of(interval),
        null,
        null,
        null,
        maxUsedStatusLastUpdatedTime2,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegments2.size());

    final ImmutableList<DataSegmentPlus> actualUnusedSegmentsPlus2 = retrieveUnusedSegmentsPlus(
        ImmutableList.of(interval),
        null,
        null,
        null,
        maxUsedStatusLastUpdatedTime2,
        derbyConnectorRule.metadataTablesConfigSupplier().get()
    );
    Assert.assertEquals(segments.size(), actualUnusedSegmentsPlus2.size());
  }

  @Test
  public void testSimpleUnusedList()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval(),
                null,
                null
            )
        )
    );
  }

  @Test
  public void testRetrieveUnusedSegmentsWithVersions()
  {
    final DateTime now = DateTimes.nowUtc();
    final String v1 = now.toString();
    final String v2 = now.plusDays(2).toString();
    final String v3 = now.plusDays(3).toString();
    final String v4 = now.plusDays(4).toString();

    final DataSegment segment1 = createSegment(
        Intervals.of("2023-01-01/2023-01-02"),
        v1,
        new LinearShardSpec(0)
    );
    final DataSegment segment2 = createSegment(
        Intervals.of("2023-01-02/2023-01-03"),
        v2,
        new LinearShardSpec(0)
    );
    final DataSegment segment3 = createSegment(
        Intervals.of("2023-01-03/2023-01-04"),
        v3,
        new LinearShardSpec(0)
    );
    final DataSegment segment4 = createSegment(
        Intervals.of("2023-01-03/2023-01-04"),
        v4,
        new LinearShardSpec(0)
    );

    final ImmutableSet<DataSegment> unusedSegments = ImmutableSet.of(segment1, segment2, segment3, segment4);
    Assert.assertEquals(unusedSegments, coordinator.commitSegments(unusedSegments, null));
    markAllSegmentsUnused(unusedSegments, DateTimes.nowUtc());

    for (DataSegment unusedSegment : unusedSegments) {
      Assertions.assertThat(
          coordinator.retrieveUnusedSegmentsForInterval(
              TestDataSource.WIKI,
              Intervals.of("2023-01-01/2023-01-04"),
              ImmutableList.of(unusedSegment.getVersion()),
              null,
              null
          )
      ).contains(unusedSegment);
    }

    Assertions.assertThat(
        coordinator.retrieveUnusedSegmentsForInterval(
            TestDataSource.WIKI,
            Intervals.of("2023-01-01/2023-01-04"),
            ImmutableList.of(v1, v2),
            null,
            null
        )
    ).contains(segment1, segment2);

    Assertions.assertThat(
        coordinator.retrieveUnusedSegmentsForInterval(
            TestDataSource.WIKI,
            Intervals.of("2023-01-01/2023-01-04"),
            null,
            null,
            null
        )
    ).containsAll(unusedSegments);

    Assertions.assertThat(
        coordinator.retrieveUnusedSegmentsForInterval(
            TestDataSource.WIKI,
            Intervals.of("2023-01-01/2023-01-04"),
            ImmutableList.of("some-non-existent-version"),
              null,
              null
          )
    ).containsAll(ImmutableSet.of());
  }

  @Test
  public void testSimpleUnusedListWithLimit()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    int limit = SEGMENTS.size() - 1;
    Set<DataSegment> retreivedUnusedSegments = ImmutableSet.copyOf(
        coordinator.retrieveUnusedSegmentsForInterval(
            defaultSegment.getDataSource(),
            defaultSegment.getInterval(),
            null,
            limit,
            null
        )
    );
    Assert.assertEquals(limit, retreivedUnusedSegments.size());
    Assert.assertTrue(SEGMENTS.containsAll(retreivedUnusedSegments));
  }

  @Test
  public void testRetrieveUnusedSegmentsWithExactInterval()
  {
    final String dataSource = defaultSegment.getDataSource();
    coordinator.commitSegments(Set.of(defaultSegment, defaultSegment2, defaultSegment3), null);

    final DateTime now = DateTimes.nowUtc();
    markAllSegmentsUnused(Set.of(defaultSegment, defaultSegment2, defaultSegment3), now.minusHours(1));

    // Verify that query for overlapping interval does not return the segments
    Assert.assertTrue(
        coordinator.retrieveUnusedSegmentsWithExactInterval(
            dataSource,
            Intervals.ETERNITY,
            now,
            10
        ).isEmpty()
    );

    // Verify that query for exact interval returns the segments
    Assert.assertEquals(
        List.of(defaultSegment3),
        coordinator.retrieveUnusedSegmentsWithExactInterval(
            dataSource,
            defaultSegment3.getInterval(),
            now,
            10
        )
    );

    Assert.assertEquals(defaultSegment.getInterval(), defaultSegment2.getInterval());
    Assert.assertEquals(
        Set.of(defaultSegment, defaultSegment2),
        Set.copyOf(
            coordinator.retrieveUnusedSegmentsWithExactInterval(
                dataSource,
                defaultSegment.getInterval(),
                now,
                10
            )
        )
    );

    // Verify that query with limit 1 returns only 1 result
    Assert.assertEquals(
        1,
        coordinator.retrieveUnusedSegmentsWithExactInterval(
            dataSource,
            defaultSegment.getInterval(),
            now,
            1
        ).size()
    );
  }

  @Test
  public void testRetrieveUnusedSegmentIntervals()
  {
    final String dataSource = defaultSegment.getDataSource();
    coordinator.commitSegments(Set.of(defaultSegment, defaultSegment3), null);

    Assert.assertTrue(coordinator.retrieveUnusedSegmentIntervals(dataSource, 100).isEmpty());

    markAllSegmentsUnused(Set.of(defaultSegment), DateTimes.nowUtc().minusHours(1));
    Assert.assertEquals(
        List.of(defaultSegment.getInterval()),
        coordinator.retrieveUnusedSegmentIntervals(dataSource, 100)
    );

    markAllSegmentsUnused(Set.of(defaultSegment3), DateTimes.nowUtc().minusHours(1));
    Assert.assertEquals(
        Set.of(defaultSegment.getInterval(), defaultSegment3.getInterval()),
        Set.copyOf(coordinator.retrieveUnusedSegmentIntervals(dataSource, 100))
    );

    // Verify retrieve with limit 1 returns only 1 interval
    Assert.assertEquals(
        1,
        coordinator.retrieveUnusedSegmentIntervals(dataSource, 1).size()
    );
  }

  @Test
  public void testRetrieveAllDatasourceNames()
  {
    coordinator.commitSegments(Set.of(defaultSegment), null);
    coordinator.commitSegments(Set.of(hugeTimeRangeSegment1), null);
    Assert.assertEquals(
        Set.of("fooDataSource", "hugeTimeRangeDataSource"),
        coordinator.retrieveAllDatasourceNames()
    );
  }

  @Test
  public void testUsedOverlapLow()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    Set<DataSegment> actualSegments = ImmutableSet.copyOf(
        coordinator.retrieveUsedSegmentsForInterval(
            defaultSegment.getDataSource(),
            Intervals.of("2014-12-31T23:59:59.999Z/2015-01-01T00:00:00.001Z"), // end is exclusive
            Segments.ONLY_VISIBLE
        )
    );
    Assert.assertEquals(
        SEGMENTS,
        actualSegments
    );
  }


  @Test
  public void testUsedOverlapHigh()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                defaultSegment.getDataSource(),
                Intervals.of("2015-1-1T23:59:59.999Z/2015-02-01T00Z"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testUsedOutOfBoundsLow()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    Assert.assertTrue(
        coordinator.retrieveUsedSegmentsForInterval(
            defaultSegment.getDataSource(),
            new Interval(defaultSegment.getInterval().getStart().minus(1), defaultSegment.getInterval().getStart()),
            Segments.ONLY_VISIBLE
        ).isEmpty()
    );
  }


  @Test
  public void testUsedOutOfBoundsHigh()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    Assert.assertTrue(
        coordinator.retrieveUsedSegmentsForInterval(
            defaultSegment.getDataSource(),
            new Interval(defaultSegment.getInterval().getEnd(), defaultSegment.getInterval().getEnd().plusDays(10)),
            Segments.ONLY_VISIBLE
        ).isEmpty()
    );
  }

  @Test
  public void testUsedWithinBoundsEnd()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval().withEnd(defaultSegment.getInterval().getEnd().minusMillis(1)),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testUsedOverlapEnd()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval().withEnd(defaultSegment.getInterval().getEnd().plusMillis(1)),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testUnusedOverlapLow()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertTrue(
        coordinator.retrieveUnusedSegmentsForInterval(
            defaultSegment.getDataSource(),
            new Interval(
                defaultSegment.getInterval().getStart().minus(1),
                defaultSegment.getInterval().getStart().plus(1)
            ),
            null,
            null
        ).isEmpty()
    );
  }

  @Test
  public void testUnusedUnderlapLow()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertTrue(
        coordinator.retrieveUnusedSegmentsForInterval(
            defaultSegment.getDataSource(),
            new Interval(defaultSegment.getInterval().getStart().plus(1), defaultSegment.getInterval().getEnd()),
            null,
            null
        ).isEmpty()
    );
  }


  @Test
  public void testUnusedUnderlapHigh()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertTrue(
        coordinator.retrieveUnusedSegmentsForInterval(
            defaultSegment.getDataSource(),
            new Interval(defaultSegment.getInterval().getStart(), defaultSegment.getInterval().getEnd().minus(1)),
            null,
            null
        ).isEmpty()
    );
  }

  @Test
  public void testUnusedOverlapHigh()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertTrue(
        coordinator.retrieveUnusedSegmentsForInterval(
            defaultSegment.getDataSource(),
            defaultSegment.getInterval().withStart(defaultSegment.getInterval().getEnd().minus(1)),
            null,
            null
        ).isEmpty()
    );
  }

  @Test
  public void testUnusedBigOverlap()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                defaultSegment.getDataSource(),
                Intervals.of("2000/2999"),
                null,
                null
            )
        )
    );
  }

  @Test
  public void testUnusedLowRange()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval().withStart(defaultSegment.getInterval().getStart().minus(1)),
                null,
                null
            )
        )
    );
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval().withStart(defaultSegment.getInterval().getStart().minusYears(1)),
                null,
                null
            )
        )
    );
  }

  @Test
  public void testUnusedHighRange()
  {
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    markAllSegmentsUnused();
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval().withEnd(defaultSegment.getInterval().getEnd().plus(1)),
                null,
                null
            )
        )
    );
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval().withEnd(defaultSegment.getInterval().getEnd().plusYears(1)),
                null,
                null
            )
        )
    );
  }

  @Test
  public void testUsedHugeTimeRangeEternityFilter()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            hugeTimeRangeSegment1,
            hugeTimeRangeSegment2,
            hugeTimeRangeSegment3
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(hugeTimeRangeSegment1, hugeTimeRangeSegment2, hugeTimeRangeSegment3),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForIntervals(
                hugeTimeRangeSegment1.getDataSource(),
                Intervals.ONLY_ETERNITY,
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testUsedHugeTimeRangeTrickyFilter1()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            hugeTimeRangeSegment1,
            hugeTimeRangeSegment2,
            hugeTimeRangeSegment3
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(hugeTimeRangeSegment2),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                hugeTimeRangeSegment1.getDataSource(),
                Intervals.of("2900/10000"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testUsedHugeTimeRangeTrickyFilter2()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            hugeTimeRangeSegment1,
            hugeTimeRangeSegment2,
            hugeTimeRangeSegment3
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(hugeTimeRangeSegment2),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                hugeTimeRangeSegment1.getDataSource(),
                Intervals.of("2993/2995"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testEternitySegmentWithStringComparison()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            eternitySegment
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(eternitySegment),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                eternitySegment.getDataSource(),
                Intervals.of("2020/2021"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testEternityMultipleSegmentWithStringComparison()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            numberedSegment0of0,
            eternitySegment
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(eternitySegment, numberedSegment0of0),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                eternitySegment.getDataSource(),
                Intervals.of("2015/2016"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testFirstHalfEternitySegmentWithStringComparison()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            firstHalfEternityRangeSegment
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(firstHalfEternityRangeSegment),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                firstHalfEternityRangeSegment.getDataSource(),
                Intervals.of("2020/2021"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testFirstHalfEternityMultipleSegmentWithStringComparison()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            numberedSegment0of0,
            firstHalfEternityRangeSegment
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(numberedSegment0of0, firstHalfEternityRangeSegment),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                firstHalfEternityRangeSegment.getDataSource(),
                Intervals.of("2015/2016"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testSecondHalfEternitySegmentWithStringComparison()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            secondHalfEternityRangeSegment
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(secondHalfEternityRangeSegment),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                secondHalfEternityRangeSegment.getDataSource(),
                Intervals.of("2020/2021"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testLargeIntervalWithStringComparison()
  {
    // Known Issue when not using cache: https://github.com/apache/druid/issues/12860
    Assume.assumeTrue(isCacheEnabled());

    coordinator.commitSegments(
        ImmutableSet.of(
            hugeTimeRangeSegment4
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(hugeTimeRangeSegment4),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                hugeTimeRangeSegment4.getDataSource(),
                Intervals.of("2020/2021"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testSecondHalfEternityMultipleSegmentWithStringComparison()
  {
    coordinator.commitSegments(
        ImmutableSet.of(
            numberedSegment0of0,
            secondHalfEternityRangeSegment
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        ImmutableSet.of(numberedSegment0of0, secondHalfEternityRangeSegment),
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                secondHalfEternityRangeSegment.getDataSource(),
                Intervals.of("2015/2016"),
                Segments.ONLY_VISIBLE
            )
        )
    );
  }

  @Test
  public void testDeleteDataSourceMetadata()
  {
    coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );

    Assert.assertFalse("deleteInvalidDataSourceMetadata", coordinator.deleteDataSourceMetadata("nonExistentSupervisor"));
    Assert.assertTrue("deleteValidDataSourceMetadata", coordinator.deleteDataSourceMetadata(SUPERVISOR_ID));

    Assert.assertNull("getDataSourceMetadataNullAfterDelete", coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID));
  }

  @Test
  public void testDeleteSegmentsInMetaDataStorage()
  {
    // Published segments to MetaDataStorage
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

    // check segments Published
    Assert.assertEquals(
        SEGMENTS,
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval(),
                Segments.ONLY_VISIBLE
            )
        )
    );
    // remove segments in MetaDataStorage
    coordinator.deleteSegments(SEGMENTS);

    // check segments removed
    Assert.assertEquals(
        0,
        ImmutableSet.copyOf(
            coordinator.retrieveUsedSegmentsForInterval(
                defaultSegment.getDataSource(),
                defaultSegment.getInterval(),
                Segments.ONLY_VISIBLE
            )
        ).size()
    );
  }

  @Test
  public void testUpdateSegmentsInMetaDataStorage()
  {
    Assume.assumeFalse(isCacheEnabled());

    // Published segments to MetaDataStorage
    coordinator.commitSegments(SEGMENTS, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

    // check segments Published
    Assert.assertEquals(
            SEGMENTS,
            ImmutableSet.copyOf(
                    coordinator.retrieveUsedSegmentsForInterval(
                            defaultSegment.getDataSource(),
                            defaultSegment.getInterval(),
                            Segments.ONLY_VISIBLE
                    )
            )
    );

    // update single metadata item
    coordinator.updateSegmentMetadata(Collections.singleton(defaultSegment2WithBiggerSize));

    Collection<DataSegment> updated = coordinator.retrieveUsedSegmentsForInterval(
            defaultSegment.getDataSource(),
            defaultSegment.getInterval(),
            Segments.ONLY_VISIBLE);

    Assert.assertEquals(SEGMENTS.size(), updated.size());

    DataSegment defaultAfterUpdate = updated.stream().filter(s -> s.equals(defaultSegment)).findFirst().get();
    DataSegment default2AfterUpdate = updated.stream().filter(s -> s.equals(defaultSegment2)).findFirst().get();

    Assert.assertNotNull(defaultAfterUpdate);
    Assert.assertNotNull(default2AfterUpdate);

    // check that default did not change
    Assert.assertEquals(defaultSegment.getSize(), defaultAfterUpdate.getSize());
    // but that default 2 did change
    Assert.assertEquals(defaultSegment2WithBiggerSize.getSize(), default2AfterUpdate.getSize());
  }

  @Test
  public void testSingleAdditionalNumberedShardWithNoCorePartitions() throws IOException
  {
    additionalNumberedShardTest(ImmutableSet.of(numberedSegment0of0));
  }

  @Test
  public void testMultipleAdditionalNumberedShardsWithNoCorePartitions() throws IOException
  {
    additionalNumberedShardTest(ImmutableSet.of(numberedSegment0of0, numberedSegment1of0, numberedSegment2of0));
  }

  @Test
  public void testSingleAdditionalNumberedShardWithOneCorePartition() throws IOException
  {
    additionalNumberedShardTest(ImmutableSet.of(numberedSegment2of1));
  }

  @Test
  public void testMultipleAdditionalNumberedShardsWithOneCorePartition() throws IOException
  {
    additionalNumberedShardTest(ImmutableSet.of(numberedSegment2of1, numberedSegment3of1));
  }

  private void additionalNumberedShardTest(Set<DataSegment> segments) throws IOException
  {
    coordinator.commitSegments(segments, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

    for (DataSegment segment : segments) {
      Assert.assertArrayEquals(
          mapper.writeValueAsString(segment).getBytes(StandardCharsets.UTF_8),
          derbyConnector.lookup(
              derbyConnectorRule.metadataTablesConfigSupplier().get().getSegmentsTable(),
              "id",
              "payload",
              segment.getId().toString()
          )
      );
    }

    Assert.assertEquals(
        segments.stream().map(segment -> segment.getId().toString()).collect(Collectors.toList()),
        retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get())
    );

    // Should not update dataSource metadata.
    Assert.assertEquals(0, metadataUpdateCounter.get());
  }

  @Test
  public void testAllocatePendingSegment()
  {
    final PartialShardSpec partialShardSpec = NumberedPartialShardSpec.instance();
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    final SegmentIdWithShardSpec identifier = allocatePendingSegment(
        dataSource,
        "seq",
        null,
        interval,
        partialShardSpec,
        "version",
        false,
        null
    );

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version", identifier.toString());

    final SegmentIdWithShardSpec identifier1 = allocatePendingSegment(
        dataSource,
        "seq",
        identifier.toString(),
        interval,
        partialShardSpec,
        identifier.getVersion(),
        false,
        null
    );

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_1", identifier1.toString());

    final SegmentIdWithShardSpec identifier2 = allocatePendingSegment(
        dataSource,
        "seq",
        identifier1.toString(),
        interval,
        partialShardSpec,
        identifier1.getVersion(),
        false,
        null
    );

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_2", identifier2.toString());

    final SegmentIdWithShardSpec identifier3 = allocatePendingSegment(
        dataSource,
        "seq",
        identifier1.toString(),
        interval,
        partialShardSpec,
        identifier1.getVersion(),
        false,
        null
    );

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_2", identifier3.toString());
    Assert.assertEquals(identifier2, identifier3);

    final SegmentIdWithShardSpec identifier4 = allocatePendingSegment(
        dataSource,
        "seq1",
        null,
        interval,
        partialShardSpec,
        "version",
        false,
        null
    );

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_3", identifier4.toString());
  }

  /**
   * This test verifies the behaviour in the following sequence of events:
   * - create segment1 for an interval and publish
   * - create segment2 for same interval and publish
   * - create segment3 for same interval and publish
   * - compact all segments above and publish new segments
   * - create segment4 for the same interval
   * - drop the compacted segment
   * - create segment5 for the same interval
   * - verify that the id for segment5 is correct
   * - Later, after the above was dropped, another segment on same interval was created by the stream but this
   * time there was an integrity violation in the pending segments table because the
   * method returned a segment id that already existed in the pending segments table
   */
  @Test
  public void testAllocatePendingSegmentAfterDroppingExistingSegment()
  {
    String maxVersion = "version_newer_newer";

    // simulate one load using kafka streaming
    final PartialShardSpec partialShardSpec = NumberedPartialShardSpec.instance();
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    final SegmentIdWithShardSpec identifier = allocatePendingSegment(
        dataSource,
        "seq",
        null,
        interval,
        partialShardSpec,
        "version",
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version", identifier.toString());
    // Since there are no used core partitions yet
    Assert.assertEquals(0, identifier.getShardSpec().getNumCorePartitions());

    // simulate one more load using kafka streaming (as if previous segment was published, note different sequence name)
    final SegmentIdWithShardSpec identifier1 = allocatePendingSegment(
        dataSource,
        "seq2",
        identifier.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_1", identifier1.toString());
    // Since there are no used core partitions yet
    Assert.assertEquals(0, identifier1.getShardSpec().getNumCorePartitions());

    // simulate one more load using kafka streaming (as if previous segment was published, note different sequence name)
    final SegmentIdWithShardSpec identifier2 = allocatePendingSegment(
        dataSource,
        "seq3",
        identifier1.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_2", identifier2.toString());
    // Since there are no used core partitions yet
    Assert.assertEquals(0, identifier2.getShardSpec().getNumCorePartitions());

    // now simulate that one compaction was done (batch) ingestion for same interval (like reindex of the previous three):
    DataSegment segment = new DataSegment(
        "ds",
        Intervals.of("2017-01-01T00Z/2017-02-01T00Z"),
        "version_new",
        ImmutableMap.of(),
        ImmutableList.of("dim1"),
        ImmutableList.of("m1"),
        new NumberedShardSpec(0, 1),
        9,
        100
    );
    coordinator.commitSegments(Set.of(segment), null);
    List<String> ids = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_new", ids.get(0));

    // one more load on same interval:
    final SegmentIdWithShardSpec identifier3 = allocatePendingSegment(
        dataSource,
        "seq4",
        identifier1.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_new_1", identifier3.toString());
    // Used segment set has 1 core partition
    Assert.assertEquals(1, identifier3.getShardSpec().getNumCorePartitions());

    // now drop the used segment previously loaded:
    coordinator.markSegmentAsUnused(segment.getId());

    // and final load, this reproduces an issue that could happen with multiple streaming appends,
    // followed by a reindex, followed by a drop, and more streaming data coming in for same interval
    final SegmentIdWithShardSpec identifier4 = allocatePendingSegment(
        dataSource,
        "seq5",
        identifier1.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version_new_2", identifier4.toString());
    // Since all core partitions have been dropped
    Assert.assertEquals(0, identifier4.getShardSpec().getNumCorePartitions());
  }

  /**
   * Slightly different from the above test that involves reverted compaction
   * 1) used segments of version = A, id = 0, 1, 2
   * 2) overwrote segments of version = B, id = 0 <= compaction
   * 3) marked segments unused for version = A, id = 0, 1, 2 <= overshadowing
   * 4) pending segment of version = B, id = 1 <= appending new data, aborted
   * 5) reverted compaction, mark segments used for version = A, id = 0, 1, 2, and mark compacted segments unused
   * 6) used segments of version = A, id = 0, 1, 2
   * 7) pending segment of version = B, id = 1
   */
  @Test
  public void testAnotherAllocatePendingSegmentAfterRevertingCompaction()
  {
    String maxVersion = "Z";

    // 1.0) simulate one append load
    final PartialShardSpec partialShardSpec = NumberedPartialShardSpec.instance();
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    final SegmentIdWithShardSpec identifier = allocatePendingSegment(
        dataSource,
        "seq",
        null,
        interval,
        partialShardSpec,
        "A",
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A", identifier.toString());
    // Assume it publishes; create its corresponding segment
    DataSegment segment = new DataSegment(
        "ds",
        Intervals.of("2017-01-01T00Z/2017-02-01T00Z"),
        "A",
        ImmutableMap.of(),
        ImmutableList.of("dim1"),
        ImmutableList.of("m1"),
        new LinearShardSpec(0),
        9,
        100
    );
    coordinator.commitSegments(Set.of(segment), null);
    List<String> ids = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A", ids.get(0));


    // 1.1) simulate one more append load  (as if previous segment was published, note different sequence name)
    final SegmentIdWithShardSpec identifier1 = allocatePendingSegment(
        dataSource,
        "seq2",
        identifier.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_1", identifier1.toString());
    // Assume it publishes; create its corresponding segment
    segment = new DataSegment(
        "ds",
        Intervals.of("2017-01-01T00Z/2017-02-01T00Z"),
        "A",
        ImmutableMap.of(),
        ImmutableList.of("dim1"),
        ImmutableList.of("m1"),
        new LinearShardSpec(1),
        9,
        100
    );
    coordinator.commitSegments(Set.of(segment), null);
    ids = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_1", ids.get(1));


    // 1.2) simulate one more append load  (as if previous segment was published, note different sequence name)
    final SegmentIdWithShardSpec identifier2 = allocatePendingSegment(
        dataSource,
        "seq3",
        identifier1.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_2", identifier2.toString());
    // Assume it publishes; create its corresponding segment
    segment = new DataSegment(
        "ds",
        Intervals.of("2017-01-01T00Z/2017-02-01T00Z"),
        "A",
        ImmutableMap.of(),
        ImmutableList.of("dim1"),
        ImmutableList.of("m1"),
        new LinearShardSpec(2),
        9,
        100
    );
    // state so far:
    // pendings: A: 0,1,2
    // used segments A: 0,1,2
    // unused segments:
    coordinator.commitSegments(Set.of(segment), null);
    ids = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_2", ids.get(2));


    // 2)
    // now simulate that one compaction was done (batch) ingestion for same interval (like reindex of the previous three):
    DataSegment compactedSegment = new DataSegment(
        "ds",
        Intervals.of("2017-01-01T00Z/2017-02-01T00Z"),
        "B",
        ImmutableMap.of(),
        ImmutableList.of("dim1"),
        ImmutableList.of("m1"),
        new LinearShardSpec(0),
        9,
        100
    );
    coordinator.commitSegments(Set.of(compactedSegment), null);
    ids = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_B", ids.get(3));
    // 3) When overshadowing, segments are still marked as "used" in the segments table
    // state so far:
    // pendings: A: 0,1,2
    // used segments: A: 0,1,2; B: 0 <- new compacted segment, overshadows previous version A
    // unused segment:

    // 4) pending segment of version = B, id = 1 <= appending new data, aborted
    final SegmentIdWithShardSpec identifier3 = allocatePendingSegment(
        dataSource,
        "seq4",
        identifier2.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_B_1", identifier3.toString());
    // no corresponding segment, pending aborted
    // state so far:
    // pendings: A: 0,1,2; B:1 (note that B_1 does not make it into segments since its task aborted)
    // used segments: A: 0,1,2; B: 0 <-  compacted segment, overshadows previous version A
    // unused segment:

    // 5) reverted compaction (by marking B_0 as unused)
    // Revert compaction a manual metadata update which is basically the following two steps:
    coordinator.markSegmentAsUnused(compactedSegment.getId());
    //        pending: version = A, id = 0,1,2
    //                 version = B, id = 1
    //
    //        used segment: version = A, id = 0,1,2
    //        unused segment: version = B, id = 0
    List<String> pendings = retrievePendingSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals(4, pendings.size());

    List<String> used = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals(3, used.size());

    List<String> unused = retrieveUnusedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals(1, unused.size());

    // Simulate one more append load
    final SegmentIdWithShardSpec identifier4 = allocatePendingSegment(
        dataSource,
        "seq5",
        identifier1.toString(),
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    // maxid = B_1 -> new partno = 2
    // versionofexistingchunk=A
    // ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_2
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_3", identifier4.toString());
    // Assume it publishes; create its corresponding segment
    segment = new DataSegment(
        "ds",
        Intervals.of("2017-01-01T00Z/2017-02-01T00Z"),
        "A",
        ImmutableMap.of(),
        ImmutableList.of("dim1"),
        ImmutableList.of("m1"),
        new LinearShardSpec(3),
        9,
        100
    );
    //        pending: version = A, id = 0,1,2,3
    //                 version = B, id = 1
    //
    //        used segment: version = A, id = 0,1,2,3
    //        unused segment: version = B, id = 0
    coordinator.commitSegments(Set.of(segment), null);
    ids = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_3", ids.get(3));

  }

  @Test
  public void testAllocatePendingSegmentsSkipSegmentPayloadFetch()
  {
    final PartialShardSpec partialShardSpec = NumberedPartialShardSpec.instance();
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    final String sequenceName = "seq";

    final SegmentCreateRequest request = new SegmentCreateRequest(sequenceName, null, "v1", partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId0 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request),
        true
    ).get(request);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1", segmentId0.toString());

    final SegmentCreateRequest request1 =
        new SegmentCreateRequest(sequenceName, segmentId0.toString(), segmentId0.getVersion(), partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId1 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request1),
        true
    ).get(request1);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_1", segmentId1.toString());

    final SegmentCreateRequest request2 =
        new SegmentCreateRequest(sequenceName, segmentId1.toString(), segmentId1.getVersion(), partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId2 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request2),
        true
    ).get(request2);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_2", segmentId2.toString());

    final SegmentCreateRequest request3 =
        new SegmentCreateRequest(sequenceName, segmentId1.toString(), segmentId1.getVersion(), partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId3 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request3),
        true
    ).get(request3);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_2", segmentId3.toString());
    Assert.assertEquals(segmentId2, segmentId3);

    final SegmentCreateRequest request4 =
        new SegmentCreateRequest("seq1", null, "v1", partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId4 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request4),
        true
    ).get(request4);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_3", segmentId4.toString());
  }

  @Test
  public void testAllocatePendingSegments()
  {
    final PartialShardSpec partialShardSpec = NumberedPartialShardSpec.instance();
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    final String sequenceName = "seq";

    final SegmentCreateRequest request = new SegmentCreateRequest(sequenceName, null, "v1", partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId0 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request),
        false
    ).get(request);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1", segmentId0.toString());

    final SegmentCreateRequest request1 =
        new SegmentCreateRequest(sequenceName, segmentId0.toString(), segmentId0.getVersion(), partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId1 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request1),
        false
    ).get(request1);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_1", segmentId1.toString());

    final SegmentCreateRequest request2 =
        new SegmentCreateRequest(sequenceName, segmentId1.toString(), segmentId1.getVersion(), partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId2 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request2),
        false
    ).get(request2);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_2", segmentId2.toString());

    final SegmentCreateRequest request3 =
        new SegmentCreateRequest(sequenceName, segmentId1.toString(), segmentId1.getVersion(), partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId3 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request3),
        false
    ).get(request3);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_2", segmentId3.toString());
    Assert.assertEquals(segmentId2, segmentId3);

    final SegmentCreateRequest request4 =
        new SegmentCreateRequest("seq1", null, "v1", partialShardSpec, null);
    final SegmentIdWithShardSpec segmentId4 = coordinator.allocatePendingSegments(
        dataSource,
        interval,
        false,
        Collections.singletonList(request4),
        false
    ).get(request4);

    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_v1_3", segmentId4.toString());
  }

  @Test
  public void testNoPendingSegmentsAndOneUsedSegment()
  {
    String maxVersion = "Z";

    // create one used segment
    DataSegment segment = new DataSegment(
        "ds",
        Intervals.of("2017-01-01T00Z/2017-02-01T00Z"),
        "A",
        ImmutableMap.of(),
        ImmutableList.of("dim1"),
        ImmutableList.of("m1"),
        new LinearShardSpec(0),
        9,
        100
    );

    coordinator.commitSegments(Set.of(segment), null);
    List<String> ids = retrieveUsedSegmentIds(derbyConnectorRule.metadataTablesConfigSupplier().get());
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A", ids.get(0));

    // simulate one aborted append load
    final PartialShardSpec partialShardSpec = NumberedPartialShardSpec.instance();
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    final SegmentIdWithShardSpec identifier = allocatePendingSegment(
        dataSource,
        "seq",
        null,
        interval,
        partialShardSpec,
        maxVersion,
        true,
        null
    );
    Assert.assertEquals("ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_A_1", identifier.toString());
  }

  @Test
  public void test_concurrentAppend_toIntervalWithUnusedAppendSegment_createsFreshVersion()
  {
    final String wiki = TestDataSource.WIKI;
    final String appendLockVersion = PendingSegmentRecord.DEFAULT_VERSION_FOR_CONCURRENT_APPEND;
    final Interval firstOfJan23 = Intervals.of("2023-01-01/P1D");

    // Allocate and commit an APPEND segment
    final String taskAllocator1 = "taskAlloc1";
    final SegmentIdWithShardSpec pendingSegment
        = allocatePendingSegmentForAppendTask(wiki, firstOfJan23, taskAllocator1);

    Assert.assertNotNull(pendingSegment);
    Assert.assertEquals(appendLockVersion, pendingSegment.getVersion());
    Assert.assertEquals(0, pendingSegment.getShardSpec().getPartitionNum());

    final DataSegment segmentV01 = asSegment(pendingSegment);
    coordinator.commitAppendSegments(Set.of(segmentV01), Map.of(), taskAllocator1, null);

    verifyIntervalHasUsedSegments(wiki, firstOfJan23, segmentV01);
    verifyIntervalHasVisibleSegments(wiki, firstOfJan23, segmentV01);

    // Mark the segment as unused with a future update time to avoid race conditions
    final DateTime markUnusedTime = DateTimes.nowUtc().plusHours(1);
    transactionFactory.inReadWriteDatasourceTransaction(
        wiki,
        t -> t.markAllSegmentsAsUnused(markUnusedTime)
    );
    verifyIntervalHasUsedSegments(wiki, firstOfJan23);

    // Allocate and commit another APPEND segment
    final String taskAllocator2 = "taskAlloc2";
    final SegmentIdWithShardSpec pendingSegment2
        = allocatePendingSegmentForAppendTask(wiki, firstOfJan23, taskAllocator2);

    // Verify that the new segment gets a different version
    Assert.assertNotNull(pendingSegment2);
    Assert.assertEquals(appendLockVersion + "S", pendingSegment2.getVersion());
    Assert.assertEquals(0, pendingSegment2.getShardSpec().getPartitionNum());

    final DataSegment segmentV02 = asSegment(pendingSegment2);
    coordinator.commitAppendSegments(Set.of(segmentV02), Map.of(), taskAllocator2, null);
    Assert.assertNotEquals(segmentV01, segmentV02);

    verifyIntervalHasUsedSegments(wiki, firstOfJan23, segmentV02);
    verifyIntervalHasVisibleSegments(wiki, firstOfJan23, segmentV02);
  }

  @Test
  public void test_allocateCommitDelete_createsFreshVersion_uptoMaxAllowedRetries()
  {
    final String wiki = TestDataSource.WIKI;
    final Interval firstOfJan23 = Intervals.of("2023-01-01/P1D");

    final int maxAllowedAppends = 10;
    final int expectedParitionNum = 0;

    String expectedVersion = DateTimes.EPOCH.toString();

    // Allocate, commit, delete, repeat
    for (int i = 0; i < maxAllowedAppends; ++i, expectedVersion += "S") {
      // Allocate a segment and verify its version and partition number
      final String taskAllocatorId = IdUtils.getRandomId();
      final SegmentIdWithShardSpec pendingSegment
          = allocatePendingSegmentForAppendTask(wiki, firstOfJan23, taskAllocatorId);

      Assert.assertNotNull(pendingSegment);
      Assert.assertEquals(expectedVersion, pendingSegment.getVersion());
      Assert.assertEquals(expectedParitionNum, pendingSegment.getShardSpec().getPartitionNum());

      // Commit the segment and verify its version and partition number
      final DataSegment segment = asSegment(pendingSegment);
      coordinator.commitAppendSegments(Set.of(segment), Map.of(), taskAllocatorId, null);

      Assert.assertEquals(expectedVersion, segment.getVersion());
      Assert.assertEquals(expectedParitionNum, segment.getShardSpec().getPartitionNum());

      verifyIntervalHasUsedSegments(wiki, firstOfJan23, segment);
      verifyIntervalHasVisibleSegments(wiki, firstOfJan23, segment);

      // Mark the segment as unused with a future update time to avoid race conditions
      final DateTime markUnusedTime = DateTimes.nowUtc().plusHours(1);
      transactionFactory.inReadWriteDatasourceTransaction(
          wiki,
          t -> t.markAllSegmentsAsUnused(markUnusedTime)
      );
      verifyIntervalHasUsedSegments(wiki, firstOfJan23);
    }

    // Verify that the next attempt fails
    MatcherAssert.assertThat(
        Assert.assertThrows(
            CallbackFailedException.class,
            () -> allocatePendingSegmentForAppendTask(wiki, firstOfJan23, IdUtils.getRandomId())
        ),
        ExceptionMatcher.of(CallbackFailedException.class).expectRootCause(
            DruidExceptionMatcher.internalServerError().expectMessageIs(
                "Could not allocate segment"
                + "[wiki_2023-01-01T00:00:00.000Z_2023-01-02T00:00:00.000Z_1970-01-01T00:00:00.000Z]"
                + " as there are too many clashing unused versions(upto [1970-01-01T00:00:00.000ZSSSSSSSSSS])"
                + " in the interval. Kill the old unused versions to proceed."
            )
        )
    );
  }

  @Test
  public void testDeletePendingSegment() throws InterruptedException
  {
    final PartialShardSpec partialShardSpec = NumberedPartialShardSpec.instance();
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    String prevSegmentId = null;

    final DateTime begin = DateTimes.nowUtc();

    for (int i = 0; i < 10; i++) {
      final SegmentIdWithShardSpec identifier = allocatePendingSegment(
          dataSource,
          "seq",
          prevSegmentId,
          interval,
          partialShardSpec,
          "version",
          false,
          null
      );
      prevSegmentId = identifier.toString();
    }
    Thread.sleep(100);

    final DateTime secondBegin = DateTimes.nowUtc();
    for (int i = 0; i < 5; i++) {
      final SegmentIdWithShardSpec identifier = allocatePendingSegment(
          dataSource,
          "seq",
          prevSegmentId,
          interval,
          partialShardSpec,
          "version",
          false,
          null
      );
      prevSegmentId = identifier.toString();
    }

    final int numDeleted = coordinator.deletePendingSegmentsCreatedInInterval(
        dataSource,
        new Interval(begin, secondBegin)
    );
    Assert.assertEquals(10, numDeleted);
  }

  @Test
  public void testAllocatePendingSegmentsWithOvershadowingSegments()
  {
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");
    String prevSegmentId = null;

    for (int i = 0; i < 10; i++) {
      final SegmentIdWithShardSpec identifier = allocatePendingSegment(
          dataSource,
          "seq",
          prevSegmentId,
          interval,
          new NumberedOverwritePartialShardSpec(0, 1, (short) (i + 1)),
          "version",
          false,
          null
      );
      Assert.assertEquals(
          StringUtils.format(
              "ds_2017-01-01T00:00:00.000Z_2017-02-01T00:00:00.000Z_version%s",
              "_" + (i + PartitionIds.NON_ROOT_GEN_START_PARTITION_ID)
          ),
          identifier.toString()
      );
      prevSegmentId = identifier.toString();
      final Set<DataSegment> toBeAnnounced = Collections.singleton(
          new DataSegment(
              identifier.getDataSource(),
              identifier.getInterval(),
              identifier.getVersion(),
              null,
              Collections.emptyList(),
              Collections.emptyList(),
              ((NumberedOverwriteShardSpec) identifier.getShardSpec()).withAtomicUpdateGroupSize(1),
              0,
              10L
          )
      );
      final Set<DataSegment> announced = coordinator.commitSegments(toBeAnnounced, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

      Assert.assertEquals(toBeAnnounced, announced);
    }

    final Collection<DataSegment> visibleSegments =
        coordinator.retrieveUsedSegmentsForInterval(dataSource, interval, Segments.ONLY_VISIBLE);

    Assert.assertEquals(1, visibleSegments.size());
    Assert.assertEquals(
        new DataSegment(
            dataSource,
            interval,
            "version",
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            new NumberedOverwriteShardSpec(
                9 + PartitionIds.NON_ROOT_GEN_START_PARTITION_ID,
                0,
                1,
                (short) 9,
                (short) 1
            ),
            0,
            10L
        ),
        Iterables.getOnlyElement(visibleSegments)
    );
  }

  @Test
  public void testAllocatePendingSegmentsForHashBasedNumberedShardSpec()
  {
    final PartialShardSpec partialShardSpec = new HashBasedNumberedPartialShardSpec(null, 2, 5, null);
    final String dataSource = "ds";
    final Interval interval = Intervals.of("2017-01-01/2017-02-01");

    SegmentIdWithShardSpec id = allocatePendingSegment(
        dataSource,
        "seq",
        null,
        interval,
        partialShardSpec,
        "version",
        true,
        null
    );

    HashBasedNumberedShardSpec shardSpec = (HashBasedNumberedShardSpec) id.getShardSpec();
    Assert.assertEquals(0, shardSpec.getPartitionNum());
    Assert.assertEquals(0, shardSpec.getNumCorePartitions());
    Assert.assertEquals(5, shardSpec.getNumBuckets());

    coordinator.commitSegments(
        Collections.singleton(
            new DataSegment(
                id.getDataSource(),
                id.getInterval(),
                id.getVersion(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                id.getShardSpec(),
                0,
                10L
            )
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    id = allocatePendingSegment(
        dataSource,
        "seq2",
        null,
        interval,
        partialShardSpec,
        "version",
        true,
        null
    );

    shardSpec = (HashBasedNumberedShardSpec) id.getShardSpec();
    Assert.assertEquals(1, shardSpec.getPartitionNum());
    Assert.assertEquals(0, shardSpec.getNumCorePartitions());
    Assert.assertEquals(5, shardSpec.getNumBuckets());

    coordinator.commitSegments(
        Collections.singleton(
            new DataSegment(
                id.getDataSource(),
                id.getInterval(),
                id.getVersion(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                id.getShardSpec(),
                0,
                10L
            )
        ),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    id = allocatePendingSegment(
        dataSource,
        "seq3",
        null,
        interval,
        new HashBasedNumberedPartialShardSpec(null, 2, 3, null),
        "version",
        true,
        null
    );

    shardSpec = (HashBasedNumberedShardSpec) id.getShardSpec();
    Assert.assertEquals(2, shardSpec.getPartitionNum());
    Assert.assertEquals(0, shardSpec.getNumCorePartitions());
    Assert.assertEquals(3, shardSpec.getNumBuckets());
  }

  @Test
  public void testAddNumberedShardSpecAfterMultiDimensionsShardSpecWithUnknownCorePartitionSize()
  {
    final String datasource = "datasource";
    final Interval interval = Intervals.of("2020-01-01/P1D");
    final String version = "version";
    final List<String> dimensions = ImmutableList.of("dim");
    final List<String> metrics = ImmutableList.of("met");
    final Set<DataSegment> originalSegments = new HashSet<>();
    for (int i = 0; i < 6; i++) {
      originalSegments.add(
          new DataSegment(
              datasource,
              interval,
              version,
              ImmutableMap.of(),
              dimensions,
              metrics,
              new DimensionRangeShardSpec(
                  Collections.singletonList("dim"),
                  i == 0 ? null : StringTuple.create(String.valueOf(i - 1)),
                  i == 5 ? null : StringTuple.create(String.valueOf(i)),
                  i,
                  null // emulate shardSpecs created in older versions of Druid
              ),
              9,
              10L
          )
      );
    }
    coordinator.commitSegments(originalSegments, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    final SegmentIdWithShardSpec id = allocatePendingSegment(
        datasource,
        "seq",
        null,
        interval,
        NumberedPartialShardSpec.instance(),
        version,
        false,
        null
    );
    Assert.assertNull(id);
  }

  @Test
  public void testAddNumberedShardSpecAfterSingleDimensionsShardSpecWithUnknownCorePartitionSize()
  {
    final String datasource = "datasource";
    final Interval interval = Intervals.of("2020-01-01/P1D");
    final String version = "version";
    final List<String> dimensions = ImmutableList.of("dim");
    final List<String> metrics = ImmutableList.of("met");
    final Set<DataSegment> originalSegments = new HashSet<>();
    for (int i = 0; i < 6; i++) {
      final String start = i == 0 ? null : String.valueOf(i - 1);
      final String end = i == 5 ? null : String.valueOf(i);
      originalSegments.add(
          new DataSegment(
              datasource,
              interval,
              version,
              ImmutableMap.of(),
              dimensions,
              metrics,
              new SingleDimensionShardSpec(
                  "dim",
                  start,
                  end,
                  i,
                  null // emulate shardSpecs created in older versions of Druid
              ),
              9,
              10L
          )
      );
    }
    coordinator.commitSegments(originalSegments, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));
    final SegmentIdWithShardSpec id = allocatePendingSegment(
        datasource,
        "seq",
        null,
        interval,
        NumberedPartialShardSpec.instance(),
        version,
        false,
        null
    );
    Assert.assertNull(id);
  }

  @Test
  public void testRemoveDataSourceMetadataOlderThanDatasourceActiveShouldNotBeDeleted()
  {
    coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );

    // Try delete. Datasource should not be deleted as it is in excluded set
    int deletedCount = coordinator.removeDataSourceMetadataOlderThan(
        System.currentTimeMillis(),
        ImmutableSet.of(SUPERVISOR_ID)
    );

    // Datasource should not be deleted
    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );
    Assert.assertEquals(0, deletedCount);
  }

  @Test
  public void testRemoveDataSourceMetadataOlderThanDatasourceNotActiveAndOlderThanTimeShouldBeDeleted()
  {
    coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );

    // Try delete. Datasource should be deleted as it is not in excluded set and created time older than given time
    int deletedCount = coordinator.removeDataSourceMetadataOlderThan(System.currentTimeMillis(), ImmutableSet.of());

    // Datasource should be deleted
    Assert.assertNull(
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );
    Assert.assertEquals(1, deletedCount);
  }

  @Test
  public void testRemoveDataSourceMetadataOlderThanDatasourceNotActiveButNotOlderThanTimeShouldNotBeDeleted()
  {
    coordinator.commitSegmentsAndMetadata(
        ImmutableSet.of(defaultSegment),
        SUPERVISOR_ID,
        new ObjectMetadata(null),
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)
    );

    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );

    // Do delete. Datasource metadata should not be deleted. Datasource is not active but it was created just now so it's
    // created timestamp will be later than the timestamp 2012-01-01T00:00:00Z
    int deletedCount = coordinator.removeDataSourceMetadataOlderThan(
        DateTimes.of("2012-01-01T00:00:00Z").getMillis(),
        ImmutableSet.of()
    );

    // Datasource should not be deleted
    Assert.assertEquals(
        new ObjectMetadata(ImmutableMap.of("foo", "bar")),
        coordinator.retrieveDataSourceMetadata(SUPERVISOR_ID)
    );
    Assert.assertEquals(0, deletedCount);
  }

  @Test
  public void testMarkSegmentsAsUnusedWithinIntervalOneYear()
  {
    coordinator.commitSegments(ImmutableSet.of(existingSegment1, existingSegment2), new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

    // interval covers existingSegment1 and partially overlaps existingSegment2,
    // only existingSegment1 will be dropped
    coordinator.markSegmentsWithinIntervalAsUnused(
        existingSegment1.getDataSource(),
        Intervals.of("1994-01-01/1994-01-02T12Z"),
        null
    );

    Assert.assertEquals(
        ImmutableSet.of(existingSegment1),
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                existingSegment1.getDataSource(),
                existingSegment1.getInterval().withEnd(existingSegment1.getInterval().getEnd().plus(1)),
                null,
                null,
                null
            )
        )
    );
    Assert.assertEquals(
        ImmutableSet.of(),
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                existingSegment2.getDataSource(),
                existingSegment2.getInterval().withEnd(existingSegment2.getInterval().getEnd().plusYears(1)),
                null,
                null
            )
        )
    );
  }

  @Test
  public void testMarkSegmentsAsUnusedWithinIntervalTwoYears()
  {
    coordinator.commitSegments(ImmutableSet.of(existingSegment1, existingSegment2), new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION));

    // interval covers existingSegment1 and partially overlaps existingSegment2,
    // only existingSegment1 will be dropped
    coordinator.markSegmentsWithinIntervalAsUnused(
        existingSegment1.getDataSource(),
        Intervals.of("1993-12-31T12Z/1994-01-02T12Z"),
        null
    );

    Assert.assertEquals(
        ImmutableSet.of(existingSegment1),
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                existingSegment1.getDataSource(),
                existingSegment1.getInterval().withEnd(existingSegment1.getInterval().getEnd().plus(1)),
                null,
                null
            )
        )
    );
    Assert.assertEquals(
        ImmutableSet.of(),
        ImmutableSet.copyOf(
            coordinator.retrieveUnusedSegmentsForInterval(
                existingSegment2.getDataSource(),
                existingSegment2.getInterval().withEnd(existingSegment2.getInterval().getEnd().plusYears(1)),
                null,
                null
            )
        )
    );
  }

  @Test
  public void testRetrieveUsedSegmentsAndCreatedDates()
  {
    coordinator.commitSegments(Set.of(defaultSegment), null);

    List<Pair<DataSegment, String>> resultForIntervalOnTheLeft =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(Intervals.of("2000/2001")));
    Assert.assertTrue(resultForIntervalOnTheLeft.isEmpty());

    List<Pair<DataSegment, String>> resultForIntervalOnTheRight =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(Intervals.of("3000/3001")));
    Assert.assertTrue(resultForIntervalOnTheRight.isEmpty());

    List<Pair<DataSegment, String>> resultForExactInterval =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(defaultSegment.getInterval()));
    Assert.assertEquals(1, resultForExactInterval.size());
    Assert.assertEquals(defaultSegment, resultForExactInterval.get(0).lhs);

    List<Pair<DataSegment, String>> resultForIntervalWithLeftOverlap =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(Intervals.of("2000/2015-01-02")));
    Assert.assertEquals(resultForExactInterval, resultForIntervalWithLeftOverlap);

    List<Pair<DataSegment, String>> resultForIntervalWithRightOverlap =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(Intervals.of("2015-01-01/3000")));
    Assert.assertEquals(resultForExactInterval, resultForIntervalWithRightOverlap);

    List<Pair<DataSegment, String>> resultForEternity =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(Intervals.ETERNITY));
    Assert.assertEquals(resultForExactInterval, resultForEternity);

    List<Pair<DataSegment, String>> resultForFirstHalfEternity =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(firstHalfEternityRangeSegment.getInterval()));
    Assert.assertEquals(resultForExactInterval, resultForFirstHalfEternity);

    List<Pair<DataSegment, String>> resultForSecondHalfEternity =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(secondHalfEternityRangeSegment.getInterval()));
    Assert.assertEquals(resultForExactInterval, resultForSecondHalfEternity);
  }

  @Test
  public void testRetrieveUsedSegmentsAndCreatedDatesFetchesEternityForAnyInterval()
  {
    // Ensure that overlapping segments do not have the same version
    // Otherwise they cannot be added to a timeline
    coordinator.commitSegments(
        Set.of(DataSegment.builder(eternitySegment).version("v1").build()),
        null
    );
    // Commit these segments separately so that the older one is not overshadowed
    coordinator.commitSegments(
        Set.of(
            DataSegment.builder(firstHalfEternityRangeSegment).version("v2").build(),
            DataSegment.builder(secondHalfEternityRangeSegment).version("v3").build()
        ),
        null
    );

    List<Pair<DataSegment, String>> resultForRandomInterval =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(defaultSegment.getInterval()));
    Assert.assertEquals(3, resultForRandomInterval.size());

    List<Pair<DataSegment, String>> resultForEternity =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(eternitySegment.getInterval()));
    Assert.assertEquals(3, resultForEternity.size());

    List<Pair<DataSegment, String>> resultForFirstHalfEternity =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(firstHalfEternityRangeSegment.getInterval()));
    Assert.assertEquals(3, resultForFirstHalfEternity.size());

    List<Pair<DataSegment, String>> resultForSecondHalfEternity =
        coordinator.retrieveUsedSegmentsAndCreatedDates(defaultSegment.getDataSource(), Collections.singletonList(secondHalfEternityRangeSegment.getInterval()));
    Assert.assertEquals(3, resultForSecondHalfEternity.size());
  }

  @Test
  public void testTimelineVisibilityWith0CorePartitionTombstone()
  {
    final Interval interval = Intervals.of("2020/2021");
    // Create and commit a tombstone segment
    final DataSegment tombstoneSegment = createSegment(
        interval,
        "version",
        new TombstoneShardSpec()
    );

    final Set<DataSegment> tombstones = new HashSet<>(Collections.singleton(tombstoneSegment));
    Assert.assertTrue(coordinator.commitSegments(tombstones, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)).containsAll(tombstones));

    // Allocate and commit a data segment by appending to the same interval
    final SegmentIdWithShardSpec identifier = allocatePendingSegment(
        TestDataSource.WIKI,
        "seq",
        tombstoneSegment.getVersion(),
        interval,
        NumberedPartialShardSpec.instance(),
        "version",
        false,
        null
    );

    Assert.assertEquals("wiki_2020-01-01T00:00:00.000Z_2021-01-01T00:00:00.000Z_version_1", identifier.toString());
    Assert.assertEquals(0, identifier.getShardSpec().getNumCorePartitions());

    final DataSegment dataSegment = createSegment(
        interval,
        "version",
        identifier.getShardSpec()
    );
    final Set<DataSegment> dataSegments = new HashSet<>(Collections.singleton(dataSegment));
    Assert.assertTrue(coordinator.commitSegments(dataSegments, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)).containsAll(dataSegments));

    // Mark the tombstone as unused
    markAllSegmentsUnused(tombstones, DateTimes.nowUtc());

    final Collection<DataSegment> allUsedSegments = coordinator.retrieveAllUsedSegments(
        TestDataSource.WIKI,
        Segments.ONLY_VISIBLE
    );

    // The appended data segment will still be visible in the timeline since the
    // tombstone contains 0 core partitions
    SegmentTimeline segmentTimeline = SegmentTimeline.forSegments(allUsedSegments);
    Assert.assertEquals(1, segmentTimeline.lookup(interval).size());
    Assert.assertEquals(dataSegment, segmentTimeline.lookup(interval).get(0).getObject().getChunk(1).getObject());
  }

  @Test
  public void testTimelineWith1CorePartitionTombstone()
  {
    // Register the old generation tombstone spec for this test.
    mapper.registerSubtypes(TombstoneShardSpecWith1CorePartition.class);

    final Interval interval = Intervals.of("2020/2021");
    // Create and commit an old generation tombstone with 1 core partition
    final DataSegment tombstoneSegment = createSegment(
        interval,
        "version",
        new TombstoneShardSpecWith1CorePartition()
    );

    final Set<DataSegment> tombstones = new HashSet<>(Collections.singleton(tombstoneSegment));
    Assert.assertTrue(coordinator.commitSegments(tombstones, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)).containsAll(tombstones));

    // Allocate and commit a data segment by appending to the same interval
    final SegmentIdWithShardSpec identifier = allocatePendingSegment(
        TestDataSource.WIKI,
        "seq",
        tombstoneSegment.getVersion(),
        interval,
        NumberedPartialShardSpec.instance(),
        "version",
        false,
        null
    );

    Assert.assertEquals("wiki_2020-01-01T00:00:00.000Z_2021-01-01T00:00:00.000Z_version_1", identifier.toString());
    Assert.assertEquals(1, identifier.getShardSpec().getNumCorePartitions());

    final DataSegment dataSegment = createSegment(
        interval,
        "version",
        identifier.getShardSpec()
    );
    final Set<DataSegment> dataSegments = new HashSet<>(Collections.singleton(dataSegment));
    Assert.assertTrue(coordinator.commitSegments(dataSegments, new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION)).containsAll(dataSegments));

    // Mark the tombstone as unused
    coordinator.markSegmentAsUnused(tombstoneSegment.getId());

    final Collection<DataSegment> allUsedSegments = coordinator.retrieveAllUsedSegments(
        TestDataSource.WIKI,
        Segments.ONLY_VISIBLE
    );

    // The appended data segment will not be visible in the timeline since the old generation
    // tombstone contains 1 core partition
    SegmentTimeline segmentTimeline = SegmentTimeline.forSegments(allUsedSegments);
    Assert.assertEquals(0, segmentTimeline.lookup(interval).size());
  }

  @Test
  public void testSegmentIdShouldNotBeReallocated()
  {
    final SegmentIdWithShardSpec idWithNullTaskAllocator = allocatePendingSegment(
        TestDataSource.WIKI,
        "seq",
        "0",
        Intervals.ETERNITY,
        NumberedPartialShardSpec.instance(),
        "version",
        false,
        null
    );
    final DataSegment dataSegment0 = createSegment(
        idWithNullTaskAllocator.getInterval(),
        idWithNullTaskAllocator.getVersion(),
        idWithNullTaskAllocator.getShardSpec()
    );

    final SegmentIdWithShardSpec idWithValidTaskAllocator = allocatePendingSegment(
        TestDataSource.WIKI,
        "seq",
        "1",
        Intervals.ETERNITY,
        NumberedPartialShardSpec.instance(),
        "version",
        false,
        "taskAllocatorId"
    );
    final DataSegment dataSegment1 = createSegment(
        idWithValidTaskAllocator.getInterval(),
        idWithValidTaskAllocator.getVersion(),
        idWithValidTaskAllocator.getShardSpec()
    );

    // Insert pending segments
    coordinator.commitSegments(ImmutableSet.of(dataSegment0, dataSegment1), null);
    // Clean up pending segments corresponding to the valid task allocator id
    coordinator.deletePendingSegmentsForTaskAllocatorId(TestDataSource.WIKI, "taskAllocatorId");
    // Mark all segments as unused
    coordinator.markSegmentsWithinIntervalAsUnused(TestDataSource.WIKI, Intervals.ETERNITY, null);

    final SegmentIdWithShardSpec theId = allocatePendingSegment(
        TestDataSource.WIKI,
        "seq",
        "2",
        Intervals.ETERNITY,
        NumberedPartialShardSpec.instance(),
        "version",
        false,
        "taskAllocatorId"
    );
    Assert.assertNull(coordinator.retrieveSegmentForId(theId.asSegmentId()));
  }

  @Test
  public void testRetrieveUnusedSegmentsForExactIntervalAndVersion()
  {
    DataSegment unusedForDifferentVersion = createSegment(
        Intervals.of("2024/2025"),
        "v0",
        new NumberedShardSpec(0, 0)
    );
    DataSegment unusedSegmentForExactIntervalAndVersion = createSegment(
        Intervals.of("2024/2025"),
        "v1",
        new NumberedShardSpec(0, 0)
    );
    DataSegment unusedSegmentForDifferentInterval = createSegment(
        Intervals.of("2023/2024"),
        "v1",
        new NumberedShardSpec(0, 0)
    );
    coordinator.commitSegments(
        ImmutableSet.of(
            unusedForDifferentVersion,
            unusedSegmentForDifferentInterval,
            unusedSegmentForExactIntervalAndVersion
        ),
        null
    );
    coordinator.markSegmentsWithinIntervalAsUnused(TestDataSource.WIKI, Intervals.ETERNITY, null);

    DataSegment usedSegmentForExactIntervalAndVersion = createSegment(
        Intervals.of("2024/2025"),
        "v1",
        new NumberedShardSpec(1, 0)
    );
    coordinator.commitSegments(ImmutableSet.of(usedSegmentForExactIntervalAndVersion), null);


    SegmentId highestUnusedId = transactionFactory.inReadWriteDatasourceTransaction(
        TestDataSource.WIKI,
        transaction -> transaction.noCacheSql().retrieveHighestUnusedSegmentId(
            TestDataSource.WIKI,
            Intervals.of("2024/2025"),
            "v1"
        )
    );
    Assert.assertEquals(
        unusedSegmentForExactIntervalAndVersion.getId(),
        highestUnusedId
    );
  }

  @Test
  public void testRetrieveUpgradedFromSegmentIds()
  {
    final String datasource = defaultSegment.getDataSource();
    final Map<String, String> upgradedFromSegmentIdMap = new HashMap<>();
    upgradedFromSegmentIdMap.put(defaultSegment2.getId().toString(), defaultSegment.getId().toString());
    insertUsedSegments(ImmutableSet.of(defaultSegment, defaultSegment2), upgradedFromSegmentIdMap);
    coordinator.markSegmentsWithinIntervalAsUnused(datasource, Intervals.ETERNITY, null);
    upgradedFromSegmentIdMap.clear();
    upgradedFromSegmentIdMap.put(defaultSegment3.getId().toString(), defaultSegment.getId().toString());
    insertUsedSegments(ImmutableSet.of(defaultSegment3, defaultSegment4), upgradedFromSegmentIdMap);

    Map<String, String> expected = new HashMap<>();
    expected.put(defaultSegment2.getId().toString(), defaultSegment.getId().toString());
    expected.put(defaultSegment3.getId().toString(), defaultSegment.getId().toString());

    Set<String> segmentIds = new HashSet<>();
    segmentIds.add(defaultSegment.getId().toString());
    segmentIds.add(defaultSegment2.getId().toString());
    segmentIds.add(defaultSegment3.getId().toString());
    segmentIds.add(defaultSegment4.getId().toString());
    Assert.assertEquals(
        expected,
        coordinator.retrieveUpgradedFromSegmentIds(datasource, segmentIds)
    );
  }

  @Test
  public void testRetrieveUpgradedFromSegmentIdsInBatches()
  {
    Assume.assumeFalse(isCacheEnabled());

    final int size = 500;
    final int batchSize = 100;

    List<DataSegment> segments = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      segments.add(
          new DataSegment(
              "DS",
              Intervals.ETERNITY,
              "v " + (i % 5),
              ImmutableMap.of("num", i / 5),
              ImmutableList.of("dim"),
              ImmutableList.of("agg"),
              new NumberedShardSpec(i / 5, 0),
              0,
              100L
          )
      );
    }
    Map<String, String> expected = new HashMap<>();
    for (int i = 0; i < batchSize; i++) {
      for (int j = 1; j < 5; j++) {
        expected.put(
            segments.get(5 * i + j).getId().toString(),
            segments.get(5 * i).getId().toString()
        );
      }
    }
    insertUsedSegments(ImmutableSet.copyOf(segments), expected);

    Map<String, String> actual = coordinator.retrieveUpgradedFromSegmentIds(
        "DS",
        segments.stream().map(DataSegment::getId).map(SegmentId::toString).collect(Collectors.toSet())
    );

    Assert.assertEquals(400, actual.size());
    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testRetrieveUpgradedToSegmentIds()
  {
    final String datasource = defaultSegment.getDataSource();
    final Map<String, String> upgradedFromSegmentIdMap = new HashMap<>();
    upgradedFromSegmentIdMap.put(defaultSegment2.getId().toString(), defaultSegment.getId().toString());
    insertUsedSegments(ImmutableSet.of(defaultSegment, defaultSegment2), upgradedFromSegmentIdMap);
    coordinator.markSegmentsWithinIntervalAsUnused(datasource, Intervals.ETERNITY, null);
    upgradedFromSegmentIdMap.clear();
    upgradedFromSegmentIdMap.put(defaultSegment3.getId().toString(), defaultSegment.getId().toString());
    insertUsedSegments(ImmutableSet.of(defaultSegment3, defaultSegment4), upgradedFromSegmentIdMap);

    Map<String, Set<String>> expected = new HashMap<>();
    expected.put(defaultSegment.getId().toString(), new HashSet<>());
    expected.get(defaultSegment.getId().toString()).add(defaultSegment.getId().toString());
    expected.get(defaultSegment.getId().toString()).add(defaultSegment2.getId().toString());
    expected.get(defaultSegment.getId().toString()).add(defaultSegment3.getId().toString());

    Set<String> upgradedIds = new HashSet<>();
    upgradedIds.add(defaultSegment.getId().toString());
    Assert.assertEquals(
        expected,
        coordinator.retrieveUpgradedToSegmentIds(datasource, upgradedIds)
    );
  }

  @Test
  public void testRetrieveUpgradedToSegmentIdsInBatches()
  {
    final int size = 500;
    final int batchSize = 100;

    List<DataSegment> segments = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      segments.add(
          new DataSegment(
              "DS",
              Intervals.ETERNITY,
              "v " + (i % 5),
              ImmutableMap.of("num", i / 5),
              ImmutableList.of("dim"),
              ImmutableList.of("agg"),
              new NumberedShardSpec(i / 5, 0),
              0,
              100L
          )
      );
    }

    Map<String, Set<String>> expected = new HashMap<>();
    for (DataSegment segment : segments) {
      final String id = segment.getId().toString();
      expected.put(id, new HashSet<>());
      expected.get(id).add(id);
    }
    Map<String, String> upgradeMap = new HashMap<>();
    for (int i = 0; i < batchSize; i++) {
      for (int j = 1; j < 5; j++) {
        upgradeMap.put(
            segments.get(5 * i + j).getId().toString(),
            segments.get(5 * i).getId().toString()
        );
        expected.get(segments.get(5 * i).getId().toString())
                .add(segments.get(5 * i + j).getId().toString());
      }
    }
    insertUsedSegments(ImmutableSet.copyOf(segments), upgradeMap);

    Map<String, Set<String>> actual = coordinator.retrieveUpgradedToSegmentIds(
        "DS",
        segments.stream().map(DataSegment::getId).map(SegmentId::toString).collect(Collectors.toSet())
    );

    Assert.assertEquals(500, actual.size());
    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testRetrieveUsedSegmentsForSegmentAllocation()
  {
    final String datasource = "DS";
    DataSegment firstSegment;
    Set<DataSegment> nextSegments;
    final Map<String, Object> loadspec = ImmutableMap.of("loadSpec", "loadSpec");
    final List<String> dimensions = ImmutableList.of("dim1", "dim2");
    final List<String> metrics = ImmutableList.of("metric1", "metric2");
    final int numSegmentsPerInterval = 100;

    final Interval month = Intervals.of("2024-10-01/2024-11-01");

    final Interval year = Intervals.of("2024/2025");

    final Interval overlappingDay = Intervals.of("2024-10-01/2024-10-02");
    final Interval nonOverlappingDay = Intervals.of("2024-01-01/2024-01-02");

    final List<Interval> intervals = ImmutableList.of(month, year, overlappingDay, nonOverlappingDay);
    final List<String> versions = ImmutableList.of("v0", "v1", "v2", "v2");
    for (int i = 0; i < 4; i++) {
      nextSegments = new HashSet<>();
      firstSegment = new DataSegment(
          datasource,
          intervals.get(i),
          versions.get(i),
          loadspec,
          dimensions,
          metrics,
          new DimensionRangeShardSpec(dimensions, null, null, 0, 1),
          0,
          100
      );
      coordinator.commitSegments(Set.of(firstSegment), null);
      for (int j = 1; j < numSegmentsPerInterval; j++) {
        nextSegments.add(
            new DataSegment(
                datasource,
                intervals.get(i),
                versions.get(i),
                loadspec,
                dimensions,
                metrics,
                // The numCorePartitions is intentionally 0
                new NumberedShardSpec(j, 0),
                0,
                100
            )
        );
      }
      coordinator.commitSegments(nextSegments, null);
    }

    final Set<SegmentIdWithShardSpec> expected = new HashSet<>();
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < numSegmentsPerInterval; j++) {
        expected.add(
            new SegmentIdWithShardSpec(
                datasource,
                intervals.get(i),
                versions.get(i),
                new NumberedShardSpec(j, 1)
            )
        );
      }
    }

    Set<SegmentIdWithShardSpec> observed = transactionFactory.inReadOnlyDatasourceTransaction(
        datasource,
        transaction ->
            coordinator.retrieveUsedSegmentsForAllocation(transaction, datasource, month)
                       .stream()
                       .map(SegmentIdWithShardSpec::fromDataSegment)
                       .collect(Collectors.toSet())
    );

    Assert.assertEquals(expected, observed);
  }

  @Test
  public void testCachedTransaction_cannotReadWhatItWrites()
  {
    Assume.assumeTrue(isCacheEnabled());

    transactionFactory.inReadWriteDatasourceTransaction(
        TestDataSource.WIKI,
        transaction -> {
          final DataSegmentPlus wikiSegment =
              CreateDataSegments.ofDatasource(TestDataSource.WIKI).updatedNow().markUsed().asPlus();
          Assert.assertEquals(1, transaction.insertSegments(Set.of(wikiSegment)));

          // Verify that segment is not present in cache
          Assert.assertNull(transaction.findUsedSegment(wikiSegment.getDataSegment().getId()));

          // Verify that segment is present in metadata store
          Assert.assertEquals(
              wikiSegment.getDataSegment(),
              transaction.findSegment(wikiSegment.getDataSegment().getId())
          );

          return 0;
        }
    );

    emitter.verifyValue(Metric.READ_WRITE_TRANSACTIONS, 1L);
  }

  @Test
  public void testReadOperation_usesCache_ifSynced()
  {
    Assume.assumeTrue(isCacheEnabled());

    Assert.assertTrue(segmentMetadataCache.isSyncedForRead());

    insertUsedSegments(Set.of(defaultSegment), Map.of());
    final Supplier<Set<DataSegment>> retrieveAction =
        () -> coordinator.retrieveAllUsedSegments(
            defaultSegment.getDataSource(),
            Segments.INCLUDING_OVERSHADOWED
        );

    // Retrieve returns empty since cache is not synced with metadata store yet
    Assert.assertTrue(retrieveAction.get().isEmpty());

    refreshCache();
    Assert.assertEquals(Set.of(defaultSegment), retrieveAction.get());

    emitter.verifyEmitted(Metric.READ_ONLY_TRANSACTIONS, 2);
  }

  @Test
  public void testReadOperation_doesNotUseCache_ifNotSynced()
  {
    Assume.assumeTrue(isCacheEnabled());

    segmentMetadataCache.stopBeingLeader();
    Assert.assertFalse(segmentMetadataCache.isSyncedForRead());

    final Supplier<Set<DataSegment>> retrieveAction =
        () -> coordinator.retrieveAllUsedSegments(
            defaultSegment.getDataSource(),
            Segments.INCLUDING_OVERSHADOWED
        );

    insertUsedSegments(Set.of(defaultSegment), Map.of());

    Assert.assertEquals(Set.of(defaultSegment), retrieveAction.get());
    emitter.verifyNotEmitted(Metric.READ_ONLY_TRANSACTIONS);

    // Become leader but cache will still not be used
    segmentMetadataCache.becomeLeader();
    Assert.assertFalse(segmentMetadataCache.isSyncedForRead());
    Assert.assertEquals(Set.of(defaultSegment), retrieveAction.get());
    emitter.verifyNotEmitted(Metric.READ_ONLY_TRANSACTIONS);

    // Sync the cache so that it becomes ready for use
    refreshCache();
    refreshCache();
    Assert.assertTrue(segmentMetadataCache.isSyncedForRead());
    Assert.assertEquals(Set.of(defaultSegment), retrieveAction.get());
    emitter.verifyValue(Metric.READ_ONLY_TRANSACTIONS, 1L);
  }

  @Test
  public void testWriteOperation_alwaysUsesCache_inModeIfSynced()
  {
    Assume.assumeTrue(cacheMode == SegmentMetadataCache.UsageMode.IF_SYNCED);

    // Lose and regain leadership
    segmentMetadataCache.stopBeingLeader();
    segmentMetadataCache.becomeLeader();

    Assert.assertTrue(segmentMetadataCache.isEnabled());
    Assert.assertFalse(segmentMetadataCache.isSyncedForRead());

    final Supplier<Set<DataSegment>> writeAction =
        () -> coordinator.commitSegments(Set.of(defaultSegment), null);

    // Cache is not synced yet and will be used only for write operations
    Assert.assertEquals(Set.of(defaultSegment), writeAction.get());
    emitter.verifyValue(Metric.WRITE_ONLY_TRANSACTIONS, 1L);

    // Sync the cache to use it for both read and write operations
    refreshCache();
    refreshCache();
    Assert.assertTrue(segmentMetadataCache.isSyncedForRead());

    Assert.assertTrue(writeAction.get().isEmpty());
    emitter.verifyValue(Metric.READ_WRITE_TRANSACTIONS, 1L);
  }

  private SegmentIdWithShardSpec allocatePendingSegment(
      String datasource,
      String sequenceName,
      String previousSegmentId,
      Interval interval,
      PartialShardSpec partialShardSpec,
      String maxVersion,
      boolean skipSegmentLineageCheck,
      String taskAllocatorId
  )
  {
    return coordinator.allocatePendingSegment(
        datasource,
        interval,
        skipSegmentLineageCheck,
        new SegmentCreateRequest(
            sequenceName,
            previousSegmentId,
            maxVersion,
            partialShardSpec,
            taskAllocatorId
        )
    );
  }

  private SegmentIdWithShardSpec allocatePendingSegmentForAppendTask(
      String dataSource,
      Interval interval,
      String taskAllocatorId
  )
  {
    return coordinator.allocatePendingSegment(
        dataSource,
        interval,
        true,
        new SegmentCreateRequest(
            IdUtils.getRandomId(),
            null,
            PendingSegmentRecord.DEFAULT_VERSION_FOR_CONCURRENT_APPEND,
            NumberedPartialShardSpec.instance(),
            taskAllocatorId
        )
    );
  }

  private int insertPendingSegments(
      String dataSource,
      List<PendingSegmentRecord> pendingSegments,
      boolean skipLineageCheck
  )
  {
    return transactionFactory.inReadWriteDatasourceTransaction(
        dataSource,
        transaction -> transaction.insertPendingSegments(pendingSegments, skipLineageCheck)
    );
  }

  private void insertUsedSegments(Set<DataSegment> segments, Map<String, String> upgradedFromSegmentIdMap)
  {
    insertUsedSegments(segments, upgradedFromSegmentIdMap, derbyConnectorRule, mapper);
  }

  private static DataSegment asSegment(SegmentIdWithShardSpec pendingSegment)
  {
    final SegmentId id = pendingSegment.asSegmentId();
    return DataSegment.builder(id)
                      .shardSpec(pendingSegment.getShardSpec())
                      .loadSpec(Map.of(id.toString(), id.toString()))
                      .build();
  }

  private void verifyIntervalHasUsedSegments(
      String dataSource,
      Interval interval,
      DataSegment... expectedSegments
  )
  {
    Assert.assertEquals(
        Set.of(expectedSegments),
        coordinator.retrieveUsedSegmentsForIntervals(dataSource, List.of(interval), Segments.INCLUDING_OVERSHADOWED)
    );
  }

  private void verifyIntervalHasVisibleSegments(
      String dataSource,
      Interval interval,
      DataSegment... expectedSegments
  )
  {
    Assert.assertEquals(
        Set.of(expectedSegments),
        coordinator.retrieveUsedSegmentsForIntervals(dataSource, List.of(interval), Segments.ONLY_VISIBLE)
    );
  }
}
