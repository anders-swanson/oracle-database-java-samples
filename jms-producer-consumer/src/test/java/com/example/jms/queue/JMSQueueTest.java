package com.example.jms.queue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.MountableFile;

import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers
public class JMSQueueTest {
    private static final String oracleImage = "gvenzl/oracle-free:23.8-slim-faststart";
    private static final String testUser = "testuser";
    private static final String testPassword = "Welcome123#";

    private static final String queueName = "myqueue";

    @Container
    private static final OracleContainer oracleContainer = new OracleContainer(oracleImage)
            .withStartupTimeout(Duration.ofMinutes(3)) // allow possible slow startup
            .withInitScripts(
                    "create-table.sql"
            )
            .withUsername(testUser)
            .withPassword(testPassword);

    private static DataSource dataSource;

    private static List<String> input;

    @BeforeAll
    static void setUp() throws Exception {
        // Configure the Oracle Database container with the TxEventQ test user.
        oracleContainer.start();
        oracleContainer.copyFileToContainer(MountableFile.forClasspathResource("testuser-queue.sql"), "/tmp/init.sql");
        oracleContainer.execInContainer("sqlplus", "sys / as sysdba", "@/tmp/init.sql");

        dataSource = getDataSource();

        input = Files.readAllLines(Paths.get("src", "test", "resources", "producer-events.txt"));
    }

    @Test
    void produceConsume() throws Exception {
        // Used for tracking the number of messages consumed. Once all messages have been consumed and the latch is empty,
        // the test completes.
        AtomicInteger count = new AtomicInteger(input.size());

        // Create an executor to submit producer and consumer threads.
        ExecutorService executor = newVirtualThreadPerTaskExecutor();

        // Number of consumer threads.
        final int consumerThreads = 3;
        List<Future<?>> consumers = new ArrayList<>();
        // Start the consumer thread(s) concurrently.
        for (int i = 0; i < consumerThreads; i++) {
            consumers.add(executor.submit(getConsumer(i + 1, count)));
        }

        // Start the producer thread.
        executor.submit(getProducer());

        // Wait for the consumer(s) to receive all messages.
        for (Future<?> consumer : consumers) {
            consumer.get();
        }

        // Verify consumer inserted all the messages to the weather_events database table.
        verifyEventsSent(input.size());
    }

    private void verifyEventsSent(int count) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            String sql = "select count(*) from weather_events";
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                assertThat(count).isEqualTo(rs.getInt(1));
            } else {
                fail("no records found");
            }
        }
    }

    private QueueProducer getProducer() {
        return new QueueProducer(
                dataSource,
                testUser,
                queueName,
                input
        );
    }

    private QueueConsumer getConsumer(int id, AtomicInteger count) {
        return new QueueConsumer(
                id,
                dataSource,
                testUser,
                queueName,
                count
        );
    }

    private static DataSource getDataSource() throws SQLException {
        PoolDataSource ds = PoolDataSourceFactory.getPoolDataSource();
        ds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
        ds.setConnectionPoolName(UUID.randomUUID().toString());
        ds.setURL(oracleContainer.getJdbcUrl());
        ds.setUser(oracleContainer.getUsername());
        ds.setPassword(oracleContainer.getPassword());
        ds.setConnectionPoolName(UUID.randomUUID().toString());
        ds.setMaxPoolSize(30);
        ds.setInitialPoolSize(10);
        ds.setMinPoolSize(1);

        return ds;
    }
}
