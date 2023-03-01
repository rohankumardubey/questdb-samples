/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb;

import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.pool.PoolListener;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.SOCountDownLatch;
import io.questdb.std.Files;
import io.questdb.std.str.Path;
import org.postgresql.util.PSQLException;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Properties;

public class SameProcess {
    private static final Log LOG = LogFactory.getLog(SameProcess.class);

    private static final String USE_DEFAULT_LOG_FACTORY = Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION;
    private static final Properties PG_CONN_PROPS = new Properties();
    private static final String PG_CONN_URI = "jdbc:postgresql://127.0.0.1:8812/qdb";

    static {
        PG_CONN_PROPS.setProperty("user", "admin");
        PG_CONN_PROPS.setProperty("password", "quest");
    }

    private static void configureQuestDB(String rootDir) throws Exception {
        try (Path path = new Path().of(rootDir).concat("conf").slash$()) {
            // slash dollar at the end, so that 'mkdirs' creates both dirName and conf
            if (!Files.exists(path)) {
                if (0 != Files.mkdirs(path, 509)) { //  509 in binary 111 111 101 (rwx rwx r-x)
                    System.exit(1); // fail!!
                }

                // Property descriptions: https://questdb.io/docs/reference/configuration/
                try (PrintWriter writer = new PrintWriter(path.put("server.conf").$().toString(), StandardCharsets.UTF_8)) {
                    // disable services
                    writer.println("http.query.cache.enabled=false");
                    writer.println("pg.select.cache.enabled=false");
                    writer.println("pg.insert.cache.enabled=false");
                    writer.println("pg.update.cache.enabled=false");
                    writer.println("cairo.wal.enabled.default=false");
                    writer.println("metrics.enabled=false");
                    writer.println("telemetry.enabled=false");

                    // configure worker pools
                    writer.println("shared.worker.count=2");
                    writer.println("http.worker.count=1");
                    writer.println("http.min.worker.count=1");
                    writer.println("pg.worker.count=1");
                    writer.println("line.tcp.writer.worker.count=1");
                    writer.println("line.tcp.io.worker.count=1");

                    // cairo engine
                    writer.println("cairo.o3.max.lag=10000");
                    writer.println("cairo.o3.min.lag=2000");
                }

                // configure logging: https://questdb.io/docs/reference/configuration/#logging
                path.parent().concat("log.conf").$();
                String file = path.toString();
                System.setProperty("out", file);
                try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
                    writer.println("writers=file"); // list of configured writers
                    // stdout
                    writer.println("w.stdout.class=io.questdb.log.LogConsoleWriter");
                    writer.println("w.stdout.level=INFO,ERROR");
                    // file writer
                    writer.println("w.file.class=io.questdb.log.LogFileWriter");
                    writer.println("w.file.location=questdb-same-process.log");
                    writer.println("w.file.level=INFO,ERROR");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {

        final String rootDir = "QuestDB_Root";
        final String tableName = "trades";

        configureQuestDB(rootDir);
        try (final ServerMain qdb = new ServerMain("-d", rootDir, USE_DEFAULT_LOG_FACTORY)) {
            qdb.start();

            // we would like to know when the table writer is released, which means
            // the data has been inserted and is available for reading.
            SOCountDownLatch dataReady = new SOCountDownLatch(1);
            CairoEngine engine = qdb.getCairoEngine();
            engine.setPoolListener((factoryType, thread, tableToken, event, segment, position) -> {
                boolean isOurTable = tableToken != null && tableToken.getTableName().equals(tableName);
                if (isOurTable) {
                    LOG.info().$("TABLE: ").$(tableToken != null ? tableToken.getTableName() : "null")
                            .$(", factoryType: ").$(factoryType)
                            .$(", event: ").$(event)
                            .$();
                    if (isOurTable && factoryType == PoolListener.SRC_WRITER && event == PoolListener.EV_UNLOCKED) {
                        dataReady.countDown();
                    }
                }
            });

            try (Connection conn = DriverManager.getConnection(PG_CONN_URI, PG_CONN_PROPS)) {
                conn.setAutoCommit(false);

                // create table - insert random data
                try (PreparedStatement createTable = conn.prepareStatement(String.format("""
                        CREATE TABLE %s AS (
                            SELECT
                                rnd_symbol('EURO', 'USD', 'OTHER') symbol,
                                rnd_double() * 50.0 price,
                                rnd_double() * 20.0 amount,
                                to_timestamp('2022-12-30', 'yyyy-MM-dd') + x * 60 * 100000 timestamp
                            FROM long_sequence(4000000)
                        ), INDEX(symbol capacity 128) TIMESTAMP(timestamp) PARTITION BY MONTH;
                        """, tableName))) {
                    createTable.execute();
                    conn.commit();
                    dataReady.await();
                    LOG.info().$("Data ready").$();
                } catch (PSQLException ignore) {
                    LOG.info().$("Data already exists").$();
                    dataReady.countDown();
                }

                // run a select query
                final long start = System.currentTimeMillis();
                try (PreparedStatement select = conn.prepareStatement(String.format("""
                        SELECT * FROM %s
                        WHERE symbol='EURO' AND price > 49.99 AND amount > 15.0 AND timestamp BETWEEN '2022-12' AND '2023-02';
                        """, tableName));
                     ResultSet rs = select.executeQuery()) {
                    LOG.info().$("Executed select query").$();
                    while (rs.next()) {
                        String symbol = rs.getString(1);
                        double price = rs.getDouble(2);
                        double amount = rs.getDouble(3);
                        Timestamp timestamp = rs.getTimestamp(4);
                        LOG.info().$(symbol).$(" [price=").$(price)
                                .$(", amount=").$(amount)
                                .$(", timestamp=").$ts(timestamp.getTime() * 1000L).$(" - ").$(timestamp.toString())
                                .I$();
                    }
                    LOG.info().$("Took: ").$(System.currentTimeMillis() - start).$(" ms").$();
                }
            }
        }
    }
}
