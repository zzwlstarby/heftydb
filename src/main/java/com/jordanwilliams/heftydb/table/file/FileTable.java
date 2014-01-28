/*
 * Copyright (c) 2013. Jordan Williams
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

package com.jordanwilliams.heftydb.table.file;

import com.jordanwilliams.heftydb.io.DataFile;
import com.jordanwilliams.heftydb.io.MutableDataFile;
import com.jordanwilliams.heftydb.offheap.ByteMap;
import com.jordanwilliams.heftydb.offheap.Memory;
import com.jordanwilliams.heftydb.read.LatestRecordIterator;
import com.jordanwilliams.heftydb.record.Key;
import com.jordanwilliams.heftydb.record.Record;
import com.jordanwilliams.heftydb.state.Paths;
import com.jordanwilliams.heftydb.table.Table;
import com.jordanwilliams.heftydb.util.Sizes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class FileTable implements Table {

    private class AscendingRecordBlockIterator implements Iterator<RecordBlock> {

        private final long maxOffset;
        private long fileOffset = 0;

        public AscendingRecordBlockIterator(long startOffset) {
            this.fileOffset = startOffset;

            try {
                this.maxOffset = tableFile.size() - Sizes.LONG_SIZE;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean hasNext() {
            return fileOffset < maxOffset;
        }

        @Override
        public RecordBlock next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            try {
                int nextBlockSize = tableFile.readInt(fileOffset);
                long nextBlockOffset = fileOffset + Sizes.INT_SIZE;

                fileOffset += Sizes.INT_SIZE;
                fileOffset += nextBlockSize;

                return readRecordBlock(nextBlockOffset, nextBlockSize, false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private class DescendingRecordBlockIterator implements Iterator<RecordBlock> {

        private long fileOffset;

        public DescendingRecordBlockIterator(long startOffset) {
            this.fileOffset = startOffset;
        }

        @Override
        public boolean hasNext() {
            return fileOffset >= 0;
        }

        @Override
        public RecordBlock next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            try {
                int nextBlockSize = tableFile.readInt(fileOffset);
                long nextBlockOffset = fileOffset + Sizes.INT_SIZE;

                fileOffset -= nextBlockSize;
                fileOffset -= Sizes.INT_SIZE;

                return readRecordBlock(nextBlockOffset, nextBlockSize, false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private class AscendingIterator implements Iterator<Record> {

        protected final Iterator<RecordBlock> recordBlockIterator;
        protected Iterator<Record> recordIterator;
        protected RecordBlock recordBlock;

        private AscendingIterator(Iterator<RecordBlock> recordBlockIterator, Iterator<Record> startIterator,
                                  RecordBlock startRecordBlock) {
            this.recordBlockIterator = recordBlockIterator;
            this.recordIterator = startIterator;
            this.recordBlock = startRecordBlock;
        }

        private AscendingIterator(Iterator<RecordBlock> recordBlockIterator) {
            this.recordBlockIterator = recordBlockIterator;
        }

        @Override
        public boolean hasNext() {
            try {
                if (recordIterator == null || !recordIterator.hasNext()) {
                    if (!nextRecordBlock()) {
                        return false;
                    }
                }

                if (recordIterator == null || !recordIterator.hasNext()) {
                    return false;
                }

                return true;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Record next() {
            if (recordIterator == null || !recordIterator.hasNext()) {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
            }

            return recordIterator.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        protected boolean nextRecordBlock() throws IOException {
            if (recordBlock != null) {
                recordBlock.memory().release();
            }

            if (!recordBlockIterator.hasNext()) {
                return false;
            }

            recordBlock = recordBlockIterator.next();
            recordIterator = recordBlock.ascendingIterator();

            return true;
        }
    }

    private class DescendingIterator extends AscendingIterator {

        private DescendingIterator(Iterator<RecordBlock> recordBlockIterator, Iterator<Record> startIterator,
                                   RecordBlock startRecordBlock) {
            super(recordBlockIterator, startIterator, startRecordBlock);
        }

        private DescendingIterator(Iterator<RecordBlock> recordBlockIterator) {
            super(recordBlockIterator);
        }

        @Override
        protected boolean nextRecordBlock() throws IOException {
            if (recordBlock != null) {
                recordBlock.memory().release();
            }

            if (!recordBlockIterator.hasNext()) {
                return false;
            }

            recordBlock = recordBlockIterator.next();
            recordIterator = recordBlock.descendingIterator();

            return true;
        }
    }

    private final long tableId;
    private final Index index;
    private final TableBloomFilter tableBloomFilter;
    private final MetaTable metaTable;
    private final RecordBlock.Cache recordCache;
    private final DataFile tableFile;

    private FileTable(long tableId, Index index, TableBloomFilter tableBloomFilter, DataFile tableFile,
                      MetaTable metaTable, RecordBlock.Cache recordCache, IndexBlock.Cache indexCache) throws
            IOException {
        this.tableId = tableId;
        this.recordCache = recordCache;
        this.index = index;
        this.tableBloomFilter = tableBloomFilter;
        this.tableFile = tableFile;
        this.metaTable = metaTable;
    }

    @Override
    public long id() {
        return tableId;
    }

    @Override
    public boolean mightContain(Key key) {
        return tableBloomFilter.mightContain(key);
    }

    @Override
    public Record get(Key key) {
        try {
            IndexRecord indexRecord = index.get(key);

            if (indexRecord.blockOffset() < 0) {
                return null;
            }

            RecordBlock recordBlock = readRecordBlock(indexRecord.blockOffset(), indexRecord.blockSize());
            return recordBlock.get(key);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterator<Record> ascendingIterator(long snapshotId) {
        return new LatestRecordIterator(snapshotId, new AscendingIterator(new AscendingRecordBlockIterator(0)));
    }

    @Override
    public Iterator<Record> descendingIterator(long snapshotId) {
        try {
            long startOffset = tableFile.readLong(tableFile.size() - Sizes.LONG_SIZE);
            return new LatestRecordIterator(snapshotId, new DescendingIterator(new DescendingRecordBlockIterator
                    (startOffset)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterator<Record> ascendingIterator(Key key, long snapshotId) {
        try {
            IndexRecord indexRecord = index.get(key);
            RecordBlock startRecordBlock = readRecordBlock(indexRecord.blockOffset(), indexRecord.blockSize(), false);
            Iterator<Record> startRecordIterator = startRecordBlock.ascendingIterator(key);
            return new LatestRecordIterator(snapshotId, new AscendingIterator(new AscendingRecordBlockIterator
                    (indexRecord.blockOffset() + indexRecord.blockSize()), startRecordIterator, startRecordBlock));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterator<Record> descendingIterator(Key key, long snapshotId) {
        try {
            IndexRecord indexRecord = index.get(key);
            RecordBlock startRecordBlock = readRecordBlock(indexRecord.blockOffset(), indexRecord.blockSize(), false);
            Iterator<Record> startRecordIterator = startRecordBlock.descendingIterator(key);
            return new LatestRecordIterator(snapshotId, new DescendingIterator(new DescendingRecordBlockIterator
                    (indexRecord.blockOffset() - indexRecord.blockSize() - Sizes.LONG_SIZE), startRecordIterator,
                    startRecordBlock));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long recordCount() {
        return metaTable.recordCount();
    }

    @Override
    public long size() {
        return metaTable.size();
    }

    @Override
    public int level() {
        return metaTable.level();
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public Iterator<Record> iterator() {
        return new AscendingIterator(new AscendingRecordBlockIterator(0));
    }

    private RecordBlock readRecordBlock(long offset, int size) throws IOException {
        return readRecordBlock(offset, size, true);
    }

    private RecordBlock readRecordBlock(long offset, int size, boolean shouldCache) throws IOException {
        RecordBlock recordBlock = recordCache.get(tableId, offset);

        if (recordBlock == null) {
            Memory recordBlockMemory = Memory.allocate(size);

            try {
                ByteBuffer recordBlockBuffer = recordBlockMemory.directBuffer();
                tableFile.read(recordBlockBuffer, offset);
                recordBlockBuffer.rewind();
                recordBlock = new RecordBlock(new ByteMap(recordBlockMemory));

                if (shouldCache) {
                    recordCache.put(tableId, offset, recordBlock);
                }
            } catch (IOException e) {
                recordBlockMemory.release();
                throw e;
            }
        }

        return recordBlock;
    }

    public static FileTable open(long tableId, Paths paths, RecordBlock.Cache recordCache,
                                 IndexBlock.Cache indexCache) throws IOException {
        Index index = Index.open(tableId, paths, indexCache);
        TableBloomFilter tableBloomFilter = TableBloomFilter.open(tableId, paths);
        DataFile tableFile = MutableDataFile.open(paths.tablePath(tableId));
        MetaTable metaTable = MetaTable.open(tableId, paths);
        return new FileTable(tableId, index, tableBloomFilter, tableFile, metaTable, recordCache, indexCache);
    }
}
