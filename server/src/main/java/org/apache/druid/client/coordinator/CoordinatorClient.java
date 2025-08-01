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

package org.apache.druid.client.coordinator;

import com.google.common.util.concurrent.ListenableFuture;
import org.apache.druid.client.BootstrapSegmentsResponse;
import org.apache.druid.client.ImmutableSegmentLoadInfo;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.lookup.LookupExtractorFactoryContainer;
import org.apache.druid.rpc.ServiceRetryPolicy;
import org.apache.druid.segment.metadata.DataSourceInformation;
import org.apache.druid.server.compaction.CompactionStatusResponse;
import org.apache.druid.server.coordinator.CoordinatorDynamicConfig;
import org.apache.druid.server.coordinator.rules.Rule;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentStatusInCluster;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface CoordinatorClient
{
  /**
   * Checks if the given segment is handed off or not.
   */
  ListenableFuture<Boolean> isHandoffComplete(String dataSource, SegmentDescriptor descriptor);

  /**
   * Fetches segment metadata for the given dataSource and segmentId. If includeUnused is set to false, the segment is
   * not returned if it is marked as unused.
   */
  ListenableFuture<DataSegment> fetchSegment(String dataSource, String segmentId, boolean includeUnused);

  /**
   * Fetches segments from the coordinator server view for the given dataSource and intervals.
   */
  Iterable<ImmutableSegmentLoadInfo> fetchServerViewSegments(String dataSource, List<Interval> intervals);

  /**
   * Fetches segment metadata for the given dataSource and intervals.
   */
  ListenableFuture<List<DataSegment>> fetchUsedSegments(String dataSource, List<Interval> intervals);

  /**
   * Retrieves detailed metadata information for the specified data sources, which includes {@code RowSignature}.
   */
  ListenableFuture<List<DataSourceInformation>> fetchDataSourceInformation(Set<String> datasources);

  /**
   * Fetch bootstrap segments from the coordinator. The results must be streamed back to the caller as the
   * result set can be large.
   */
  ListenableFuture<BootstrapSegmentsResponse> fetchBootstrapSegments();

  /**
   * Returns a new instance backed by a ServiceClient which follows the provided retryPolicy
   */
  CoordinatorClient withRetryPolicy(ServiceRetryPolicy retryPolicy);

  /**
   * Retrieves list of datasources with used segments.
   */
  ListenableFuture<Set<String>> fetchDataSourcesWithUsedSegments();

  /**
   * Gets the latest compaction snapshots of one or all datasources.
   * <p>
   * API: {@code GET /druid/coordinator/v1/compaction/status}
   *
   * @param dataSource If passed as non-null, then the returned list contains only
   *                   the snapshot for this datasource.
   */
  ListenableFuture<CompactionStatusResponse> getCompactionSnapshots(@Nullable String dataSource);

  /**
   * Gets the latest coordinator dynamic config.
   * <p>
   * API: {@code GET /druid/coordinator/v1/config}
   */
  ListenableFuture<CoordinatorDynamicConfig> getCoordinatorDynamicConfig();

  /**
   * Updates the Coordinator dynamic config.
   * <p>
   * API: {@code POST /druid/coordinator/v1/config}
   */
  ListenableFuture<Void> updateCoordinatorDynamicConfig(CoordinatorDynamicConfig dynamicConfig);

  /**
   * Updates lookups for all tiers.
   * <p>
   * API: {@code POST /druid/coordinator/v1/lookups/config}
   */
  ListenableFuture<Void> updateAllLookups(Object lookups);

  /**
   * Gets the lookup configuration for a tier synchronously.
   * <p>
   * API: {@code GET /druid/coordinator/v1/lookups/config/<tier>}
   *
   * @param tier The name of the tier for which the lookup configuration is to be fetched.
   */
  Map<String, LookupExtractorFactoryContainer> fetchLookupsForTierSync(String tier);

  /**
   * Returns an iterator over the metadata segments of multiple datasources in the cluster, fetching them in one go.
   * <p>
   * API: {@code GET /druid/coordinator/v1/metadata/segments?includeOvershadowedStatus}
   *
   * @param watchedDataSources Optional datasources to filter the segments by. If null or empty, all segments are returned.
   * @param includeRealtimeSegments If true, includes realtime segments in the result.
   */
  ListenableFuture<CloseableIterator<SegmentStatusInCluster>> fetchAllUsedSegmentsWithOvershadowedStatus(
      @Nullable Set<String> watchedDataSources,
      boolean includeRealtimeSegments
  );

  /**
   * Returns the current snapshot of the rules.
   * <p>
   * API: {@code GET /druid/coordinator/v1/rules}
   */
  ListenableFuture<Map<String, List<Rule>>> getRulesForAllDatasources();

  /**
   * Returns the current coordinator leader's URI.
   * <p>
   * API: {@code GET /druid/coordinator/v1/leader}
   */
  ListenableFuture<URI> findCurrentLeader();

  /**
   * Posts load rules to the coordinator.
   * <p>
   * API: {@code POST /druid/coordinator/v1/rules}
   */
  ListenableFuture<Void> updateRulesForDatasource(String dataSource, List<Rule> rules);
}
