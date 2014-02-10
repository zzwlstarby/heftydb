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

package com.jordanwilliams.heftydb.aggregate;

import com.jordanwilliams.heftydb.util.PeekableIterator;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Queue;

public class MergingIterator<T extends Comparable> implements Iterator<T> {

    private static class ComparableIterator<T extends Comparable> implements PeekableIterator<T>,
            Comparable<ComparableIterator<T>> {

        private final Iterator<T> delegate;
        private T current;

        private ComparableIterator(Iterator<T> delegate) {
            this.delegate = delegate;

            if (delegate.hasNext()) {
                current = delegate.next();
            }
        }

        @Override
        public T current() {
            return current;
        }

        @Override
        public boolean advance() {
            if (delegate.hasNext()) {
                current = delegate.next();
                return true;
            }

            return false;
        }

        @Override
        public int compareTo(ComparableIterator<T> other) {
            return current.compareTo(other.current);
        }
    }

    private final Queue<T> next = new LinkedList<T>();
    private final PriorityQueue<ComparableIterator<T>> iteratorHeap;

    public MergingIterator(List<Iterator<T>> iterators) {
        this.iteratorHeap = new PriorityQueue<ComparableIterator<T>>();
        buildIteratorHeap(iterators);
    }

    public MergingIterator(Iterator<T>... iterators) {
        this(Arrays.asList(iterators));
    }

    public MergingIterator(boolean descending, Iterator<T>... iterators) {
        this(descending, Arrays.asList(iterators));
    }

    public MergingIterator(boolean descending, List<Iterator<T>> iterators) {
        this.iteratorHeap = descending ? new PriorityQueue<ComparableIterator<T>>(iterators.size(),
                Collections.reverseOrder()) : new PriorityQueue<ComparableIterator<T>>();
        buildIteratorHeap(iterators);
    }

    @Override
    public boolean hasNext() {
        if (!next.isEmpty()) {
            return true;
        }

        if (iteratorHeap.isEmpty()) {
            return false;
        }

        ComparableIterator<T> nextIterator = iteratorHeap.poll();
        T nextCandidate = nextIterator.current();

        if (nextCandidate != null) {
            next.add(nextCandidate);
        }

        if (nextIterator.advance()) {
            iteratorHeap.add(nextIterator);
        }

        if (next.isEmpty()) {
            return false;
        }

        return true;
    }

    @Override
    public T next() {
        if (next.isEmpty()) {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
        }

        return next.poll();
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    private void buildIteratorHeap(List<Iterator<T>> iteratorList) {
        for (Iterator<T> iterator : iteratorList) {
            if (iterator.hasNext()) {
                iteratorHeap.add(new ComparableIterator<T>(iterator));
            }
        }
    }
}
