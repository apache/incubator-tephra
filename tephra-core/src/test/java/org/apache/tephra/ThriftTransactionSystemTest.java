/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tephra;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Scopes;
import com.google.inject.util.Modules;
import org.apache.hadoop.conf.Configuration;
import org.apache.tephra.distributed.TransactionService;
import org.apache.tephra.persist.InMemoryTransactionStateStorage;
import org.apache.tephra.persist.TransactionStateStorage;
import org.apache.tephra.runtime.ConfigModule;
import org.apache.tephra.runtime.DiscoveryModules;
import org.apache.tephra.runtime.TransactionClientModule;
import org.apache.tephra.runtime.TransactionModules;
import org.apache.tephra.runtime.ZKModule;
import org.apache.tephra.util.Tests;
import org.apache.twill.internal.zookeeper.InMemoryZKServer;
import org.apache.twill.zookeeper.ZKClientService;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThriftTransactionSystemTest extends TransactionSystemTest {
  private static final Logger LOG = LoggerFactory.getLogger(ThriftTransactionSystemTest.class);
  
  private static InMemoryZKServer zkServer;
  private static ZKClientService zkClientService;
  private static TransactionService txService;
  private static TransactionStateStorage storage;
  private static TransactionSystemClient txClient;

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();
  
  @BeforeClass
  public static void start() throws Exception {
    zkServer = InMemoryZKServer.builder().setDataDir(tmpFolder.newFolder()).build();
    zkServer.startAndWait();

    Configuration conf = new Configuration();
    conf.setBoolean(TxConstants.Manager.CFG_DO_PERSIST, false);
    conf.set(TxConstants.Service.CFG_DATA_TX_ZOOKEEPER_QUORUM, zkServer.getConnectionStr());
    conf.set(TxConstants.Service.CFG_DATA_TX_CLIENT_RETRY_STRATEGY, "n-times");
    conf.setInt(TxConstants.Service.CFG_DATA_TX_CLIENT_ATTEMPTS, 1);

    Injector injector = Guice.createInjector(
      new ConfigModule(conf),
      new ZKModule(),
      new DiscoveryModules().getDistributedModules(),
      Modules.override(new TransactionModules().getDistributedModules())
        .with(new AbstractModule() {
          @Override
          protected void configure() {
            bind(TransactionStateStorage.class).to(InMemoryTransactionStateStorage.class).in(Scopes.SINGLETON);
          }
        }),
      new TransactionClientModule()
    );

    zkClientService = injector.getInstance(ZKClientService.class);
    zkClientService.startAndWait();

    // start a tx server
    txService = injector.getInstance(TransactionService.class);
    storage = injector.getInstance(TransactionStateStorage.class);
    txClient = injector.getInstance(TransactionSystemClient.class);
    try {
      LOG.info("Starting transaction service");
      storage.startAndWait();
      txService.startAndWait();
    } catch (Exception e) {
      LOG.error("Failed to start service: ", e);
      throw e;
    }

    Tests.waitForTxReady(txClient);
  }
  
  @Before
  public void reset() throws Exception {
    getClient().resetState();
  }
  
  @AfterClass
  public static void stop() throws Exception {
    txService.stopAndWait();
    storage.stopAndWait();
    zkClientService.stopAndWait();
    zkServer.stopAndWait();
  }
  
  @Override
  protected TransactionSystemClient getClient() throws Exception {
    return txClient;
  }

  @Override
  protected TransactionStateStorage getStateStorage() throws Exception {
    return storage;
  }
}
