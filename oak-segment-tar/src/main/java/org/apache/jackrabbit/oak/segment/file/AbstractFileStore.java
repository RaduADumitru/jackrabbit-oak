/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.segment.file;

import static org.apache.jackrabbit.oak.segment.SegmentCache.newSegmentCache;
import static org.apache.jackrabbit.oak.segment.data.SegmentData.newSegmentData;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.jackrabbit.oak.api.jmx.CacheStatsMBean;
import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.CachingSegmentReader;
import org.apache.jackrabbit.oak.segment.RecordType;
import org.apache.jackrabbit.oak.segment.Revisions;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.Segment.RecordConsumer;
import org.apache.jackrabbit.oak.segment.SegmentBlob;
import org.apache.jackrabbit.oak.segment.SegmentBufferMonitor;
import org.apache.jackrabbit.oak.segment.SegmentCache;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentIdFactory;
import org.apache.jackrabbit.oak.segment.SegmentIdProvider;
import org.apache.jackrabbit.oak.segment.SegmentNodeState;
import org.apache.jackrabbit.oak.segment.SegmentNotFoundException;
import org.apache.jackrabbit.oak.segment.SegmentReader;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.apache.jackrabbit.oak.segment.SegmentTracker;
import org.apache.jackrabbit.oak.segment.SegmentWriter;
import org.apache.jackrabbit.oak.segment.file.tar.EntryRecovery;
import org.apache.jackrabbit.oak.segment.file.tar.GCGeneration;
import org.apache.jackrabbit.oak.segment.file.tar.TarFiles;
import org.apache.jackrabbit.oak.segment.file.tar.TarRecovery;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.stats.StatsOptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The storage implementation for tar files.
 */
public abstract class AbstractFileStore implements SegmentStore, Closeable {

    private static final Logger log = LoggerFactory.getLogger(AbstractFileStore.class);

    /**
     * The minimum supported store version. It is possible for an implementation
     * to support in a transparent and backwards-compatible way older versions
     * of a repository. In this case, the minimum supported store version
     * identifies the store format that can still be processed by the
     * implementation. The minimum store version has to be greater than zero and
     * less than or equal to the maximum store version.
     */
    private static final int MIN_STORE_VERSION = 1;

    /**
     * The maximum supported store version. It is possible for an implementation
     * to support in a transparent and forwards-compatible way newer version of
     * a repository. In this case, the maximum supported store version
     * identifies the store format that can still be processed by the
     * implementation. The maximum supported store version has to be greater
     * than zero and greater than or equal to the minimum store version.
     */
    private static final int MAX_STORE_VERSION = 2;

    static ManifestChecker newManifestChecker(SegmentNodeStorePersistence persistence, boolean strictVersionCheck) throws IOException {
        return ManifestChecker.newManifestChecker(
                persistence.getManifestFile(),
                persistence.segmentFilesExist(),
                strictVersionCheck ? MAX_STORE_VERSION : MIN_STORE_VERSION,
                MAX_STORE_VERSION
        );
    }

    @NotNull
    final SegmentTracker tracker;

    @NotNull
    final CachingSegmentReader segmentReader;

    final File directory;

    private final BlobStore blobStore;

    final boolean memoryMapping;

    final boolean offHeapAccess;

    @NotNull
    final SegmentCache segmentCache;

    final TarRecovery recovery = new TarRecovery() {

        @Override
        public void recoverEntry(UUID uuid, byte[] data, EntryRecovery entryRecovery) throws IOException {
            writeSegment(uuid, data, entryRecovery);
        }

    };

    @NotNull
    private final SegmentBufferMonitor segmentBufferMonitor;

    protected final IOMonitor ioMonitor;

    protected final RemoteStoreMonitor remoteStoreMonitor;
    
    protected final int binariesInlineThreshold;

    AbstractFileStore(final FileStoreBuilder builder) {
        this.directory = builder.getDirectory();
        this.tracker = new SegmentTracker(new SegmentIdFactory() {
            @Override @NotNull
            public SegmentId newSegmentId(long msb, long lsb) {
                return new SegmentId(AbstractFileStore.this, msb, lsb, segmentCache::recordHit);
            }
        });
        this.blobStore = builder.getBlobStore();
        this.segmentCache = newSegmentCache(builder.getSegmentCacheSize());
        this.segmentReader = new CachingSegmentReader(
            this::getWriter,
            blobStore,
            builder.getStringCacheSize(),
            builder.getTemplateCacheSize(),
            builder.getStatsProvider().getMeter("oak.segment.reads", StatsOptions.DEFAULT)
        );
        this.memoryMapping = builder.getMemoryMapping();
        this.offHeapAccess = builder.getOffHeapAccess();
        this.ioMonitor = builder.getIOMonitor();
        this.remoteStoreMonitor = builder.getRemoteStoreMonitor();
        this.segmentBufferMonitor = new SegmentBufferMonitor(builder.getStatsProvider());
        this.binariesInlineThreshold = builder.getBinariesInlineThreshold();
    }

