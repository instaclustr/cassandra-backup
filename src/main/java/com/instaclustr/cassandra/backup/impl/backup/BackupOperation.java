package com.instaclustr.cassandra.backup.impl.backup;

import static java.lang.String.format;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.instaclustr.cassandra.backup.guice.BackuperFactory;
import com.instaclustr.cassandra.backup.guice.BucketServiceFactory;
import com.instaclustr.cassandra.backup.impl.BucketService;
import com.instaclustr.cassandra.backup.impl.ManifestEntry;
import com.instaclustr.cassandra.backup.impl.ManifestEntry.Type;
import com.instaclustr.cassandra.backup.impl.OperationProgressTracker;
import com.instaclustr.cassandra.backup.impl.SSTableUtils;
import com.instaclustr.io.GlobalLock;
import com.instaclustr.operations.FunctionWithEx;
import com.instaclustr.operations.Operation;
import com.instaclustr.operations.OperationRequest;
import jmx.org.apache.cassandra.service.CassandraJMXService;
import jmx.org.apache.cassandra.service.cassandra3.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupOperation extends Operation<BackupOperationRequest> {

    private static final Logger logger = LoggerFactory.getLogger(BackupOperation.class);

    private final Provider<CassandraJMXService> cassandraJMXService;
    private final Map<String, BackuperFactory> backuperFactoryMap;
    final Map<String, BucketServiceFactory> bucketServiceFactoryMap;

    @AssistedInject
    public BackupOperation(final Provider<CassandraJMXService> cassandraJMXService,
                           final Map<String, BackuperFactory> backuperFactoryMap,
                           final Map<String, BucketServiceFactory> bucketServiceFactoryMap,
                           @Assisted final BackupOperationRequest request) {
        super(request);
        this.cassandraJMXService = cassandraJMXService;
        this.backuperFactoryMap = backuperFactoryMap;
        this.bucketServiceFactoryMap = bucketServiceFactoryMap;
    }

    @Override
    protected void run0() throws Exception {
        logger.info(request.toString());

        new GlobalLock(request.lockFile).waitForLock(request.waitForLock);

        if (request.offlineBackup) {
            executeUpload(ImmutableList.of());

            return;
        }

        try {
            new TakeSnapshotOperation(cassandraJMXService.get(),
                                      new TakeSnapshotOperation.TakeSnapshotOperationRequest(request.entities,
                                                                                             request.snapshotTag)).run0();

            final List<String> tokens = cassandraJMXService.get().doWithStorageServiceMBean(new FunctionWithEx<StorageServiceMBean, List<String>>() {
                @Override
                public List<String> apply(StorageServiceMBean ssMBean) {
                    return ssMBean.getTokens();
                }
            });

            executeUpload(tokens);
        } finally {
            new ClearSnapshotOperation(cassandraJMXService.get(),
                                       new ClearSnapshotOperation.ClearSnapshotOperationRequest(request.snapshotTag)).run0();
        }
    }

    private void executeUpload(List<String> tokens) throws Exception {
        final Collection<ManifestEntry> manifest = generateManifest(request.snapshotTag, request.cassandraDirectory.resolve("data"));

        Iterables.addAll(manifest, saveTokenList(tokens));

        final Path snapshotManifestDirectory = Files.createDirectories(request.sharedContainerPath.resolve(Paths.get("tmp/cassandra-operator/manifests")));

        Path manifestFile = prepareManifestFile(snapshotManifestDirectory, request.snapshotTag);

        Iterables.addAll(manifest, saveManifest(manifest, manifestFile));

        try (final BucketService bucketService = bucketServiceFactoryMap.get(request.storageLocation.storageProvider).createBucketService(request)) {
            bucketService.createIfMissing(request.storageLocation.bucket);
        }

        try (final Backuper backuper = backuperFactoryMap.get(request.storageLocation.storageProvider).createBackuper(request)) {
            backuper.uploadOrFreshenFiles(manifest, new OperationProgressTracker(this, manifest.size()));
        }

        try {
            if (!manifestFile.toFile().delete()) {
                logger.error(format("Local backup manifest file %s was not deleted.", manifestFile.toFile().getAbsolutePath()));
            }
        } catch (Exception ex) {
            logger.error(format("Unable to delete local manifest file %s", manifestFile.toFile().getAbsolutePath()), ex);
        }
    }

    private Collection<ManifestEntry> generateManifest(final String snapshotTag, final Path cassandraDataDirectory) throws IOException {
        // find files belonging to snapshot
        final Map<String, ? extends Iterable<KeyspaceColumnFamilySnapshot>> snapshots = findKeyspaceColumnFamilySnapshots(cassandraDataDirectory);

        final Iterable<KeyspaceColumnFamilySnapshot> kcfss = snapshots.get(snapshotTag);

        // generate manifest (set of object keys and source files defining the snapshot)
        final Collection<ManifestEntry> manifest = new LinkedList<>(); // linked list to maintain order

        // add snapshot files to the manifest
        for (final KeyspaceColumnFamilySnapshot keyspaceColumnFamilySnapshot : kcfss) {
            final Path tablePath = Paths.get("data").resolve(Paths.get(keyspaceColumnFamilySnapshot.keyspace, keyspaceColumnFamilySnapshot.table));
            Iterables.addAll(manifest, SSTableUtils.ssTableManifest(keyspaceColumnFamilySnapshot.snapshotDirectory, tablePath).collect(toList()));

            Path schemaPath = keyspaceColumnFamilySnapshot.snapshotDirectory.resolve("schema.cql");

            if (Files.exists(schemaPath)) {
                manifest.add(new ManifestEntry(tablePath.resolve(snapshotTag + "-schema.cql"), schemaPath, Type.FILE));
            }
        }

        logger.info("{} files in manifest for snapshot \"{}\".", manifest.size(), snapshotTag);

        if (manifest.stream().noneMatch(input -> input != null && input.localFile.toString().contains("-Data.db"))) {
            throw new IllegalStateException("No Data.db SSTables found in manifest. Aborting backup.");
        }

        return manifest;
    }

    private Path prepareManifestFile(Path snapshotManifestDirectory, String tag) throws IOException {
        final Path manifestFilePath = snapshotManifestDirectory.resolve(tag);

        Files.deleteIfExists(manifestFilePath);
        Files.createFile(manifestFilePath);

        return manifestFilePath;
    }

    private Iterable<ManifestEntry> saveManifest(final Iterable<ManifestEntry> manifest, Path manifestFilePath) throws IOException {

        try (final OutputStream stream = Files.newOutputStream(manifestFilePath);
            final PrintStream writer = new PrintStream(stream)) {
            for (final ManifestEntry manifestEntry : manifest) {
                writer.println(Joiner.on(' ').join(manifestEntry.size, manifestEntry.objectKey));
            }
        } catch (Exception ex) {
            logger.error(format("Unable to write manifest entries into manifest file %s", manifestFilePath.toAbsolutePath().toString()), ex);
            if (manifestFilePath.toFile().delete()) {
                logger.error(format("It was not possible to delete manifest file %s after there was error to write manifest entries into it.", manifestFilePath.toString()));
            }
        }

        return ImmutableList.of(new ManifestEntry(Paths.get("manifests").resolve(manifestFilePath.getFileName()),
                                                  manifestFilePath,
                                                  ManifestEntry.Type.MANIFEST_FILE));
    }

    private Iterable<ManifestEntry> saveTokenList(List<String> tokens) throws IOException {
        final Path tokensDirectory = Files.createDirectories(request.sharedContainerPath.resolve(Paths.get("tmp/cassandra-operator/tokens")));
        final Path tokensFilePath = tokensDirectory.resolve(format("%s-tokens.yaml", request.snapshotTag));

        Files.deleteIfExists(tokensFilePath);
        Files.createFile(tokensFilePath);

        try (final OutputStream stream = Files.newOutputStream(tokensFilePath);
            final PrintStream writer = new PrintStream(stream)) {
            writer.println("# automatically generated by cassandra-backup");
            writer.println("# add the following to cassandra.yaml when restoring to a new cluster.");
            writer.printf("initial_token: %s%n", Joiner.on(',').join(tokens));
        }

        return ImmutableList.of(new ManifestEntry(Paths.get("tokens").resolve(tokensFilePath.getFileName()),
                                                  tokensFilePath,
                                                  ManifestEntry.Type.FILE));
    }

    private static Map<String, ? extends Iterable<KeyspaceColumnFamilySnapshot>> findKeyspaceColumnFamilySnapshots(final Path cassandraDataDirectory) throws IOException {
        // /var/lib/cassandra /data /<keyspace> /<column family> /snapshots /<snapshot>
        return Files.find(cassandraDataDirectory,
                          4,
                          (path, basicFileAttributes) -> path.getParent().endsWith("snapshots"))
            .map((KeyspaceColumnFamilySnapshot::new))
            .collect(groupingBy(k -> k.snapshotDirectory.getFileName().toString()));
    }

    static class KeyspaceColumnFamilySnapshot {

        final String keyspace, table;
        final Path snapshotDirectory;

        KeyspaceColumnFamilySnapshot(final Path snapshotDirectory) {
            // /data /<keyspace> /<column family> /snapshots /<snapshot>

            final Path columnFamilyDirectory = snapshotDirectory.getParent().getParent();

            this.table = columnFamilyDirectory.getFileName().toString();
            this.keyspace = columnFamilyDirectory.getParent().getFileName().toString();
            this.snapshotDirectory = snapshotDirectory;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .add("keyspace", keyspace)
                .add("table", table)
                .add("snapshotDirectory", snapshotDirectory)
                .toString();
        }
    }

    public static class ClearSnapshotOperation extends Operation<ClearSnapshotOperation.ClearSnapshotOperationRequest> {

        private static final Logger logger = LoggerFactory.getLogger(ClearSnapshotOperation.class);

        private final CassandraJMXService cassandraJMXService;
        private boolean hasRun = false;

        ClearSnapshotOperation(final CassandraJMXService cassandraJMXService,
                               final ClearSnapshotOperationRequest request) {
            super(request);
            this.cassandraJMXService = cassandraJMXService;
        }

        @Override
        protected void run0() {
            if (hasRun) {
                return;
            }

            hasRun = true;

            try {
                cassandraJMXService.doWithStorageServiceMBean(new FunctionWithEx<StorageServiceMBean, Void>() {
                    @Override
                    public Void apply(StorageServiceMBean ssMBean) throws Exception {
                        ssMBean.clearSnapshot(request.snapshotTag);
                        return null;
                    }
                });

                logger.info("Cleared snapshot {}.", request.snapshotTag);
            } catch (final Exception ex) {
                logger.error("Failed to cleanup snapshot {}.", request.snapshotTag, ex);
            }
        }

        static class ClearSnapshotOperationRequest extends OperationRequest {

            final String snapshotTag;

            ClearSnapshotOperationRequest(final String snapshotTag) {
                this.snapshotTag = snapshotTag;
            }
        }
    }

    public static class TakeSnapshotOperation extends Operation<TakeSnapshotOperation.TakeSnapshotOperationRequest> {

        private static final Logger logger = LoggerFactory.getLogger(TakeSnapshotOperation.class);

        private final TakeSnapshotOperationRequest request;
        private final CassandraJMXService cassandraJMXService;

        public TakeSnapshotOperation(final CassandraJMXService cassandraJMXService, final TakeSnapshotOperationRequest request) {
            super(request);
            this.request = request;
            this.cassandraJMXService = cassandraJMXService;
        }

        public List<String> parseEntities() {

            if (request.entities == null) {
                return ImmutableList.of();
            }

            final String removedSpaces = request.entities.replace("\\s+", "");

            if (removedSpaces.isEmpty()) {
                return ImmutableList.of();
            }

            // if it contains a dot, check that it is in the form of "ks1.t1,ks1.t2,ks1.t3"
            // otherwise it has to be in format "ks1,ks2"

            if (removedSpaces.contains(".")) {

                final String[] keyspaceTablePairs = removedSpaces.split(",");

                for (final String keyspaceTablepair : keyspaceTablePairs) {
                    if (keyspaceTablepair.isEmpty() || !keyspaceTablepair.contains(".")) {
                        throw new IllegalStateException(String.format("Not in format 'ks.cf': %s", keyspaceTablepair));
                    }
                }

                return Stream.of(keyspaceTablePairs).collect(toList());
            }

            return Stream.of(removedSpaces.split(",")).collect(toList());
        }

        @Override
        protected void run0() throws Exception {
            final List<String> entities = parseEntities();

            if (entities.isEmpty()) {
                logger.info("Taking snapshot {} on all keyspaces.", request.tag);
            } else {
                logger.info("Taking snapshot {} on {}", request.tag, entities);
            }

            cassandraJMXService.doWithStorageServiceMBean(new FunctionWithEx<StorageServiceMBean, Void>() {
                @Override
                public Void apply(StorageServiceMBean ssMBean) throws Exception {

                    if (entities.isEmpty()) {
                        ssMBean.takeSnapshot(request.tag, new HashMap<>());
                    } else {
                        ssMBean.takeSnapshot(request.tag, new HashMap<>(), entities.toArray(new String[0]));
                    }

                    return null;
                }
            });
        }

        public static class TakeSnapshotOperationRequest extends OperationRequest {

            final String entities;
            final String tag;

            public TakeSnapshotOperationRequest(final String entities, final String tag) {
                this.entities = entities;
                this.tag = tag;
            }
        }
    }
}
