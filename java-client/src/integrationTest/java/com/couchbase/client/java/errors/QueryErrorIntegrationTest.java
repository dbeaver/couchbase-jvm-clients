/*
 * Copyright (c) 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java.errors;

import com.couchbase.client.core.error.InvalidArgumentException;
import com.couchbase.client.core.error.ParsingFailureException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.util.JavaIntegrationTest;
import com.couchbase.client.test.Capabilities;
import com.couchbase.client.test.ClusterType;
import com.couchbase.client.test.IgnoreWhen;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IgnoreWhen( missesCapabilities = { Capabilities.QUERY, Capabilities.CLUSTER_LEVEL_QUERY })
class QueryErrorIntegrationTest extends JavaIntegrationTest {

  static private Cluster cluster;

  @BeforeAll
  static void beforeAll() {
    cluster = createCluster();
    Bucket bucket = cluster.bucket(config().bucketname());
    bucket.waitUntilReady(WAIT_UNTIL_READY_DEFAULT);
  }

  @AfterAll
  static void afterAll() {
    cluster.disconnect();
  }

  @Test
  void verifyInvalidArguments() {
    assertThrows(InvalidArgumentException.class, () -> cluster.query(null));
    assertThrows(InvalidArgumentException.class, () -> cluster.query("foo", null));
  }

  @Test
  @IgnoreWhen(clusterTypes = ClusterType.CAVES, isProtostellar = true)
  void includesHttpStatusCodeInErrorContext() {
    ParsingFailureException ex = assertThrows(ParsingFailureException.class, () -> cluster.query("select 1="));
    assertTrue(ex.getMessage().contains("\"httpStatus\":400"));
  }

}