    static SegmentNotFoundException asSegmentNotFoundException(Exception e, SegmentId id) {
        if (e.getCause() instanceof SegmentNotFoundException) {
            return (SegmentNotFoundException) e.getCause();
        }
        return new SegmentNotFoundException(id, e);
    }

    @NotNull
    public CacheStatsMBean getSegmentCacheStats() {
        return segmentCache.getCacheStats();
    }

    @NotNull
    public CacheStatsMBean getStringCacheStats() {
        return segmentReader.getStringCacheStats();
    }

    @NotNull
    public CacheStatsMBean getTemplateCacheStats() {
        return segmentReader.getTemplateCacheStats();
    }

    @NotNull
    public abstract SegmentWriter getWriter();

    @NotNull
    public SegmentReader getReader() {
        return segmentReader;
    }

    @NotNull
    public SegmentIdProvider getSegmentIdProvider() {
        return tracker;
    }
    
    public int getBinariesInlineThreshold() {
        return binariesInlineThreshold;
    }

    /**
     * @return the {@link Revisions} object bound to the current store.
     */
    public abstract Revisions getRevisions();

    /**
     * Convenience method for accessing the root node for the current head.
     * This is equivalent to
     * <pre>
     * fileStore.getReader().readHeadState(fileStore.getRevisions())
     * </pre>
     * @return the current head node state
     */
    @NotNull
    public SegmentNodeState getHead() {
        return segmentReader.readHeadState(getRevisions());
    }

    /**
     * @return  the external BlobStore (if configured) with this store, {@code null} otherwise.
     */
    @Nullable
    public BlobStore getBlobStore() {
        return blobStore;
    }

    private void writeSegment(UUID id, byte[] data, EntryRecovery w) throws IOException {
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        Buffer buffer = Buffer.wrap(data);
        GCGeneration generation = SegmentId.isDataSegmentId(lsb)
                ? Segment.getGcGeneration(newSegmentData(buffer), id)
                : GCGeneration.NULL;
        w.recoverEntry(msb, lsb, data, 0, data.length, generation);
        if (SegmentId.isDataSegmentId(lsb)) {
            SegmentId segmentId = tracker.newSegmentId(msb, lsb);
            Segment segment = new Segment(tracker, segmentReader, segmentId, buffer);
            segmentCache.putSegment(segment);
            w.addSegment(segment);
            populateTarGraph(segment, w);
            populateTarBinaryReferences(segment, w);
        }
    }

    private static void populateTarGraph(Segment segment, EntryRecovery w) {
        UUID from = segment.getSegmentId().asUUID();
        for (int i = 0; i < segment.getReferencedSegmentIdCount(); i++) {
            w.recoverGraphEdge(from, segment.getReferencedSegmentId(i));
        }
    }

    private static void populateTarBinaryReferences(final Segment segment, final EntryRecovery w) {
        final GCGeneration generation = segment.getGcGeneration();
        final UUID id = segment.getSegmentId().asUUID();
        segment.forEachRecord((number, type, offset) -> {
            if (type == RecordType.BLOB_ID) {
                w.recoverBinaryReference(generation, id, SegmentBlob.readBlobId(segment, number, w.getRecoveredSegments()));
            }
        });
    }

    static Set<UUID> readReferences(Segment segment) {
        Set<UUID> references = new HashSet<>();
        for (int i = 0; i < segment.getReferencedSegmentIdCount(); i++) {
            references.add(segment.getReferencedSegmentId(i));
        }
        return references;
    }

    static Set<String> readBinaryReferences(final Segment segment) {
        final Set<String> binaryReferences = new HashSet<>();
        segment.forEachRecord(new RecordConsumer() {

            @Override
            public void consume(int number, RecordType type, int offset) {
                if (type == RecordType.BLOB_ID) {
                    binaryReferences.add(SegmentBlob.readBlobId(segment, number));
                }
            }

        });
        return binaryReferences;
    }

    static void closeAndLogOnFail(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ioe) {
                // ignore and log
                log.error(ioe.getMessage(), ioe);
            }
        }
    }

    Segment readSegmentUncached(TarFiles tarFiles, SegmentId id) {
        Buffer buffer = tarFiles.readSegment(id.getMostSignificantBits(), id.getLeastSignificantBits());
        if (buffer == null) {
            throw new SegmentNotFoundException(id);
        }
        segmentBufferMonitor.trackAllocation(buffer);
        return new Segment(tracker, segmentReader, id, buffer);
    }

    /**
     * Finds all external blob references that are currently accessible
     * in this repository and adds them to the given collector. Useful
     * for collecting garbage in an external data store.
     * <p>
     * Note that this method only collects blob references that are already
     * stored in the repository (at the time when this method is called), so
     * the garbage collector will need some other mechanism for tracking
     * in-memory references and references stored while this method is
     * running.
     * @param collector  reference collector called back for each blob reference found
     */
    public abstract void collectBlobReferences(Consumer<String> collector) throws IOException;
}
