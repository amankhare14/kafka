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
package org.apache.kafka.connect.mirror.integration;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.mirror.MirrorMaker;
import org.apache.kafka.connect.util.clusters.EmbeddedKafkaCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.test.TestUtils.waitForCondition;

@Tag("integration")
public class DedicatedMirrorIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DedicatedMirrorIntegrationTest.class);

    private static final int TOPIC_CREATION_TIMEOUT_MS = 120_000;
    private static final int TOPIC_REPLICATION_TIMEOUT_MS = 120_000;

    private Map<String, EmbeddedKafkaCluster> kafkaClusters;
    private Map<String, MirrorMaker> mirrorMakers;

    @BeforeEach
    public void setup() {
        kafkaClusters = new HashMap<>();
        mirrorMakers = new HashMap<>();
    }

    @AfterEach
    public void teardown() throws Throwable {
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        mirrorMakers.forEach((name, mirrorMaker) ->
            Utils.closeQuietly(mirrorMaker::stop, "MirrorMaker worker '" + name + "'", shutdownFailure)
        );
        kafkaClusters.forEach((name, kafkaCluster) ->
            Utils.closeQuietly(kafkaCluster::stop, "Embedded Kafka cluster '" + name + "'", shutdownFailure)
        );
        if (shutdownFailure.get() != null) {
            throw shutdownFailure.get();
        }
    }

    private EmbeddedKafkaCluster startKafkaCluster(String name, int numBrokers, Properties brokerProperties) {
        if (kafkaClusters.containsKey(name))
            throw new IllegalStateException("Cannot register multiple Kafka clusters with the same name");

        EmbeddedKafkaCluster result = new EmbeddedKafkaCluster(numBrokers, brokerProperties);
        kafkaClusters.put(name, result);

        result.start();

        return result;
    }

    private MirrorMaker startMirrorMaker(String name, Map<String, String> mmProps) {
        if (mirrorMakers.containsKey(name))
            throw new IllegalStateException("Cannot register multiple MirrorMaker nodes with the same name");

        MirrorMaker result = new MirrorMaker(mmProps);
        mirrorMakers.put(name, result);

        result.start();

        return result;
    }

    /**
     * Tests a single-node cluster without the REST server enabled.
     */
    @Test
    public void testSingleNodeCluster() throws Exception {
        Properties brokerProps = new Properties();
        EmbeddedKafkaCluster clusterA = startKafkaCluster("A", 1, brokerProps);
        EmbeddedKafkaCluster clusterB = startKafkaCluster("B", 1, brokerProps);

        clusterA.start();
        clusterB.start();

        try (Admin adminA = clusterA.createAdminClient();
             Admin adminB = clusterB.createAdminClient()) {

            // Cluster aliases
            final String a = "A";
            final String b = "B";
            final String ab = a + "->" + b;
            final String ba = b + "->" + a;
            final String testTopicPrefix = "test-topic-";

            Map<String, String> mmProps = new HashMap<String, String>() {{
                    put("dedicated.mode.enable.internal.rest", "false");
                    put("listeners", "http://localhost:0");
                    // Refresh topics very frequently to quickly pick up on topics that are created
                    // after the MM2 nodes are brought up during testing
                    put("refresh.topics.interval.seconds", "1");
                    put("clusters", String.join(", ", a, b));
                    put(a + ".bootstrap.servers", clusterA.bootstrapServers());
                    put(b + ".bootstrap.servers", clusterB.bootstrapServers());
                    put(ab + ".enabled", "true");
                    put(ab + ".topics", "^" + testTopicPrefix + ".*");
                    put(ba + ".enabled", "false");
                    put(ba + ".emit.heartbeats.enabled", "false");
                    put("replication.factor", "1");
                    put("checkpoints.topic.replication.factor", "1");
                    put("heartbeats.topic.replication.factor", "1");
                    put("offset-syncs.topic.replication.factor", "1");
                    put("offset.storage.replication.factor", "1");
                    put("status.storage.replication.factor", "1");
                    put("config.storage.replication.factor", "1");
                }};

            // Bring up a single-node cluster
            startMirrorMaker("single node", mmProps);

            final int numMessages = 10;
            String topic = testTopicPrefix + "1";

            // Create the topic on cluster A
            createTopic(adminA, topic);
            // and wait for MirrorMaker to create it on cluster B
            awaitTopicCreation(b, adminB, a + "." + topic);

            // Write data to the topic on cluster A
            writeToTopic(clusterA, topic, numMessages);
            // and wait for MirrorMaker to copy it to cluster B
            awaitTopicContent(clusterB, b, a + "." + topic, numMessages);
        }
    }

    /**
     * Test that a multi-node dedicated cluster is able to dynamically detect new topics at runtime
     * and reconfigure its connectors and their tasks to replicate those topics correctly.
     * See <a href="https://cwiki.apache.org/confluence/display/KAFKA/KIP-710%3A+Full+support+for+distributed+mode+in+dedicated+MirrorMaker+2.0+clusters">KIP-710</a>
     * for more detail on the necessity for this test case.
     */
    @Test
    public void testMultiNodeCluster() throws Exception {
        Properties brokerProps = new Properties();
        brokerProps.put("transaction.state.log.replication.factor", "1");
        brokerProps.put("transaction.state.log.min.isr", "1");
        EmbeddedKafkaCluster clusterA = startKafkaCluster("A", 1, brokerProps);
        EmbeddedKafkaCluster clusterB = startKafkaCluster("B", 1, brokerProps);

        clusterA.start();
        clusterB.start();

        try (Admin adminA = clusterA.createAdminClient();
                Admin adminB = clusterB.createAdminClient()) {

            // Cluster aliases
            final String a = "A";
            // Use a convoluted cluster name to ensure URL encoding/decoding works
            final String b = "B- ._~:/?#[]@!$&'()*+;=\"<>%{}|\\^`618";
            final String ab = a + "->" + b;
            final String ba = b + "->" + a;
            final String testTopicPrefix = "test-topic-";

            Map<String, String> mmProps = new HashMap<String, String>() {{
                    put("dedicated.mode.enable.internal.rest", "true");
                    put("listeners", "http://localhost:0");
                    // Refresh topics very frequently to quickly pick up on topics that are created
                    // after the MM2 nodes are brought up during testing
                    put("refresh.topics.interval.seconds", "1");
                    put("clusters", String.join(", ", a, b));
                    put(a + ".bootstrap.servers", clusterA.bootstrapServers());
                    put(b + ".bootstrap.servers", clusterB.bootstrapServers());
                    // Enable exactly-once support to both validate that MirrorMaker can run with
                    // that feature turned on, and to force cross-worker communication before
                    // task startup
                    put(b + ".exactly.once.source.support", "enabled");
                    put(a + ".consumer.isolation.level", "read_committed");
                    put(ab + ".enabled", "true");
                    put(ab + ".topics", "^" + testTopicPrefix + ".*");
                    // The name of the offset syncs topic will contain the name of the cluster in
                    // the replication flow that it is _not_ hosted on; create the offset syncs topic
                    // on the target cluster so that its name will contain the source cluster's name
                    // (since the target cluster's name contains characters that are not valid for
                    // use in a topic name)
                    put(ab + ".offset-syncs.topic.location", "target");
                    // Disable b -> a (and heartbeats from it) so that no topics are created that use
                    // the target cluster's name
                    put(ba + ".enabled", "false");
                    put(ba + ".emit.heartbeats.enabled", "false");
                    put("replication.factor", "1");
                    put("checkpoints.topic.replication.factor", "1");
                    put("heartbeats.topic.replication.factor", "1");
                    put("offset-syncs.topic.replication.factor", "1");
                    put("offset.storage.replication.factor", "1");
                    put("status.storage.replication.factor", "1");
                    put("config.storage.replication.factor", "1");
                }};

            // Bring up a three-node cluster
            final int numNodes = 3;
            for (int i = 0; i < numNodes; i++) {
                startMirrorMaker("node " + i, mmProps);
            }

            // Create one topic per Kafka cluster per MirrorMaker node
            final int topicsPerCluster = numNodes;
            final int messagesPerTopic = 10;
            for (int i = 0; i < topicsPerCluster; i++) {
                String topic = testTopicPrefix + i;

                // Create the topic on cluster A
                createTopic(adminA, topic);
                // and wait for MirrorMaker to create it on cluster B
                awaitTopicCreation(b, adminB, a + "." + topic);

                // Write data to the topic on cluster A
                writeToTopic(clusterA, topic, messagesPerTopic);
                // and wait for MirrorMaker to copy it to cluster B
                awaitTopicContent(clusterB, b, a + "." + topic, messagesPerTopic);
            }
        }
    }

    private void createTopic(Admin admin, String name) throws Exception {
        admin.createTopics(Collections.singleton(new NewTopic(name, 1, (short) 1))).all().get();
    }

    private void awaitTopicCreation(String clusterName, Admin admin, String topic) throws Exception {
        waitForCondition(
                () -> {
                    try {
                        Set<String> allTopics = admin.listTopics().names().get();
                        return allTopics.contains(topic);
                    } catch (Exception e) {
                        log.debug("Failed to check for existence of topic {} on cluster {}", topic, clusterName, e);
                        return false;
                    }
                },
                TOPIC_CREATION_TIMEOUT_MS,
                "topic " + topic + " was not created on cluster " + clusterName + " in time"
        );
    }

    private void writeToTopic(EmbeddedKafkaCluster cluster, String topic, int numMessages) {
        for (int i = 0; i <= numMessages; i++) {
            cluster.produce(topic, Integer.toString(i));
        }
    }

    private void awaitTopicContent(EmbeddedKafkaCluster cluster, String clusterName, String topic, int numMessages) throws Exception {
        try (Consumer<?, ?> consumer = cluster.createConsumer(Collections.singletonMap(AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            consumer.subscribe(Collections.singleton(topic));
            AtomicInteger messagesRead = new AtomicInteger(0);
            waitForCondition(
                    () -> {
                        ConsumerRecords<?, ?> records = consumer.poll(Duration.ofSeconds(1));
                        return messagesRead.addAndGet(records.count()) >= numMessages;
                    },
                    TOPIC_REPLICATION_TIMEOUT_MS,
                    () -> "could not read " + numMessages + " from topic " + topic + " on cluster " + clusterName + " in time; only read " + messagesRead.get()
            );
        }
    }

}
