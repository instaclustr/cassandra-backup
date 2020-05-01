package com.instaclustr.cassandra.backup.impl.restore;

import javax.validation.constraints.NotBlank;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.MoreObjects;
import com.instaclustr.cassandra.backup.impl.StorageLocation;
import com.instaclustr.cassandra.backup.impl.restore.RestorationEntities.RestorationEntitiesConverter;
import com.instaclustr.jackson.PathDeserializer;
import com.instaclustr.jackson.PathSerializer;
import com.instaclustr.picocli.typeconverter.PathTypeConverter;
import picocli.CommandLine.Option;

public class RestoreOperationRequest extends BaseRestoreOperationRequest {

    @Option(names = {"--dd", "--data-directory"},
        description = "Base directory that contains the Cassandra data, cache and commitlog directories",
        converter = PathTypeConverter.class,
        defaultValue = "/var/lib/cassandra/")
    @JsonDeserialize(using = PathDeserializer.class)
    @JsonSerialize(using = PathSerializer.class)
    public Path cassandraDirectory;

    @Option(names = {"-p", "--shared-path"},
        description = "Shared Container path for pod",
        converter = PathTypeConverter.class,
        defaultValue = "/")
    @JsonDeserialize(using = PathDeserializer.class)
    @JsonSerialize(using = PathSerializer.class)
    public Path sharedContainerPath;

    @Option(names = {"--cd", "--config-directory"},
        description = "Directory where configuration of Cassandra is stored.",
        converter = PathTypeConverter.class,
        defaultValue = "/etc/cassandra/")
    @JsonDeserialize(using = PathDeserializer.class)
    @JsonSerialize(using = PathSerializer.class)
    public Path cassandraConfigDirectory;

    @Option(names = {"--rs", "--restore-system-keyspace"},
        description = "Restore system keyspace. Use this to prevent bootstrapping, when restoring on only a single node.")
    public boolean restoreSystemKeyspace;

    @Option(names = {"-s", "--st", "--snapshot-tag"},
        description = "Snapshot to download and restore.",
        required = true)
    @NotBlank
    public String snapshotTag;

    @Option(names = {"--entities"},
        description = "Comma separated list of keyspaces or keyspaces and tables to restore either in for 'ks1,ks2' or 'ks1.cf1,ks2.cf2'",
        converter = RestorationEntitiesConverter.class)
    public RestorationEntities entities = RestorationEntities.empty();

    @Option(names = {"--update-cassandra-yaml"},
        description = "If set to true, cassandra.yaml file will be updated to restore it properly (sets initial_tokens)")
    public boolean updateCassandraYaml;

    public RestoreOperationRequest() {
        // for picocli
    }

    @JsonCreator
    public RestoreOperationRequest(@JsonProperty("storageLocation") final StorageLocation storageLocation,
                                   @JsonProperty("concurrentConnections") final Integer concurrentConnections,
                                   @JsonProperty("waitForLock") final boolean waitForLock,
                                   @JsonProperty("lockFile") final Path lockFile,
                                   @JsonProperty("cassandraDirectory") final Path cassandraDirectory,
                                   @JsonProperty("cassandraConfigDirectory") final Path cassandraConfigDirectory,
                                   @JsonProperty("sharedContainerPath") final Path sharedContainerPath,
                                   @JsonProperty("restoreSystemKeyspace") final boolean restoreSystemKeyspace,
                                   @JsonProperty("snapshotTag") final String snapshotTag,
                                   @JsonProperty("entities") final RestorationEntities entities,
                                   @JsonProperty("updateCassandraYaml") final boolean updateCassandraYaml,
                                   @JsonProperty("k8sNamespace") final String k8sNamespace,
                                   @JsonProperty("k8sSecretName") final String k8sSecretName) {
        super(storageLocation, concurrentConnections, waitForLock, lockFile, k8sNamespace, k8sSecretName);
        this.cassandraDirectory = cassandraDirectory == null ? Paths.get("/var/lib/cassandra") : cassandraDirectory;
        this.cassandraConfigDirectory = cassandraConfigDirectory == null ? Paths.get("/etc/cassandra") : cassandraConfigDirectory;
        this.sharedContainerPath = sharedContainerPath == null ? Paths.get("/") : sharedContainerPath;
        this.restoreSystemKeyspace = restoreSystemKeyspace;
        this.snapshotTag = snapshotTag;
        this.entities = entities == null ? RestorationEntities.empty() : entities;
        this.updateCassandraYaml = updateCassandraYaml;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("storageLocation", storageLocation)
            .add("waitForLock", waitForLock)
            .add("concurrentConnections", concurrentConnections)
            .add("cassandraDirectory", cassandraDirectory)
            .add("sharedContainerPath", sharedContainerPath)
            .add("restoreSystemKeyspace", restoreSystemKeyspace)
            .add("snapshotTag", snapshotTag)
            .add("entities", entities)
            .add("updateCassandraYaml", updateCassandraYaml)
            .add("k8sNamespace", k8sNamespace)
            .add("k8sSecretName", k8sBackupSecretName)
            .toString();
    }
}
