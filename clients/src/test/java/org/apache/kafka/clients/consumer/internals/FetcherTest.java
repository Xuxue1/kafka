/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.MockClient;
import org.apache.kafka.clients.NetworkClient;
import org.apache.kafka.clients.NodeApiVersions;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.ControlRecordType;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.record.DefaultRecordBatch;
import org.apache.kafka.common.record.EndTransactionMarker;
import org.apache.kafka.common.record.LegacyRecord;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MemoryRecordsBuilder;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.requests.IsolationLevel;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.ApiVersionsResponse;
import org.apache.kafka.common.requests.FetchRequest;
import org.apache.kafka.common.requests.FetchResponse;
import org.apache.kafka.common.requests.ListOffsetRequest;
import org.apache.kafka.common.requests.ListOffsetResponse;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.ResponseHeader;
import org.apache.kafka.common.requests.FetchRequest.PartitionData;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.test.DelayedReceive;
import org.apache.kafka.test.MockSelector;
import org.apache.kafka.test.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singleton;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FetcherTest {
    private ConsumerRebalanceListener listener = new NoOpConsumerRebalanceListener();
    private String topicName = "test";
    private String groupId = "test-group";
    private final String metricGroup = "consumer" + groupId + "-fetch-manager-metrics";
    private TopicPartition tp1 = new TopicPartition(topicName, 0);
    private TopicPartition tp2 = new TopicPartition(topicName, 1);
    private int minBytes = 1;
    private int maxBytes = Integer.MAX_VALUE;
    private int maxWaitMs = 0;
    private int fetchSize = 1000;
    private long retryBackoffMs = 100;
    private MockTime time = new MockTime(1);
    private Metadata metadata = new Metadata(0, Long.MAX_VALUE, true);
    private MockClient client = new MockClient(time, metadata);
    private Cluster cluster = TestUtils.singletonCluster(topicName, 2);
    private Node node = cluster.nodes().get(0);
    private Metrics metrics = new Metrics(time);
    FetcherMetricsRegistry metricsRegistry = new FetcherMetricsRegistry("consumer" + groupId);

    private SubscriptionState subscriptions = new SubscriptionState(OffsetResetStrategy.EARLIEST);
    private SubscriptionState subscriptionsNoAutoReset = new SubscriptionState(OffsetResetStrategy.NONE);
    private static final double EPSILON = 0.0001;
    private ConsumerNetworkClient consumerClient = new ConsumerNetworkClient(client, metadata, time, 100, 1000);

    private MemoryRecords records;
    private MemoryRecords nextRecords;
    private Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, metrics);
    private Metrics fetcherMetrics = new Metrics(time);
    private Fetcher<byte[], byte[]> fetcherNoAutoReset = createFetcher(subscriptionsNoAutoReset, fetcherMetrics);

    @Before
    public void setup() throws Exception {
        metadata.update(cluster, Collections.<String>emptySet(), time.milliseconds());
        client.setNode(node);

        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE, TimestampType.CREATE_TIME, 1L);
        builder.append(0L, "key".getBytes(), "value-1".getBytes());
        builder.append(0L, "key".getBytes(), "value-2".getBytes());
        builder.append(0L, "key".getBytes(), "value-3".getBytes());
        records = builder.build();

        builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE, TimestampType.CREATE_TIME, 4L);
        builder.append(0L, "key".getBytes(), "value-4".getBytes());
        builder.append(0L, "key".getBytes(), "value-5".getBytes());
        nextRecords = builder.build();
    }

    @After
    public void teardown() {
        this.metrics.close();
        this.fetcherMetrics.close();
        this.fetcher.close();
        this.fetcherMetrics.close();
    }

    @Test
    public void testFetchNormal() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        client.prepareResponse(fetchResponse(this.records, Errors.NONE, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> partitionRecords = fetcher.fetchedRecords();
        assertTrue(partitionRecords.containsKey(tp1));

        List<ConsumerRecord<byte[], byte[]>> records = partitionRecords.get(tp1);
        assertEquals(3, records.size());
        assertEquals(4L, subscriptions.position(tp1).longValue()); // this is the next fetching position
        long offset = 1;
        for (ConsumerRecord<byte[], byte[]> record : records) {
            assertEquals(offset, record.offset());
            offset += 1;
        }
    }

    @Test
    public void testFetcherIgnoresControlRecords() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        long producerId = 1;
        short producerEpoch = 0;
        int baseSequence = 0;
        int partitionLeaderEpoch = 0;

        ByteBuffer buffer = ByteBuffer.allocate(1024);
        MemoryRecordsBuilder builder = MemoryRecords.idempotentBuilder(buffer, CompressionType.NONE, 0L, producerId,
                producerEpoch, baseSequence);
        builder.append(0L, "key".getBytes(), null);
        builder.close();

        MemoryRecords.writeEndTransactionalMarker(buffer, 1L, time.milliseconds(), partitionLeaderEpoch, producerId, producerEpoch,
                new EndTransactionMarker(ControlRecordType.ABORT, 0));

        buffer.flip();

        client.prepareResponse(fetchResponse(MemoryRecords.readableRecords(buffer), Errors.NONE, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> partitionRecords = fetcher.fetchedRecords();
        assertTrue(partitionRecords.containsKey(tp1));

        List<ConsumerRecord<byte[], byte[]>> records = partitionRecords.get(tp1);
        assertEquals(1, records.size());
        assertEquals(2L, subscriptions.position(tp1).longValue());

        ConsumerRecord<byte[], byte[]> record = records.get(0);
        assertArrayEquals("key".getBytes(), record.key());
    }

    @Test
    public void testFetchError() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        client.prepareResponse(fetchResponse(this.records, Errors.NOT_LEADER_FOR_PARTITION, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> partitionRecords = fetcher.fetchedRecords();
        assertFalse(partitionRecords.containsKey(tp1));
    }

    private MockClient.RequestMatcher matchesOffset(final TopicPartition tp, final long offset) {
        return new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                FetchRequest fetch = (FetchRequest) body;
                return fetch.fetchData().containsKey(tp) &&
                        fetch.fetchData().get(tp).fetchOffset == offset;
            }
        };
    }

    @Test
    public void testFetchedRecordsRaisesOnSerializationErrors() {
        // raise an exception from somewhere in the middle of the fetch response
        // so that we can verify that our position does not advance after raising
        ByteArrayDeserializer deserializer = new ByteArrayDeserializer() {
            int i = 0;
            @Override
            public byte[] deserialize(String topic, byte[] data) {
                if (i++ % 2 == 1) {
                    // Should be blocked on the value deserialization of the first record.
                    assertEquals(new String(data, StandardCharsets.UTF_8), "value-1");
                    throw new SerializationException();
                }
                return data;
            }
        };

        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(time), deserializer, deserializer);

        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 1);

        client.prepareResponse(matchesOffset(tp1, 1), fetchResponse(this.records, Errors.NONE, 100L, 0));

        assertEquals(1, fetcher.sendFetches());
        consumerClient.poll(0);
        // The fetcher should block on Deserialization error
        for (int i = 0; i < 2; i++) {
            try {
                fetcher.fetchedRecords();
                fail("fetchedRecords should have raised");
            } catch (SerializationException e) {
                // the position should not advance since no data has been returned
                assertEquals(1, subscriptions.position(tp1).longValue());
            }
        }
    }

    @Test
    public void testParseInvalidRecord() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        DataOutputStream out = new DataOutputStream(new ByteBufferOutputStream(buffer));

        byte magic = RecordBatch.MAGIC_VALUE_V1;
        byte[] key = "foo".getBytes();
        byte[] value = "baz".getBytes();
        long offset = 0;
        long timestamp = 500L;

        int size = LegacyRecord.recordSize(magic, key.length, value.length);
        byte attributes = LegacyRecord.computeAttributes(magic, CompressionType.NONE, TimestampType.CREATE_TIME);
        long crc = LegacyRecord.computeChecksum(magic, attributes, timestamp, key, value);

        // write one valid record
        out.writeLong(offset);
        out.writeInt(size);
        LegacyRecord.write(out, magic, crc, LegacyRecord.computeAttributes(magic, CompressionType.NONE, TimestampType.CREATE_TIME), timestamp, key, value);

        // and one invalid record (note the crc)
        out.writeLong(offset + 1);
        out.writeInt(size);
        LegacyRecord.write(out, magic, crc + 1, LegacyRecord.computeAttributes(magic, CompressionType.NONE, TimestampType.CREATE_TIME), timestamp, key, value);

        // write one valid record
        out.writeLong(offset + 2);
        out.writeInt(size);
        LegacyRecord.write(out, magic, crc, LegacyRecord.computeAttributes(magic, CompressionType.NONE, TimestampType.CREATE_TIME), timestamp, key, value);

        buffer.flip();

        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(MemoryRecords.readableRecords(buffer), Errors.NONE, 100L, 0));
        consumerClient.poll(0);

        // the first fetchedRecords() should return the first valid message
        assertEquals(1, fetcher.fetchedRecords().get(tp1).size());
        assertEquals(1, subscriptions.position(tp1).longValue());

        // the fetchedRecords() should always throw exception due to the second invalid message
        for (int i = 0; i < 2; i++) {
            try {
                fetcher.fetchedRecords();
                fail("fetchedRecords should have raised KafkaException");
            } catch (KafkaException e) {
                assertEquals(1, subscriptions.position(tp1).longValue());
            }
        }

        // Seek to skip the bad record and fetch again.
        subscriptions.seek(tp1, 2);
        // Should not throw exception after the seek.
        fetcher.fetchedRecords();
        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(MemoryRecords.readableRecords(buffer), Errors.NONE, 100L, 0));
        consumerClient.poll(0);

        List<ConsumerRecord<byte[], byte[]>> records = fetcher.fetchedRecords().get(tp1);
        assertEquals(1, records.size());
        assertEquals(2L, records.get(0).offset());
        assertEquals(3, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testInvalidDefaultRecordBatch() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        ByteBufferOutputStream out = new ByteBufferOutputStream(buffer);

        MemoryRecordsBuilder builder = new MemoryRecordsBuilder(out,
                                                                DefaultRecordBatch.CURRENT_MAGIC_VALUE,
                                                                CompressionType.NONE,
                                                                TimestampType.CREATE_TIME,
                                                                0L, 10L, 0L, (short) 0, 0, false, false, 0, 1024);
        builder.append(10L, "key".getBytes(), "value".getBytes());
        builder.close();
        buffer.flip();

        // Garble the CRC
        buffer.position(17);
        buffer.put("beef".getBytes());
        buffer.position(0);

        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(MemoryRecords.readableRecords(buffer), Errors.NONE, 100L, 0));
        consumerClient.poll(0);

        // the fetchedRecords() should always throw exception due to the bad batch.
        for (int i = 0; i < 2; i++) {
            try {
                fetcher.fetchedRecords();
                fail("fetchedRecords should have raised KafkaException");
            } catch (KafkaException e) {
                assertEquals(0, subscriptions.position(tp1).longValue());
            }
        }
    }

    @Test
    public void testParseInvalidRecordBatch() throws Exception {
        MemoryRecords records = MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V2, 0L,
                CompressionType.NONE, TimestampType.CREATE_TIME,
                new SimpleRecord(1L, "a".getBytes(), "1".getBytes()),
                new SimpleRecord(2L, "b".getBytes(), "2".getBytes()),
                new SimpleRecord(3L, "c".getBytes(), "3".getBytes()));
        ByteBuffer buffer = records.buffer();

        // flip some bits to fail the crc
        buffer.putInt(32, buffer.get(32) ^ 87238423);

        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(MemoryRecords.readableRecords(buffer), Errors.NONE, 100L, 0));
        consumerClient.poll(0);
        try {
            fetcher.fetchedRecords();
            fail("fetchedRecords should have raised");
        } catch (KafkaException e) {
            // the position should not advance since no data has been returned
            assertEquals(0, subscriptions.position(tp1).longValue());
        }
    }

    @Test
    public void testHeaders() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(time));

        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE, TimestampType.CREATE_TIME, 1L);
        builder.append(0L, "key".getBytes(), "value-1".getBytes());

        Header[] headersArray = new Header[1];
        headersArray[0] = new RecordHeader("headerKey", "headerValue".getBytes(StandardCharsets.UTF_8));
        builder.append(0L, "key".getBytes(), "value-2".getBytes(), headersArray);

        Header[] headersArray2 = new Header[2];
        headersArray2[0] = new RecordHeader("headerKey", "headerValue".getBytes(StandardCharsets.UTF_8));
        headersArray2[1] = new RecordHeader("headerKey", "headerValue2".getBytes(StandardCharsets.UTF_8));
        builder.append(0L, "key".getBytes(), "value-3".getBytes(), headersArray2);

        MemoryRecords memoryRecords = builder.build();

        List<ConsumerRecord<byte[], byte[]>> records;
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 1);

        client.prepareResponse(matchesOffset(tp1, 1), fetchResponse(memoryRecords, Errors.NONE, 100L, 0));

        assertEquals(1, fetcher.sendFetches());
        consumerClient.poll(0);
        records = fetcher.fetchedRecords().get(tp1);

        assertEquals(3, records.size());

        Iterator<ConsumerRecord<byte[], byte[]>> recordIterator = records.iterator();

        ConsumerRecord<byte[], byte[]> record = recordIterator.next();
        assertNull(record.headers().lastHeader("headerKey"));

        record = recordIterator.next();
        assertEquals("headerValue", new String(record.headers().lastHeader("headerKey").value(), StandardCharsets.UTF_8));
        assertEquals("headerKey", record.headers().lastHeader("headerKey").key());

        record = recordIterator.next();
        assertEquals("headerValue2", new String(record.headers().lastHeader("headerKey").value(), StandardCharsets.UTF_8));
        assertEquals("headerKey", record.headers().lastHeader("headerKey").key());
    }

    @Test
    public void testFetchMaxPollRecords() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(time), 2);

        List<ConsumerRecord<byte[], byte[]>> records;
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 1);

        client.prepareResponse(matchesOffset(tp1, 1), fetchResponse(this.records, Errors.NONE, 100L, 0));
        client.prepareResponse(matchesOffset(tp1, 4), fetchResponse(this.nextRecords, Errors.NONE, 100L, 0));

        assertEquals(1, fetcher.sendFetches());
        consumerClient.poll(0);
        records = fetcher.fetchedRecords().get(tp1);
        assertEquals(2, records.size());
        assertEquals(3L, subscriptions.position(tp1).longValue());
        assertEquals(1, records.get(0).offset());
        assertEquals(2, records.get(1).offset());

        assertEquals(0, fetcher.sendFetches());
        consumerClient.poll(0);
        records = fetcher.fetchedRecords().get(tp1);
        assertEquals(1, records.size());
        assertEquals(4L, subscriptions.position(tp1).longValue());
        assertEquals(3, records.get(0).offset());

        assertTrue(fetcher.sendFetches() > 0);
        consumerClient.poll(0);
        records = fetcher.fetchedRecords().get(tp1);
        assertEquals(2, records.size());
        assertEquals(6L, subscriptions.position(tp1).longValue());
        assertEquals(4, records.get(0).offset());
        assertEquals(5, records.get(1).offset());
    }

    /**
     * Test the scenario where a partition with fetched but not consumed records (i.e. max.poll.records is
     * less than the number of fetched records) is unassigned and a different partition is assigned. This is a
     * pattern used by Streams state restoration and KAFKA-5097 would have been caught by this test.
     */
    @Test
    public void testFetchAfterPartitionWithFetchedRecordsIsUnassigned() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(time), 2);

        List<ConsumerRecord<byte[], byte[]>> records;
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 1);

        // Returns 3 records while `max.poll.records` is configured to 2
        client.prepareResponse(matchesOffset(tp1, 1), fetchResponse(tp1, this.records, Errors.NONE, 100L, 0));

        assertEquals(1, fetcher.sendFetches());
        consumerClient.poll(0);
        records = fetcher.fetchedRecords().get(tp1);
        assertEquals(2, records.size());
        assertEquals(3L, subscriptions.position(tp1).longValue());
        assertEquals(1, records.get(0).offset());
        assertEquals(2, records.get(1).offset());

        subscriptions.assignFromUser(singleton(tp2));
        client.prepareResponse(matchesOffset(tp2, 4), fetchResponse(tp2, this.nextRecords, Errors.NONE, 100L, 0));
        subscriptions.seek(tp2, 4);

        assertEquals(1, fetcher.sendFetches());
        consumerClient.poll(0);
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchedRecords = fetcher.fetchedRecords();
        assertNull(fetchedRecords.get(tp1));
        records = fetchedRecords.get(tp2);
        assertEquals(2, records.size());
        assertEquals(6L, subscriptions.position(tp2).longValue());
        assertEquals(4, records.get(0).offset());
        assertEquals(5, records.get(1).offset());
    }

    @Test
    public void testFetchNonContinuousRecords() {
        // if we are fetching from a compacted topic, there may be gaps in the returned records
        // this test verifies the fetcher updates the current fetched/consumed positions correctly for this case

        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE,
                TimestampType.CREATE_TIME, 0L);
        builder.appendWithOffset(15L, 0L, "key".getBytes(), "value-1".getBytes());
        builder.appendWithOffset(20L, 0L, "key".getBytes(), "value-2".getBytes());
        builder.appendWithOffset(30L, 0L, "key".getBytes(), "value-3".getBytes());
        MemoryRecords records = builder.build();

        List<ConsumerRecord<byte[], byte[]>> consumerRecords;
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(records, Errors.NONE, 100L, 0));
        consumerClient.poll(0);
        consumerRecords = fetcher.fetchedRecords().get(tp1);
        assertEquals(3, consumerRecords.size());
        assertEquals(31L, subscriptions.position(tp1).longValue()); // this is the next fetching position

        assertEquals(15L, consumerRecords.get(0).offset());
        assertEquals(20L, consumerRecords.get(1).offset());
        assertEquals(30L, consumerRecords.get(2).offset());
    }

    /**
     * Test the case where the client makes a pre-v3 FetchRequest, but the server replies with only a partial
     * request. This happens when a single message is larger than the per-partition limit.
     */
    @Test
    public void testFetchRequestWhenRecordTooLarge() {
        try {
            client.setNodeApiVersions(NodeApiVersions.create(Collections.singletonList(
                new ApiVersionsResponse.ApiVersion(ApiKeys.FETCH.id, (short) 2, (short) 2))));
            makeFetchRequestWithIncompleteRecord();
            try {
                fetcher.fetchedRecords();
                fail("RecordTooLargeException should have been raised");
            } catch (RecordTooLargeException e) {
                assertTrue(e.getMessage().startsWith("There are some messages at [Partition=Offset]: "));
                // the position should not advance since no data has been returned
                assertEquals(0, subscriptions.position(tp1).longValue());
            }
        } finally {
            client.setNodeApiVersions(NodeApiVersions.create());
        }
    }

    /**
     * Test the case where the client makes a post KIP-74 FetchRequest, but the server replies with only a
     * partial request. For v3 and later FetchRequests, the implementation of KIP-74 changed the behavior
     * so that at least one message is always returned. Therefore, this case should not happen, and it indicates
     * that an internal error has taken place.
     */
    @Test
    public void testFetchRequestInternalError() {
        makeFetchRequestWithIncompleteRecord();
        try {
            fetcher.fetchedRecords();
            fail("RecordTooLargeException should have been raised");
        } catch (KafkaException e) {
            assertTrue(e.getMessage().startsWith("Failed to make progress reading messages"));
            // the position should not advance since no data has been returned
            assertEquals(0, subscriptions.position(tp1).longValue());
        }
    }

    private void makeFetchRequestWithIncompleteRecord() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());
        MemoryRecords partialRecord = MemoryRecords.readableRecords(
            ByteBuffer.wrap(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}));
        client.prepareResponse(fetchResponse(partialRecord, Errors.NONE, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());
    }

    @Test
    public void testUnauthorizedTopic() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        // resize the limit of the buffer to pretend it is only fetch-size large
        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(this.records, Errors.TOPIC_AUTHORIZATION_FAILED, 100L, 0));
        consumerClient.poll(0);
        try {
            fetcher.fetchedRecords();
            fail("fetchedRecords should have thrown");
        } catch (TopicAuthorizationException e) {
            assertEquals(singleton(topicName), e.unauthorizedTopics());
        }
    }

    @Test
    public void testFetchDuringRebalance() {
        subscriptions.subscribe(singleton(topicName), listener);
        subscriptions.assignFromSubscribed(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());

        // Now the rebalance happens and fetch positions are cleared
        subscriptions.assignFromSubscribed(singleton(tp1));
        client.prepareResponse(fetchResponse(this.records, Errors.NONE, 100L, 0));
        consumerClient.poll(0);

        // The active fetch should be ignored since its position is no longer valid
        assertTrue(fetcher.fetchedRecords().isEmpty());
    }

    @Test
    public void testInFlightFetchOnPausedPartition() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());
        subscriptions.pause(tp1);

        client.prepareResponse(fetchResponse(this.records, Errors.NONE, 100L, 0));
        consumerClient.poll(0);
        assertNull(fetcher.fetchedRecords().get(tp1));
    }

    @Test
    public void testFetchOnPausedPartition() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        subscriptions.pause(tp1);
        assertFalse(fetcher.sendFetches() > 0);
        assertTrue(client.requests().isEmpty());
    }

    @Test
    public void testFetchNotLeaderForPartition() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(this.records, Errors.NOT_LEADER_FOR_PARTITION, 100L, 0));
        consumerClient.poll(0);
        assertEquals(0, fetcher.fetchedRecords().size());
        assertEquals(0L, metadata.timeToNextUpdate(time.milliseconds()));
    }

    @Test
    public void testFetchUnknownTopicOrPartition() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(this.records, Errors.UNKNOWN_TOPIC_OR_PARTITION, 100L, 0));
        consumerClient.poll(0);
        assertEquals(0, fetcher.fetchedRecords().size());
        assertEquals(0L, metadata.timeToNextUpdate(time.milliseconds()));
    }

    @Test
    public void testFetchOffsetOutOfRange() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(this.records, Errors.OFFSET_OUT_OF_RANGE, 100L, 0));
        consumerClient.poll(0);
        assertEquals(0, fetcher.fetchedRecords().size());
        assertTrue(subscriptions.isOffsetResetNeeded(tp1));
        assertEquals(null, subscriptions.position(tp1));
    }

    @Test
    public void testStaleOutOfRangeError() {
        // verify that an out of range error which arrives after a seek
        // does not cause us to reset our position or throw an exception
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(this.records, Errors.OFFSET_OUT_OF_RANGE, 100L, 0));
        subscriptions.seek(tp1, 1);
        consumerClient.poll(0);
        assertEquals(0, fetcher.fetchedRecords().size());
        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertEquals(1, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testFetchedRecordsAfterSeek() {
        subscriptionsNoAutoReset.assignFromUser(singleton(tp1));
        subscriptionsNoAutoReset.seek(tp1, 0);

        assertTrue(fetcherNoAutoReset.sendFetches() > 0);
        client.prepareResponse(fetchResponse(this.records, Errors.OFFSET_OUT_OF_RANGE, 100L, 0));
        consumerClient.poll(0);
        assertFalse(subscriptionsNoAutoReset.isOffsetResetNeeded(tp1));
        subscriptionsNoAutoReset.seek(tp1, 2);
        assertEquals(0, fetcherNoAutoReset.fetchedRecords().size());
    }

    @Test
    public void testFetchOffsetOutOfRangeException() {
        subscriptionsNoAutoReset.assignFromUser(singleton(tp1));
        subscriptionsNoAutoReset.seek(tp1, 0);

        fetcherNoAutoReset.sendFetches();
        client.prepareResponse(fetchResponse(this.records, Errors.OFFSET_OUT_OF_RANGE, 100L, 0));
        consumerClient.poll(0);

        assertFalse(subscriptionsNoAutoReset.isOffsetResetNeeded(tp1));
        for (int i = 0; i < 2; i++) {
            try {
                fetcherNoAutoReset.fetchedRecords();
                fail("Should have thrown OffsetOutOfRangeException");
            } catch (OffsetOutOfRangeException e) {
                assertTrue(e.offsetOutOfRangePartitions().containsKey(tp1));
                assertEquals(e.offsetOutOfRangePartitions().size(), 1);
            }
        }
    }

    @Test
    public void testFetchPositionAfterException() {
        // verify the advancement in the next fetch offset equals to the number of fetched records when
        // some fetched partitions cause Exception. This ensures that consumer won't lose record upon exception
        subscriptionsNoAutoReset.assignFromUser(Utils.mkSet(tp1, tp2));
        subscriptionsNoAutoReset.seek(tp1, 1);
        subscriptionsNoAutoReset.seek(tp2, 1);

        assertEquals(1, fetcherNoAutoReset.sendFetches());

        Map<TopicPartition, FetchResponse.PartitionData> partitions = new LinkedHashMap<>();
        partitions.put(tp2, new FetchResponse.PartitionData(Errors.NONE, 100,
            FetchResponse.INVALID_LAST_STABLE_OFFSET, FetchResponse.INVALID_LOG_START_OFFSET, null, records));
        partitions.put(tp1, new FetchResponse.PartitionData(Errors.OFFSET_OUT_OF_RANGE, 100,
                FetchResponse.INVALID_LAST_STABLE_OFFSET, FetchResponse.INVALID_LOG_START_OFFSET, null, MemoryRecords.EMPTY));
        client.prepareResponse(new FetchResponse(new LinkedHashMap<>(partitions), 0));
        consumerClient.poll(0);

        List<ConsumerRecord<byte[], byte[]>> fetchedRecords = new ArrayList<>();
        List<OffsetOutOfRangeException> exceptions = new ArrayList<>();

        for (List<ConsumerRecord<byte[], byte[]>> records: fetcherNoAutoReset.fetchedRecords().values())
            fetchedRecords.addAll(records);

        assertEquals(fetchedRecords.size(), subscriptionsNoAutoReset.position(tp2) - 1);

        try {
            for (List<ConsumerRecord<byte[], byte[]>> records: fetcherNoAutoReset.fetchedRecords().values())
                fetchedRecords.addAll(records);
        } catch (OffsetOutOfRangeException e) {
            exceptions.add(e);
        }

        assertEquals(4, subscriptionsNoAutoReset.position(tp2).longValue());
        assertEquals(3, fetchedRecords.size());

        // Should have received one OffsetOutOfRangeException for partition tp1
        assertEquals(1, exceptions.size());
        OffsetOutOfRangeException e = exceptions.get(0);
        assertTrue(e.offsetOutOfRangePartitions().containsKey(tp1));
        assertEquals(e.offsetOutOfRangePartitions().size(), 1);
    }

    @Test
    public void testSeekBeforeException() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptionsNoAutoReset, new Metrics(time), 2);

        subscriptionsNoAutoReset.assignFromUser(Utils.mkSet(tp1));
        subscriptionsNoAutoReset.seek(tp1, 1);
        assertEquals(1, fetcher.sendFetches());
        Map<TopicPartition, FetchResponse.PartitionData> partitions = new HashMap<>();
        partitions.put(tp1, new FetchResponse.PartitionData(Errors.NONE, 100,
            FetchResponse.INVALID_LAST_STABLE_OFFSET, FetchResponse.INVALID_LOG_START_OFFSET, null, records));
        client.prepareResponse(fetchResponse(this.records, Errors.NONE, 100L, 0));
        consumerClient.poll(0);

        assertEquals(2, fetcher.fetchedRecords().get(tp1).size());

        subscriptionsNoAutoReset.assignFromUser(Utils.mkSet(tp1, tp2));
        subscriptionsNoAutoReset.seek(tp2, 1);
        assertEquals(1, fetcher.sendFetches());
        partitions = new HashMap<>();
        partitions.put(tp2, new FetchResponse.PartitionData(Errors.OFFSET_OUT_OF_RANGE, 100,
            FetchResponse.INVALID_LAST_STABLE_OFFSET, FetchResponse.INVALID_LOG_START_OFFSET, null, MemoryRecords.EMPTY));
        client.prepareResponse(new FetchResponse(new LinkedHashMap<>(partitions), 0));
        consumerClient.poll(0);
        assertEquals(1, fetcher.fetchedRecords().get(tp1).size());

        subscriptionsNoAutoReset.seek(tp2, 10);
        // Should not throw OffsetOutOfRangeException after the seek
        assertEquals(0, fetcher.fetchedRecords().size());
    }

    @Test
    public void testFetchDisconnected() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(this.records, Errors.NONE, 100L, 0), true);
        consumerClient.poll(0);
        assertEquals(0, fetcher.fetchedRecords().size());

        // disconnects should have no affect on subscription state
        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertTrue(subscriptions.isFetchable(tp1));
        assertEquals(0, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionsNoneCommittedNoResetStrategy() {
        Set<TopicPartition> tps = new HashSet<>(Arrays.asList(tp1, tp2));
        subscriptionsNoAutoReset.assignFromUser(tps);
        try {
            fetcherNoAutoReset.updateFetchPositions(tps);
            fail("Should have thrown NoOffsetForPartitionException");
        } catch (NoOffsetForPartitionException e) {
            // we expect the exception to be thrown for both TPs at the same time
            Set<TopicPartition> partitions = e.partitions();
            assertEquals(tps, partitions);
        }
    }

    @Test
    public void testUpdateFetchPositionToCommitted() {
        // unless a specific reset is expected, the default behavior is to reset to the committed
        // position if one is present
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.committed(tp1, new OffsetAndMetadata(5));

        fetcher.updateFetchPositions(singleton(tp1));
        assertTrue(subscriptions.isFetchable(tp1));
        assertEquals(5, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionResetToDefaultOffset() {
        subscriptions.assignFromUser(singleton(tp1));
        // with no commit position, we should reset using the default strategy defined above (EARLIEST)

        client.prepareResponse(listOffsetRequestMatcher(ListOffsetRequest.EARLIEST_TIMESTAMP),
                               listOffsetResponse(Errors.NONE, 1L, 5L));
        fetcher.updateFetchPositions(singleton(tp1));
        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertTrue(subscriptions.isFetchable(tp1));
        assertEquals(5, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionResetToLatestOffset() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.needOffsetReset(tp1, OffsetResetStrategy.LATEST);

        client.prepareResponse(listOffsetRequestMatcher(ListOffsetRequest.LATEST_TIMESTAMP),
                               listOffsetResponse(Errors.NONE, 1L, 5L));
        fetcher.updateFetchPositions(singleton(tp1));
        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertTrue(subscriptions.isFetchable(tp1));
        assertEquals(5, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testListOffsetsSendsIsolationLevel() {
        for (final IsolationLevel isolationLevel : IsolationLevel.values()) {
            Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(), new ByteArrayDeserializer(),
                    new ByteArrayDeserializer(), Integer.MAX_VALUE, isolationLevel);

            subscriptions.assignFromUser(singleton(tp1));
            subscriptions.needOffsetReset(tp1, OffsetResetStrategy.LATEST);

            client.prepareResponse(new MockClient.RequestMatcher() {
                @Override
                public boolean matches(AbstractRequest body) {
                    ListOffsetRequest request = (ListOffsetRequest) body;
                    return request.isolationLevel() == isolationLevel;
                }
            }, listOffsetResponse(Errors.NONE, 1L, 5L));
            fetcher.updateFetchPositions(singleton(tp1));
            assertFalse(subscriptions.isOffsetResetNeeded(tp1));
            assertTrue(subscriptions.isFetchable(tp1));
            assertEquals(5, subscriptions.position(tp1).longValue());
        }
    }

    @Test
    public void testUpdateFetchPositionResetToEarliestOffset() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.needOffsetReset(tp1, OffsetResetStrategy.EARLIEST);

        client.prepareResponse(listOffsetRequestMatcher(ListOffsetRequest.EARLIEST_TIMESTAMP),
                               listOffsetResponse(Errors.NONE, 1L, 5L));
        fetcher.updateFetchPositions(singleton(tp1));
        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertTrue(subscriptions.isFetchable(tp1));
        assertEquals(5, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionDisconnect() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.needOffsetReset(tp1, OffsetResetStrategy.LATEST);

        // First request gets a disconnect
        client.prepareResponse(listOffsetRequestMatcher(ListOffsetRequest.LATEST_TIMESTAMP),
                               listOffsetResponse(Errors.NONE, 1L, 5L), true);

        // Next one succeeds
        client.prepareResponse(listOffsetRequestMatcher(ListOffsetRequest.LATEST_TIMESTAMP),
                               listOffsetResponse(Errors.NONE, 1L, 5L));
        fetcher.updateFetchPositions(singleton(tp1));
        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertTrue(subscriptions.isFetchable(tp1));
        assertEquals(5, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionOfPausedPartitionsRequiringOffsetReset() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.committed(tp1, new OffsetAndMetadata(0));
        subscriptions.pause(tp1); // paused partition does not have a valid position
        subscriptions.needOffsetReset(tp1, OffsetResetStrategy.LATEST);

        client.prepareResponse(listOffsetRequestMatcher(ListOffsetRequest.LATEST_TIMESTAMP),
                               listOffsetResponse(Errors.NONE, 1L, 10L));
        fetcher.updateFetchPositions(singleton(tp1));

        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertFalse(subscriptions.isFetchable(tp1)); // because tp is paused
        assertTrue(subscriptions.hasValidPosition(tp1));
        assertEquals(10, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionOfPausedPartitionsWithoutACommittedOffset() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.pause(tp1); // paused partition does not have a valid position

        client.prepareResponse(listOffsetRequestMatcher(ListOffsetRequest.EARLIEST_TIMESTAMP),
                               listOffsetResponse(Errors.NONE, 1L, 0L));
        fetcher.updateFetchPositions(singleton(tp1));

        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertFalse(subscriptions.isFetchable(tp1)); // because tp is paused
        assertTrue(subscriptions.hasValidPosition(tp1));
        assertEquals(0, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionOfPausedPartitionsWithoutAValidPosition() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.committed(tp1, new OffsetAndMetadata(0));
        subscriptions.pause(tp1); // paused partition does not have a valid position
        subscriptions.seek(tp1, 10);

        fetcher.updateFetchPositions(singleton(tp1));

        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertFalse(subscriptions.isFetchable(tp1)); // because tp is paused
        assertTrue(subscriptions.hasValidPosition(tp1));
        assertEquals(10, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testUpdateFetchPositionOfPausedPartitionsWithAValidPosition() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.committed(tp1, new OffsetAndMetadata(0));
        subscriptions.seek(tp1, 10);
        subscriptions.pause(tp1); // paused partition already has a valid position

        fetcher.updateFetchPositions(singleton(tp1));

        assertFalse(subscriptions.isOffsetResetNeeded(tp1));
        assertFalse(subscriptions.isFetchable(tp1)); // because tp is paused
        assertTrue(subscriptions.hasValidPosition(tp1));
        assertEquals(10, subscriptions.position(tp1).longValue());
    }

    @Test
    public void testGetAllTopics() {
        // sending response before request, as getTopicMetadata is a blocking call
        client.prepareResponse(newMetadataResponse(topicName, Errors.NONE));

        Map<String, List<PartitionInfo>> allTopics = fetcher.getAllTopicMetadata(5000L);

        assertEquals(cluster.topics().size(), allTopics.size());
    }

    @Test
    public void testGetAllTopicsDisconnect() {
        // first try gets a disconnect, next succeeds
        client.prepareResponse(null, true);
        client.prepareResponse(newMetadataResponse(topicName, Errors.NONE));
        Map<String, List<PartitionInfo>> allTopics = fetcher.getAllTopicMetadata(5000L);
        assertEquals(cluster.topics().size(), allTopics.size());
    }

    @Test(expected = TimeoutException.class)
    public void testGetAllTopicsTimeout() {
        // since no response is prepared, the request should timeout
        fetcher.getAllTopicMetadata(50L);
    }

    @Test
    public void testGetAllTopicsUnauthorized() {
        client.prepareResponse(newMetadataResponse(topicName, Errors.TOPIC_AUTHORIZATION_FAILED));
        try {
            fetcher.getAllTopicMetadata(10L);
            fail();
        } catch (TopicAuthorizationException e) {
            assertEquals(singleton(topicName), e.unauthorizedTopics());
        }
    }

    @Test(expected = InvalidTopicException.class)
    public void testGetTopicMetadataInvalidTopic() {
        client.prepareResponse(newMetadataResponse(topicName, Errors.INVALID_TOPIC_EXCEPTION));
        fetcher.getTopicMetadata(
                new MetadataRequest.Builder(Collections.singletonList(topicName), true), 5000L);
    }

    @Test
    public void testGetTopicMetadataUnknownTopic() {
        client.prepareResponse(newMetadataResponse(topicName, Errors.UNKNOWN_TOPIC_OR_PARTITION));

        Map<String, List<PartitionInfo>> topicMetadata = fetcher.getTopicMetadata(
                new MetadataRequest.Builder(Collections.singletonList(topicName), true), 5000L);
        assertNull(topicMetadata.get(topicName));
    }

    @Test
    public void testGetTopicMetadataLeaderNotAvailable() {
        client.prepareResponse(newMetadataResponse(topicName, Errors.LEADER_NOT_AVAILABLE));
        client.prepareResponse(newMetadataResponse(topicName, Errors.NONE));

        Map<String, List<PartitionInfo>> topicMetadata = fetcher.getTopicMetadata(
                new MetadataRequest.Builder(Collections.singletonList(topicName), true), 5000L);
        assertTrue(topicMetadata.containsKey(topicName));
    }

    /*
     * Send multiple requests. Verify that the client side quota metrics have the right values
     */
    @Test
    public void testQuotaMetrics() throws Exception {
        MockSelector selector = new MockSelector(time);
        Sensor throttleTimeSensor = Fetcher.throttleTimeSensor(metrics, metricsRegistry);
        Cluster cluster = TestUtils.singletonCluster("test", 1);
        Node node = cluster.nodes().get(0);
        NetworkClient client = new NetworkClient(selector, metadata, "mock", Integer.MAX_VALUE,
                1000, 1000, 64 * 1024, 64 * 1024, 1000,
                time, true, new ApiVersions(), throttleTimeSensor);

        short apiVersionsResponseVersion = ApiKeys.API_VERSIONS.latestVersion();
        ByteBuffer buffer = ApiVersionsResponse.createApiVersionsResponse(400, RecordBatch.CURRENT_MAGIC_VALUE).serialize(apiVersionsResponseVersion, new ResponseHeader(0));
        selector.delayedReceive(new DelayedReceive(node.idString(), new NetworkReceive(node.idString(), buffer)));
        while (!client.ready(node, time.milliseconds()))
            client.poll(1, time.milliseconds());
        selector.clear();

        for (int i = 1; i <= 3; i++) {
            int throttleTimeMs = 100 * i;
            FetchRequest.Builder builder = FetchRequest.Builder.forConsumer(100, 100, new LinkedHashMap<TopicPartition, PartitionData>());
            ClientRequest request = client.newClientRequest(node.idString(), builder, time.milliseconds(), true, null);
            client.send(request, time.milliseconds());
            client.poll(1, time.milliseconds());
            FetchResponse response = fetchResponse(nextRecords, Errors.NONE, i, throttleTimeMs);
            buffer = response.serialize(ApiKeys.FETCH.latestVersion(), new ResponseHeader(request.correlationId()));
            selector.completeReceive(new NetworkReceive(node.idString(), buffer));
            client.poll(1, time.milliseconds());
            selector.clear();
        }
        Map<MetricName, KafkaMetric> allMetrics = metrics.metrics();
        KafkaMetric avgMetric = allMetrics.get(metrics.metricInstance(metricsRegistry.fetchThrottleTimeAvg));
        KafkaMetric maxMetric = allMetrics.get(metrics.metricInstance(metricsRegistry.fetchThrottleTimeMax));
        // Throttle times are ApiVersions=400, Fetch=(100, 200, 300)
        assertEquals(250, avgMetric.value(), EPSILON);
        assertEquals(400, maxMetric.value(), EPSILON);
        client.close();
    }


    /*
     * Send multiple requests. Verify that the client side quota metrics have the right values
     */
    @Test
    public void testFetcherMetrics() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        MetricName maxLagMetric = metrics.metricInstance(metricsRegistry.recordsLagMax);
        MetricName partitionLagMetric = metrics.metricName(tp1 + ".records-lag", metricGroup);

        Map<MetricName, KafkaMetric> allMetrics = metrics.metrics();
        KafkaMetric recordsFetchLagMax = allMetrics.get(maxLagMetric);

        // recordsFetchLagMax should be initialized to negative infinity
        assertEquals(Double.NEGATIVE_INFINITY, recordsFetchLagMax.value(), EPSILON);

        // recordsFetchLagMax should be hw - fetchOffset after receiving an empty FetchResponse
        fetchRecords(MemoryRecords.EMPTY, Errors.NONE, 100L, 0);
        assertEquals(100, recordsFetchLagMax.value(), EPSILON);

        KafkaMetric partitionLag = allMetrics.get(partitionLagMetric);
        assertEquals(100, partitionLag.value(), EPSILON);

        // recordsFetchLagMax should be hw - offset of the last message after receiving a non-empty FetchResponse
        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE,
                TimestampType.CREATE_TIME, 0L);
        for (int v = 0; v < 3; v++)
            builder.appendWithOffset(v, RecordBatch.NO_TIMESTAMP, "key".getBytes(), ("value-" + v).getBytes());
        fetchRecords(builder.build(), Errors.NONE, 200L, 0);
        assertEquals(197, recordsFetchLagMax.value(), EPSILON);

        // verify de-registration of partition lag
        subscriptions.unsubscribe();
        assertFalse(allMetrics.containsKey(partitionLagMetric));
    }

    @Test
    public void testFetchResponseMetrics() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);

        Map<MetricName, KafkaMetric> allMetrics = metrics.metrics();
        KafkaMetric fetchSizeAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.fetchSizeAvg));
        KafkaMetric recordsCountAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.recordsPerRequestAvg));

        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE,
                TimestampType.CREATE_TIME, 0L);
        for (int v = 0; v < 3; v++)
            builder.appendWithOffset(v, RecordBatch.NO_TIMESTAMP, "key".getBytes(), ("value-" + v).getBytes());
        MemoryRecords records = builder.build();

        int expectedBytes = 0;
        for (Record record : records.records())
            expectedBytes += record.sizeInBytes();

        fetchRecords(records, Errors.NONE, 100L, 0);
        assertEquals(expectedBytes, fetchSizeAverage.value(), EPSILON);
        assertEquals(3, recordsCountAverage.value(), EPSILON);
    }

    @Test
    public void testFetchResponseMetricsPartialResponse() {
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 1);

        Map<MetricName, KafkaMetric> allMetrics = metrics.metrics();
        KafkaMetric fetchSizeAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.fetchSizeAvg));
        KafkaMetric recordsCountAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.recordsPerRequestAvg));

        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE,
                TimestampType.CREATE_TIME, 0L);
        for (int v = 0; v < 3; v++)
            builder.appendWithOffset(v, RecordBatch.NO_TIMESTAMP, "key".getBytes(), ("value-" + v).getBytes());
        MemoryRecords records = builder.build();

        int expectedBytes = 0;
        for (Record record : records.records()) {
            if (record.offset() >= 1)
                expectedBytes += record.sizeInBytes();
        }

        fetchRecords(records, Errors.NONE, 100L, 0);
        assertEquals(expectedBytes, fetchSizeAverage.value(), EPSILON);
        assertEquals(2, recordsCountAverage.value(), EPSILON);
    }

    @Test
    public void testFetchResponseMetricsWithOnePartitionError() {
        subscriptions.assignFromUser(Utils.mkSet(tp1, tp2));
        subscriptions.seek(tp1, 0);
        subscriptions.seek(tp2, 0);

        Map<MetricName, KafkaMetric> allMetrics = metrics.metrics();
        KafkaMetric fetchSizeAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.fetchSizeAvg));
        KafkaMetric recordsCountAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.recordsPerRequestAvg));

        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE,
                TimestampType.CREATE_TIME, 0L);
        for (int v = 0; v < 3; v++)
            builder.appendWithOffset(v, RecordBatch.NO_TIMESTAMP, "key".getBytes(), ("value-" + v).getBytes());
        MemoryRecords records = builder.build();

        Map<TopicPartition, FetchResponse.PartitionData> partitions = new HashMap<>();
        partitions.put(tp1, new FetchResponse.PartitionData(Errors.NONE, 100,
                FetchResponse.INVALID_LAST_STABLE_OFFSET, 0L, null, records));
        partitions.put(tp2, new FetchResponse.PartitionData(Errors.OFFSET_OUT_OF_RANGE, 100,
                FetchResponse.INVALID_LAST_STABLE_OFFSET, 0L, null, MemoryRecords.EMPTY));

        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(new FetchResponse(new LinkedHashMap<>(partitions), 0));
        consumerClient.poll(0);
        fetcher.fetchedRecords();

        int expectedBytes = 0;
        for (Record record : records.records())
            expectedBytes += record.sizeInBytes();

        assertEquals(expectedBytes, fetchSizeAverage.value(), EPSILON);
        assertEquals(3, recordsCountAverage.value(), EPSILON);
    }

    @Test
    public void testFetchResponseMetricsWithOnePartitionAtTheWrongOffset() {
        subscriptions.assignFromUser(Utils.mkSet(tp1, tp2));
        subscriptions.seek(tp1, 0);
        subscriptions.seek(tp2, 0);

        Map<MetricName, KafkaMetric> allMetrics = metrics.metrics();
        KafkaMetric fetchSizeAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.fetchSizeAvg));
        KafkaMetric recordsCountAverage = allMetrics.get(metrics.metricInstance(metricsRegistry.recordsPerRequestAvg));

        // send the fetch and then seek to a new offset
        assertEquals(1, fetcher.sendFetches());
        subscriptions.seek(tp2, 5);

        MemoryRecordsBuilder builder = MemoryRecords.builder(ByteBuffer.allocate(1024), CompressionType.NONE,
                TimestampType.CREATE_TIME, 0L);
        for (int v = 0; v < 3; v++)
            builder.appendWithOffset(v, RecordBatch.NO_TIMESTAMP, "key".getBytes(), ("value-" + v).getBytes());
        MemoryRecords records = builder.build();

        Map<TopicPartition, FetchResponse.PartitionData> partitions = new HashMap<>();
        partitions.put(tp1, new FetchResponse.PartitionData(Errors.NONE, 100,
                FetchResponse.INVALID_LAST_STABLE_OFFSET, 0L, null, records));
        partitions.put(tp2, new FetchResponse.PartitionData(Errors.NONE, 100,
                FetchResponse.INVALID_LAST_STABLE_OFFSET, 0L, null,
                MemoryRecords.withRecords(CompressionType.NONE, new SimpleRecord("val".getBytes()))));

        client.prepareResponse(new FetchResponse(new LinkedHashMap<>(partitions), 0));
        consumerClient.poll(0);
        fetcher.fetchedRecords();

        // we should have ignored the record at the wrong offset
        int expectedBytes = 0;
        for (Record record : records.records())
            expectedBytes += record.sizeInBytes();

        assertEquals(expectedBytes, fetchSizeAverage.value(), EPSILON);
        assertEquals(3, recordsCountAverage.value(), EPSILON);
    }

    private Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchRecords(MemoryRecords records, Errors error, long hw, int throttleTime) {
        assertEquals(1, fetcher.sendFetches());
        client.prepareResponse(fetchResponse(records, error, hw, throttleTime));
        consumerClient.poll(0);
        return fetcher.fetchedRecords();
    }

    @Test
    public void testGetOffsetsForTimesTimeout() {
        try {
            fetcher.getOffsetsByTimes(Collections.singletonMap(new TopicPartition(topicName, 2), 1000L), 100L);
            fail("Should throw timeout exception.");
        } catch (TimeoutException e) {
            // let it go.
        }
    }

    @Test
    public void testGetOffsetsForTimes() {
        // Empty map
        assertTrue(fetcher.getOffsetsByTimes(new HashMap<TopicPartition, Long>(), 100L).isEmpty());
        // Error code none with unknown offset
        testGetOffsetsForTimesWithError(Errors.NONE, Errors.NONE, -1L, 100L, null, 100L);
        // Error code none with known offset
        testGetOffsetsForTimesWithError(Errors.NONE, Errors.NONE, 10L, 100L, 10L, 100L);
        // Test both of partition has error.
        testGetOffsetsForTimesWithError(Errors.NOT_LEADER_FOR_PARTITION, Errors.INVALID_REQUEST, 10L, 100L, 10L, 100L);
        // Test the second partition has error.
        testGetOffsetsForTimesWithError(Errors.NONE, Errors.NOT_LEADER_FOR_PARTITION, 10L, 100L, 10L, 100L);
        // Test different errors.
        testGetOffsetsForTimesWithError(Errors.NOT_LEADER_FOR_PARTITION, Errors.NONE, 10L, 100L, 10L, 100L);
        testGetOffsetsForTimesWithError(Errors.UNKNOWN_TOPIC_OR_PARTITION, Errors.NONE, 10L, 100L, 10L, 100L);
        testGetOffsetsForTimesWithError(Errors.UNSUPPORTED_FOR_MESSAGE_FORMAT, Errors.NONE, 10L, 100L, null, 100L);
        testGetOffsetsForTimesWithError(Errors.BROKER_NOT_AVAILABLE, Errors.NONE, 10L, 100L, 10L, 100L);
    }

    @Test(expected = TimeoutException.class)
    public void testBatchedListOffsetsMetadataErrors() {
        Map<TopicPartition, ListOffsetResponse.PartitionData> partitionData = new HashMap<>();
        partitionData.put(tp1, new ListOffsetResponse.PartitionData(Errors.NOT_LEADER_FOR_PARTITION,
                ListOffsetResponse.UNKNOWN_TIMESTAMP, ListOffsetResponse.UNKNOWN_OFFSET));
        partitionData.put(tp2, new ListOffsetResponse.PartitionData(Errors.UNKNOWN_TOPIC_OR_PARTITION,
                ListOffsetResponse.UNKNOWN_TIMESTAMP, ListOffsetResponse.UNKNOWN_OFFSET));
        client.prepareResponse(new ListOffsetResponse(0, partitionData));

        Map<TopicPartition, Long> offsetsToSearch = new HashMap<>();
        offsetsToSearch.put(tp1, ListOffsetRequest.EARLIEST_TIMESTAMP);
        offsetsToSearch.put(tp2, ListOffsetRequest.EARLIEST_TIMESTAMP);

        fetcher.getOffsetsByTimes(offsetsToSearch, 0);
    }

    @Test
    public void testSkippingAbortedTransactions() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(), new ByteArrayDeserializer(),
                new ByteArrayDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_COMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int currentOffset = 0;

        currentOffset += appendTransactionalRecords(buffer, 1L, currentOffset,
                new SimpleRecord(time.milliseconds(), "key".getBytes(), "value".getBytes()),
                new SimpleRecord(time.milliseconds(), "key".getBytes(), "value".getBytes()));

        abortTransaction(buffer, 1L, currentOffset);

        buffer.flip();

        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();
        abortedTransactions.add(new FetchResponse.AbortedTransaction(1, 0));
        MemoryRecords records = MemoryRecords.readableRecords(buffer);
        subscriptions.assignFromUser(singleton(tp1));

        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        client.prepareResponse(fetchResponseWithAbortedTransactions(records, abortedTransactions, Errors.NONE, 100L, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchedRecords = fetcher.fetchedRecords();
        assertFalse(fetchedRecords.containsKey(tp1));
    }

    @Test
    public void testReturnCommittedTransactions() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(), new ByteArrayDeserializer(),
                new ByteArrayDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_COMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int currentOffset = 0;

        currentOffset += appendTransactionalRecords(buffer, 1L, currentOffset,
                new SimpleRecord(time.milliseconds(), "key".getBytes(), "value".getBytes()),
                new SimpleRecord(time.milliseconds(), "key".getBytes(), "value".getBytes()));

        currentOffset += commitTransaction(buffer, 1L, currentOffset);
        buffer.flip();

        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();
        MemoryRecords records = MemoryRecords.readableRecords(buffer);
        subscriptions.assignFromUser(singleton(tp1));

        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                FetchRequest request = (FetchRequest) body;
                assertEquals(IsolationLevel.READ_COMMITTED, request.isolationLevel());
                return true;
            }
        }, fetchResponseWithAbortedTransactions(records, abortedTransactions, Errors.NONE, 100L, 100L, 0));

        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchedRecords = fetcher.fetchedRecords();
        assertTrue(fetchedRecords.containsKey(tp1));
        assertEquals(fetchedRecords.get(tp1).size(), 2);
    }

    @Test
    public void testReadCommittedWithCommittedAndAbortedTransactions() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(), new ByteArrayDeserializer(),
                new ByteArrayDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_COMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();

        long pid1 = 1L;
        long pid2 = 2L;

        // Appends for producer 1 (eventually committed)
        appendTransactionalRecords(buffer, pid1, 0L,
                new SimpleRecord("commit1-1".getBytes(), "value".getBytes()),
                new SimpleRecord("commit1-2".getBytes(), "value".getBytes()));

        // Appends for producer 2 (eventually aborted)
        appendTransactionalRecords(buffer, pid2, 2L,
                new SimpleRecord("abort2-1".getBytes(), "value".getBytes()));

        // commit producer 1
        commitTransaction(buffer, pid1, 3L);

        // append more for producer 2 (eventually aborted)
        appendTransactionalRecords(buffer, pid2, 4L,
                new SimpleRecord("abort2-2".getBytes(), "value".getBytes()));

        // abort producer 2
        abortTransaction(buffer, pid2, 5L);
        abortedTransactions.add(new FetchResponse.AbortedTransaction(pid2, 2L));

        // New transaction for producer 1 (eventually aborted)
        appendTransactionalRecords(buffer, pid1, 6L,
                new SimpleRecord("abort1-1".getBytes(), "value".getBytes()));

        // New transaction for producer 2 (eventually committed)
        appendTransactionalRecords(buffer, pid2, 7L,
                new SimpleRecord("commit2-1".getBytes(), "value".getBytes()));

        // Add messages for producer 1 (eventually aborted)
        appendTransactionalRecords(buffer, pid1, 8L,
                new SimpleRecord("abort1-2".getBytes(), "value".getBytes()));

        // abort producer 1
        abortTransaction(buffer, pid1, 9L);
        abortedTransactions.add(new FetchResponse.AbortedTransaction(1, 6));

        // commit producer 2
        commitTransaction(buffer, pid2, 10L);

        buffer.flip();

        MemoryRecords records = MemoryRecords.readableRecords(buffer);
        subscriptions.assignFromUser(singleton(tp1));

        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        client.prepareResponse(fetchResponseWithAbortedTransactions(records, abortedTransactions, Errors.NONE, 100L, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchedRecords = fetcher.fetchedRecords();
        assertTrue(fetchedRecords.containsKey(tp1));
        // There are only 3 committed records
        List<ConsumerRecord<byte[], byte[]>> fetchedConsumerRecords = fetchedRecords.get(tp1);
        Set<String> fetchedKeys = new HashSet<>();
        for (ConsumerRecord<byte[], byte[]> consumerRecord : fetchedConsumerRecords) {
            fetchedKeys.add(new String(consumerRecord.key(), StandardCharsets.UTF_8));
        }
        assertEquals(Utils.mkSet("commit1-1", "commit1-2", "commit2-1"), fetchedKeys);
    }

    @Test
    public void testMultipleAbortMarkers() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(), new ByteArrayDeserializer(),
                new ByteArrayDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_COMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int currentOffset = 0;

        currentOffset += appendTransactionalRecords(buffer, 1L, currentOffset,
                new SimpleRecord(time.milliseconds(), "abort1-1".getBytes(), "value".getBytes()),
                new SimpleRecord(time.milliseconds(), "abort1-2".getBytes(), "value".getBytes()));

        currentOffset += abortTransaction(buffer, 1L, currentOffset);
        // Duplicate abort -- should be ignored.
        currentOffset += abortTransaction(buffer, 1L, currentOffset);
        // Now commit a transaction.
        currentOffset += appendTransactionalRecords(buffer, 1L, currentOffset,
                new SimpleRecord(time.milliseconds(), "commit1-1".getBytes(), "value".getBytes()),
                new SimpleRecord(time.milliseconds(), "commit1-2".getBytes(), "value".getBytes()));
        commitTransaction(buffer, 1L, currentOffset);
        buffer.flip();

        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();
        abortedTransactions.add(new FetchResponse.AbortedTransaction(1, 0));
        MemoryRecords records = MemoryRecords.readableRecords(buffer);
        subscriptions.assignFromUser(singleton(tp1));

        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        client.prepareResponse(fetchResponseWithAbortedTransactions(records, abortedTransactions, Errors.NONE, 100L, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchedRecords = fetcher.fetchedRecords();
        assertTrue(fetchedRecords.containsKey(tp1));
        assertEquals(fetchedRecords.get(tp1).size(), 2);
        List<ConsumerRecord<byte[], byte[]>> fetchedConsumerRecords = fetchedRecords.get(tp1);
        Set<String> committedKeys = new HashSet<>(Arrays.asList("commit1-1", "commit1-2"));
        Set<String> actuallyCommittedKeys = new HashSet<>();
        for (ConsumerRecord<byte[], byte[]> consumerRecord : fetchedConsumerRecords) {
            actuallyCommittedKeys.add(new String(consumerRecord.key(), StandardCharsets.UTF_8));
        }
        assertTrue(actuallyCommittedKeys.equals(committedKeys));
    }

    @Test
    public void testReadCommittedAbortMarkerWithNoData() {
        Fetcher<String, String> fetcher = createFetcher(subscriptions, new Metrics(), new StringDeserializer(),
                new StringDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_COMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        long producerId = 1L;

        abortTransaction(buffer, producerId, 5L);

        appendTransactionalRecords(buffer, producerId, 6L,
                new SimpleRecord("6".getBytes(), null),
                new SimpleRecord("7".getBytes(), null),
                new SimpleRecord("8".getBytes(), null));

        commitTransaction(buffer, producerId, 9L);

        buffer.flip();

        // send the fetch
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);
        assertEquals(1, fetcher.sendFetches());

        // prepare the response. the aborted transactions begin at offsets which are no longer in the log
        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();
        abortedTransactions.add(new FetchResponse.AbortedTransaction(producerId, 0L));

        client.prepareResponse(fetchResponseWithAbortedTransactions(MemoryRecords.readableRecords(buffer),
                abortedTransactions, Errors.NONE, 100L, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<String, String>>> allFetchedRecords = fetcher.fetchedRecords();
        assertTrue(allFetchedRecords.containsKey(tp1));
        List<ConsumerRecord<String, String>> fetchedRecords = allFetchedRecords.get(tp1);
        assertEquals(3, fetchedRecords.size());
        assertEquals(Arrays.asList(6L, 7L, 8L), collectRecordOffsets(fetchedRecords));
    }

    @Test
    public void testReadCommittedWithCompactedTopic() {
        Fetcher<String, String> fetcher = createFetcher(subscriptions, new Metrics(), new StringDeserializer(),
                new StringDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_COMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        long pid1 = 1L;
        long pid2 = 2L;
        long pid3 = 3L;

        appendTransactionalRecords(buffer, pid3, 3L,
                new SimpleRecord("3".getBytes(), "value".getBytes()),
                new SimpleRecord("4".getBytes(), "value".getBytes()));

        appendTransactionalRecords(buffer, pid2, 15L,
                new SimpleRecord("15".getBytes(), "value".getBytes()),
                new SimpleRecord("16".getBytes(), "value".getBytes()),
                new SimpleRecord("17".getBytes(), "value".getBytes()));

        appendTransactionalRecords(buffer, pid1, 22L,
                new SimpleRecord("22".getBytes(), "value".getBytes()),
                new SimpleRecord("23".getBytes(), "value".getBytes()));

        abortTransaction(buffer, pid2, 28L);

        appendTransactionalRecords(buffer, pid3, 30L,
                new SimpleRecord("30".getBytes(), "value".getBytes()),
                new SimpleRecord("31".getBytes(), "value".getBytes()),
                new SimpleRecord("32".getBytes(), "value".getBytes()));

        commitTransaction(buffer, pid3, 35L);

        appendTransactionalRecords(buffer, pid1, 39L,
                new SimpleRecord("39".getBytes(), "value".getBytes()),
                new SimpleRecord("40".getBytes(), "value".getBytes()));

        // transaction from pid1 is aborted, but the marker is not included in the fetch

        buffer.flip();

        // send the fetch
        subscriptions.assignFromUser(singleton(tp1));
        subscriptions.seek(tp1, 0);
        assertEquals(1, fetcher.sendFetches());

        // prepare the response. the aborted transactions begin at offsets which are no longer in the log
        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();
        abortedTransactions.add(new FetchResponse.AbortedTransaction(pid2, 6L));
        abortedTransactions.add(new FetchResponse.AbortedTransaction(pid1, 0L));

        client.prepareResponse(fetchResponseWithAbortedTransactions(MemoryRecords.readableRecords(buffer),
                abortedTransactions, Errors.NONE, 100L, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<String, String>>> allFetchedRecords = fetcher.fetchedRecords();
        assertTrue(allFetchedRecords.containsKey(tp1));
        List<ConsumerRecord<String, String>> fetchedRecords = allFetchedRecords.get(tp1);
        assertEquals(5, fetchedRecords.size());
        assertEquals(Arrays.asList(3L, 4L, 30L, 31L, 32L), collectRecordOffsets(fetchedRecords));
    }

    @Test
    public void testReturnAbortedTransactionsinUncommittedMode() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(), new ByteArrayDeserializer(),
                new ByteArrayDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_UNCOMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int currentOffset = 0;

        currentOffset += appendTransactionalRecords(buffer, 1L, currentOffset,
                new SimpleRecord(time.milliseconds(), "key".getBytes(), "value".getBytes()),
                new SimpleRecord(time.milliseconds(), "key".getBytes(), "value".getBytes()));

        abortTransaction(buffer, 1L, currentOffset);

        buffer.flip();

        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();
        abortedTransactions.add(new FetchResponse.AbortedTransaction(1, 0));
        MemoryRecords records = MemoryRecords.readableRecords(buffer);
        subscriptions.assignFromUser(singleton(tp1));

        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        client.prepareResponse(fetchResponseWithAbortedTransactions(records, abortedTransactions, Errors.NONE, 100L, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchedRecords = fetcher.fetchedRecords();
        assertTrue(fetchedRecords.containsKey(tp1));
    }

    @Test
    public void testConsumerPositionUpdatedWhenSkippingAbortedTransactions() {
        Fetcher<byte[], byte[]> fetcher = createFetcher(subscriptions, new Metrics(), new ByteArrayDeserializer(),
                new ByteArrayDeserializer(), Integer.MAX_VALUE, IsolationLevel.READ_COMMITTED);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        long currentOffset = 0;

        currentOffset += appendTransactionalRecords(buffer, 1L, currentOffset,
                new SimpleRecord(time.milliseconds(), "abort1-1".getBytes(), "value".getBytes()),
                new SimpleRecord(time.milliseconds(), "abort1-2".getBytes(), "value".getBytes()));

        currentOffset += abortTransaction(buffer, 1L, currentOffset);
        buffer.flip();

        List<FetchResponse.AbortedTransaction> abortedTransactions = new ArrayList<>();
        abortedTransactions.add(new FetchResponse.AbortedTransaction(1, 0));
        MemoryRecords records = MemoryRecords.readableRecords(buffer);
        subscriptions.assignFromUser(singleton(tp1));

        subscriptions.seek(tp1, 0);

        // normal fetch
        assertEquals(1, fetcher.sendFetches());
        assertFalse(fetcher.hasCompletedFetches());

        client.prepareResponse(fetchResponseWithAbortedTransactions(records, abortedTransactions, Errors.NONE, 100L, 100L, 0));
        consumerClient.poll(0);
        assertTrue(fetcher.hasCompletedFetches());

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> fetchedRecords = fetcher.fetchedRecords();

        // Ensure that we don't return any of the aborted records, but yet advance the consumer position.
        assertFalse(fetchedRecords.containsKey(tp1));
        assertEquals(currentOffset, (long) subscriptions.position(tp1));
    }

    private int appendTransactionalRecords(ByteBuffer buffer, long pid, long baseOffset, int baseSequence, SimpleRecord... records) {
        MemoryRecordsBuilder builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE,
                TimestampType.CREATE_TIME, baseOffset, time.milliseconds(), pid, (short) 0, baseSequence, true,
                RecordBatch.NO_PARTITION_LEADER_EPOCH);

        for (SimpleRecord record : records) {
            builder.append(record);
        }
        builder.build();
        return records.length;
    }

    private int appendTransactionalRecords(ByteBuffer buffer, long pid, long baseOffset, SimpleRecord... records) {
        return appendTransactionalRecords(buffer, pid, baseOffset, (int) baseOffset, records);
    }

    private int commitTransaction(ByteBuffer buffer, long producerId, long baseOffset) {
        short producerEpoch = 0;
        int partitionLeaderEpoch = 0;
        MemoryRecords.writeEndTransactionalMarker(buffer, baseOffset, time.milliseconds(), partitionLeaderEpoch, producerId, producerEpoch,
                new EndTransactionMarker(ControlRecordType.COMMIT, 0));
        return 1;
    }

    private int abortTransaction(ByteBuffer buffer, long producerId, long baseOffset) {
        short producerEpoch = 0;
        int partitionLeaderEpoch = 0;
        MemoryRecords.writeEndTransactionalMarker(buffer, baseOffset, time.milliseconds(), partitionLeaderEpoch, producerId, producerEpoch,
                new EndTransactionMarker(ControlRecordType.ABORT, 0));
        return 1;
    }

    private void testGetOffsetsForTimesWithError(Errors errorForTp0,
                                                 Errors errorForTp1,
                                                 long offsetForTp0,
                                                 long offsetForTp1,
                                                 Long expectedOffsetForTp0,
                                                 Long expectedOffsetForTp1) {
        client.reset();
        TopicPartition tp0 = tp1;
        TopicPartition tp1 = new TopicPartition(topicName, 1);
        // Ensure metadata has both partition.
        Cluster cluster = TestUtils.clusterWith(2, topicName, 2);
        metadata.update(cluster, Collections.<String>emptySet(), time.milliseconds());

        // First try should fail due to metadata error.
        client.prepareResponseFrom(listOffsetResponse(tp0, errorForTp0, offsetForTp0, offsetForTp0), cluster.leaderFor(tp0));
        client.prepareResponseFrom(listOffsetResponse(tp1, errorForTp1, offsetForTp1, offsetForTp1), cluster.leaderFor(tp1));
        // Second try should succeed.
        client.prepareResponseFrom(listOffsetResponse(tp0, Errors.NONE, offsetForTp0, offsetForTp0), cluster.leaderFor(tp0));
        client.prepareResponseFrom(listOffsetResponse(tp1, Errors.NONE, offsetForTp1, offsetForTp1), cluster.leaderFor(tp1));

        Map<TopicPartition, Long> timestampToSearch = new HashMap<>();
        timestampToSearch.put(tp0, 0L);
        timestampToSearch.put(tp1, 0L);
        Map<TopicPartition, OffsetAndTimestamp> offsetAndTimestampMap = fetcher.getOffsetsByTimes(timestampToSearch, Long.MAX_VALUE);

        if (expectedOffsetForTp0 == null)
            assertNull(offsetAndTimestampMap.get(tp0));
        else {
            assertEquals(expectedOffsetForTp0.longValue(), offsetAndTimestampMap.get(tp0).timestamp());
            assertEquals(expectedOffsetForTp0.longValue(), offsetAndTimestampMap.get(tp0).offset());
        }

        if (expectedOffsetForTp1 == null)
            assertNull(offsetAndTimestampMap.get(tp1));
        else {
            assertEquals(expectedOffsetForTp1.longValue(), offsetAndTimestampMap.get(tp1).timestamp());
            assertEquals(expectedOffsetForTp1.longValue(), offsetAndTimestampMap.get(tp1).offset());
        }
    }

    private MockClient.RequestMatcher listOffsetRequestMatcher(final long timestamp) {
        // matches any list offset request with the provided timestamp
        return new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                ListOffsetRequest req = (ListOffsetRequest) body;
                return timestamp == req.partitionTimestamps().get(tp1);
            }
        };
    }

    private ListOffsetResponse listOffsetResponse(Errors error, long timestamp, long offset) {
        return listOffsetResponse(tp1, error, timestamp, offset);
    }

    private ListOffsetResponse listOffsetResponse(TopicPartition tp, Errors error, long timestamp, long offset) {
        ListOffsetResponse.PartitionData partitionData = new ListOffsetResponse.PartitionData(error, timestamp, offset);
        Map<TopicPartition, ListOffsetResponse.PartitionData> allPartitionData = new HashMap<>();
        allPartitionData.put(tp, partitionData);
        return new ListOffsetResponse(allPartitionData);
    }

    private FetchResponse fetchResponseWithAbortedTransactions(MemoryRecords records,
                                                               List<FetchResponse.AbortedTransaction> abortedTransactions,
                                                               Errors error,
                                                               long lastStableOffset,
                                                               long hw,
                                                               int throttleTime) {
        Map<TopicPartition, FetchResponse.PartitionData> partitions = Collections.singletonMap(tp1,
                new FetchResponse.PartitionData(error, hw, lastStableOffset, 0L, abortedTransactions, records));
        return new FetchResponse(new LinkedHashMap<>(partitions), throttleTime);
    }

    private FetchResponse fetchResponse(MemoryRecords records, Errors error, long hw, int throttleTime) {
        return fetchResponse(tp1, records, error, hw, throttleTime);
    }

    private FetchResponse fetchResponse(TopicPartition tp, MemoryRecords records, Errors error, long hw, int throttleTime) {
        Map<TopicPartition, FetchResponse.PartitionData> partitions = Collections.singletonMap(tp,
                new FetchResponse.PartitionData(error, hw, FetchResponse.INVALID_LAST_STABLE_OFFSET, 0L, null, records));
        return new FetchResponse(new LinkedHashMap<>(partitions), throttleTime);
    }

    private MetadataResponse newMetadataResponse(String topic, Errors error) {
        List<MetadataResponse.PartitionMetadata> partitionsMetadata = new ArrayList<>();
        if (error == Errors.NONE) {
            for (PartitionInfo partitionInfo : cluster.partitionsForTopic(topic)) {
                partitionsMetadata.add(new MetadataResponse.PartitionMetadata(
                        Errors.NONE,
                        partitionInfo.partition(),
                        partitionInfo.leader(),
                        Arrays.asList(partitionInfo.replicas()),
                        Arrays.asList(partitionInfo.inSyncReplicas())));
            }
        }

        MetadataResponse.TopicMetadata topicMetadata = new MetadataResponse.TopicMetadata(error, topic, false, partitionsMetadata);
        return new MetadataResponse(cluster.nodes(), null, MetadataResponse.NO_CONTROLLER_ID, Arrays.asList(topicMetadata));
    }

    private Fetcher<byte[], byte[]> createFetcher(SubscriptionState subscriptions,
                                                  Metrics metrics,
                                                  int maxPollRecords) {
        return createFetcher(subscriptions, metrics, new ByteArrayDeserializer(), new ByteArrayDeserializer(),
                maxPollRecords, IsolationLevel.READ_UNCOMMITTED);
    }

    private Fetcher<byte[], byte[]> createFetcher(SubscriptionState subscriptions, Metrics metrics) {
        return createFetcher(subscriptions, metrics, Integer.MAX_VALUE);
    }

    private <K, V> Fetcher<K, V> createFetcher(SubscriptionState subscriptions,
                                               Metrics metrics,
                                               Deserializer<K> keyDeserializer,
                                               Deserializer<V> valueDeserializer) {
        return createFetcher(subscriptions, metrics, keyDeserializer, valueDeserializer, Integer.MAX_VALUE,
                IsolationLevel.READ_UNCOMMITTED);
    }

    private <K, V> Fetcher<K, V> createFetcher(SubscriptionState subscriptions,
                                               Metrics metrics,
                                               Deserializer<K> keyDeserializer,
                                               Deserializer<V> valueDeserializer,
                                               int maxPollRecords,
                                               IsolationLevel isolationLevel) {
        return new Fetcher<>(consumerClient,
                minBytes,
                maxBytes,
                maxWaitMs,
                fetchSize,
                maxPollRecords,
                true, // check crc
                keyDeserializer,
                valueDeserializer,
                metadata,
                subscriptions,
                metrics,
                metricsRegistry,
                time,
                retryBackoffMs,
                isolationLevel);
    }

    private <T> List<Long> collectRecordOffsets(List<ConsumerRecord<T, T>> records) {
        List<Long> res = new ArrayList<>(records.size());
        for (ConsumerRecord<?, ?> record : records)
            res.add(record.offset());
        return res;
    }
}
