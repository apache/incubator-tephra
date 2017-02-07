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

package org.apache.tephra.hbase.txprune;

import com.google.common.util.concurrent.AbstractIdleService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

/**
 * Thread that will write the the prune upper bound. An instance of this class should be obtained only
 * through {@link PruneUpperBoundWriterSupplier} which will also handle the lifecycle of this instance.
 */
public class PruneUpperBoundWriter extends AbstractIdleService {
  private static final Log LOG = LogFactory.getLog(PruneUpperBoundWriter.class);

  private final TableName tableName;
  private final DataJanitorState dataJanitorState;
  private final long pruneFlushInterval;
  private final ConcurrentSkipListMap<byte[], Long> pruneEntries;

  private volatile Thread flushThread;

  private long lastChecked;

  public PruneUpperBoundWriter(TableName tableName, DataJanitorState dataJanitorState, long pruneFlushInterval) {
    this.tableName = tableName;
    this.dataJanitorState = dataJanitorState;
    this.pruneFlushInterval = pruneFlushInterval;
    this.pruneEntries = new ConcurrentSkipListMap<>(Bytes.BYTES_COMPARATOR);
  }

  public void persistPruneEntry(byte[] regionName, long pruneUpperBound) {
    // The number of entries in this map is bound by the number of regions in this region server and thus it will not
    // grow indefinitely
    pruneEntries.put(regionName, pruneUpperBound);
  }

  public boolean isAlive() {
    return flushThread != null && flushThread.isAlive();
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting PruneUpperBoundWriter Thread.");
    startFlushThread();
  }

  @Override
  protected void shutDown() throws Exception {
    LOG.info("Stopping PruneUpperBoundWriter Thread.");
    if (flushThread != null) {
      flushThread.interrupt();
      flushThread.join(TimeUnit.SECONDS.toMillis(1));
    }
  }

  private void startFlushThread() {
    flushThread = new Thread("tephra-prune-upper-bound-writer") {
      @Override
      public void run() {
        while ((!isInterrupted()) && isRunning()) {
          long now = System.currentTimeMillis();
          if (now > (lastChecked + pruneFlushInterval)) {
            // should flush data
            try {
              while (pruneEntries.firstEntry() != null) {
                Map.Entry<byte[], Long> firstEntry = pruneEntries.firstEntry();
                dataJanitorState.savePruneUpperBoundForRegion(firstEntry.getKey(), firstEntry.getValue());
                // We can now remove the entry only if the key and value match with what we wrote since it is
                // possible that a new pruneUpperBound for the same key has been added
                pruneEntries.remove(firstEntry.getKey(), firstEntry.getValue());
              }
            } catch (IOException ex) {
              LOG.warn("Cannot record prune upper bound for a region to table " +
                         tableName.getNamespaceAsString() + ":" + tableName.getNameAsString(), ex);
            }
            lastChecked = now;
          }

          try {
            TimeUnit.SECONDS.sleep(1);
          } catch (InterruptedException ex) {
            interrupt();
            break;
          }
        }

        LOG.info("PruneUpperBound Writer thread terminated.");
      }
    };

    flushThread.setDaemon(true);
    flushThread.start();
  }
}
