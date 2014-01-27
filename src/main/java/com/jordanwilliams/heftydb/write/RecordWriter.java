/*
 * Copyright (c) 2014. Jordan Williams
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

package com.jordanwilliams.heftydb.write;

import com.jordanwilliams.heftydb.log.WriteLog;
import com.jordanwilliams.heftydb.record.Record;
import com.jordanwilliams.heftydb.state.State;
import com.jordanwilliams.heftydb.table.memory.MemoryTable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordWriter {

    private final State state;
    private final ExecutorService tableExecutor;

    private MemoryTable memoryTable;
    private WriteLog writeLog;

    public RecordWriter(State state) {
        this.state = state;
        this.tableExecutor = Executors.newFixedThreadPool(state.config().tableWriterThreads());
    }

    public void write(Record record) throws IOException {
        if (memoryTable == null || memoryTable.size() >= state.config().memoryTableSize()) {
            rotateMemoryTable();
        }

        writeLog.append(record);
        memoryTable.put(record);
    }

    private synchronized void rotateMemoryTable() throws IOException {
        if (memoryTable != null) {
            final long currentTableId = memoryTable.id();

            tableExecutor.submit(new FileTableWriter.Task(memoryTable.id(), 1, state, memoryTable.ascendingIterator(state.snapshots().currentId()), memoryTable.recordCount(), new FileTableWriter.Task.Callback() {
                @Override
                public void finish() {
                    try {
                        Files.deleteIfExists(state.files().logPath(currentTableId));
                    } catch (IOException e){
                        throw new RuntimeException(e);
                    }
                }
            }));
        }

        long nextTableId = state.tables().nextId();
        memoryTable = new MemoryTable(nextTableId);
        writeLog = WriteLog.open(nextTableId, state.files());
    }
}