/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.common.predicate.Domain;
import com.facebook.presto.hive.cache.HiveCachingHdfsConfiguration;
import com.facebook.presto.hive.filesystem.ExtendedFileSystem;
import com.facebook.presto.hive.metastore.Partition;
import com.facebook.presto.hive.metastore.Storage;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.hive.util.HiveFileIterator;
import com.facebook.presto.hive.util.InternalHiveSplitFactory;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.ListMultimap;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.SymlinkTextInputFormat;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import static com.facebook.presto.hive.HiveBucketing.getVirtualBucketNumber;
import static com.facebook.presto.hive.HiveColumnHandle.pathColumnHandle;
import static com.facebook.presto.hive.HiveCommonSessionProperties.getNodeSelectionStrategy;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_BAD_DATA;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_BUCKET_FILES;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_FILE_NAMES;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_UNSUPPORTED_FORMAT;
import static com.facebook.presto.hive.HiveMetadata.shouldCreateFilesForMissingBuckets;
import static com.facebook.presto.hive.HiveSessionProperties.getMaxInitialSplitSize;
import static com.facebook.presto.hive.HiveSessionProperties.getMaxSplitSize;
import static com.facebook.presto.hive.HiveSessionProperties.isFileSplittable;
import static com.facebook.presto.hive.HiveSessionProperties.isOrderBasedExecutionEnabled;
import static com.facebook.presto.hive.HiveSessionProperties.isSkipEmptyFilesEnabled;
import static com.facebook.presto.hive.HiveSessionProperties.isSymlinkOptimizedReaderEnabled;
import static com.facebook.presto.hive.HiveSessionProperties.isUseListDirectoryCache;
import static com.facebook.presto.hive.HiveUtil.buildDirectoryContextProperties;
import static com.facebook.presto.hive.HiveUtil.getFooterCount;
import static com.facebook.presto.hive.HiveUtil.getHeaderCount;
import static com.facebook.presto.hive.HiveUtil.getInputFormat;
import static com.facebook.presto.hive.HiveUtil.getTargetPathsHiveFileInfos;
import static com.facebook.presto.hive.HiveUtil.isHudiParquetInputFormat;
import static com.facebook.presto.hive.HiveUtil.readSymlinkPaths;
import static com.facebook.presto.hive.HiveUtil.shouldUseFileSplitsFromInputFormat;
import static com.facebook.presto.hive.HiveWriterFactory.getBucketNumber;
import static com.facebook.presto.hive.NestedDirectoryPolicy.FAIL;
import static com.facebook.presto.hive.NestedDirectoryPolicy.IGNORED;
import static com.facebook.presto.hive.NestedDirectoryPolicy.RECURSE;
import static com.facebook.presto.hive.metastore.MetastoreUtil.getHiveSchema;
import static com.facebook.presto.hive.metastore.MetastoreUtil.getPartitionLocation;
import static com.facebook.presto.hive.s3select.S3SelectPushdown.shouldEnablePushdownForTable;
import static com.facebook.presto.hive.util.ConfigurationUtils.toJobConf;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Maps.fromProperties;
import static com.google.common.collect.Streams.stream;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class StoragePartitionLoader
        extends PartitionLoader
{
    private static final ListenableFuture<?> COMPLETED_FUTURE = immediateFuture(null);

    private final Table table;
    private final Map<Integer, Domain> infoColumnConstraints;
    private final Optional<BucketSplitInfo> tableBucketInfo;
    private final HdfsEnvironment hdfsEnvironment;
    private final HdfsContext hdfsContext;
    private final NamenodeStats namenodeStats;
    private final DirectoryLister directoryLister;
    private final boolean recursiveDirWalkerEnabled;
    private final ConnectorSession session;
    private final Deque<Iterator<InternalHiveSplit>> fileIterators;
    private final boolean schedulerUsesHostAddresses;
    private final boolean partialAggregationsPushedDown;
    private static final String SPLIT_MINSIZE = "mapreduce.input.fileinputformat.split.minsize";

    public StoragePartitionLoader(
            Table table,
            Map<Integer, Domain> infoColumnConstraints,
            Optional<BucketSplitInfo> tableBucketInfo,
            ConnectorSession session,
            HdfsEnvironment hdfsEnvironment,
            NamenodeStats namenodeStats,
            DirectoryLister directoryLister,
            Deque<Iterator<InternalHiveSplit>> fileIterators,
            boolean recursiveDirWalkerEnabled,
            boolean schedulerUsesHostAddresses,
            boolean partialAggregationsPushedDown)
    {
        this.table = requireNonNull(table, "table is null");
        this.infoColumnConstraints = requireNonNull(infoColumnConstraints, "infoColumnConstraints is null");
        this.tableBucketInfo = requireNonNull(tableBucketInfo, "tableBucketInfo is null");
        this.session = requireNonNull(session, "session is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.namenodeStats = requireNonNull(namenodeStats, "namenodeStats is null");
        this.recursiveDirWalkerEnabled = recursiveDirWalkerEnabled;
        this.hdfsContext = new HdfsContext(session, table.getDatabaseName(), table.getTableName(), table.getStorage().getLocation(), false);
        this.fileIterators = requireNonNull(fileIterators, "fileIterators is null");
        this.schedulerUsesHostAddresses = schedulerUsesHostAddresses;
        this.partialAggregationsPushedDown = partialAggregationsPushedDown;

        Optional<DirectoryLister> directoryListerOverride = Optional.empty();
        if (!isNullOrEmpty(table.getStorage().getLocation())) {
            Configuration configuration = hdfsEnvironment.getConfiguration(hdfsContext, new Path(table.getStorage().getLocation()));
            try {
                InputFormat<?, ?> inputFormat = getInputFormat(
                        configuration,
                        table.getStorage().getStorageFormat().getInputFormat(),
                        table.getStorage().getStorageFormat().getSerDe(),
                        false);
                if (isHudiParquetInputFormat(inputFormat)) {
                    directoryListerOverride = Optional.of(new HudiDirectoryLister(configuration, session, table));
                }
            }
            catch (PrestoException ex) {
                // Tables and partitions can have different format. When Table format is not supported,
                // Ignore Hudi check for those tables. Partitions can still be of a supported format.
                if (!HIVE_UNSUPPORTED_FORMAT.toErrorCode().equals(ex.getErrorCode())) {
                    throw ex;
                }
            }
        }
        this.directoryLister = directoryListerOverride.orElseGet(() -> requireNonNull(directoryLister, "directoryLister is null"));
    }

    private ListenableFuture<?> handleSymlinkTextInputFormat(
            ExtendedFileSystem fs,
            Path path,
            InputFormat<?, ?> inputFormat,
            boolean s3SelectPushdownEnabled,
            Storage storage,
            List<HivePartitionKey> partitionKeys,
            String partitionName,
            int partitionDataColumnCount,
            boolean stopped,
            HivePartitionMetadata partition,
            HiveSplitSource hiveSplitSource,
            Configuration configuration,
            boolean splittable)
            throws IOException
    {
        if (tableBucketInfo.isPresent()) {
            throw new PrestoException(NOT_SUPPORTED, "Bucketed table in SymlinkTextInputFormat is not yet supported");
        }

        List<Path> targetPaths = getTargetPathsFromSymlink(fs, path, partition.getPartition());

        if (isSymlinkOptimizedReaderEnabled(session)) {
            Map<Path, List<Path>> parentToTargets = targetPaths.stream().collect(Collectors.groupingBy(Path::getParent));

            InputFormat<?, ?> targetInputFormat = getInputFormat(
                    configuration,
                    storage.getStorageFormat().getInputFormat(),
                    storage.getStorageFormat().getSerDe(),
                    true);

            HiveDirectoryContext hiveDirectoryContext = new HiveDirectoryContext(
                    IGNORED,
                    isUseListDirectoryCache(session),
                    isSkipEmptyFilesEnabled(session),
                    hdfsContext.getIdentity(),
                    buildDirectoryContextProperties(session),
                    session.getRuntimeStats());

            for (Map.Entry<Path, List<Path>> entry : parentToTargets.entrySet()) {
                Iterator<InternalHiveSplit> symlinkIterator = getSymlinkIterator(
                        path,
                        s3SelectPushdownEnabled,
                        storage,
                        partitionKeys,
                        partitionName,
                        partitionDataColumnCount,
                        partition,
                        splittable,
                        entry.getKey(),
                        entry.getValue(),
                        targetInputFormat,
                        hiveDirectoryContext);

                fileIterators.addLast(symlinkIterator);
            }
            return COMPLETED_FUTURE;
        }

        return getSymlinkSplits(
                path,
                inputFormat,
                s3SelectPushdownEnabled,
                storage,
                partitionKeys,
                partitionName,
                partitionDataColumnCount,
                stopped,
                partition,
                hiveSplitSource,
                targetPaths);
    }

    @VisibleForTesting
    Iterator<InternalHiveSplit> getSymlinkIterator(
            Path path,
            boolean s3SelectPushdownEnabled,
            Storage storage,
            List<HivePartitionKey> partitionKeys,
            String partitionName,
            int partitionDataColumnCount,
            HivePartitionMetadata partition,
            boolean splittable,
            Path targetParent,
            List<Path> currentTargetPaths,
            InputFormat<?, ?> targetInputFormat,
            HiveDirectoryContext hiveDirectoryContext)
            throws IOException
    {
        ExtendedFileSystem targetFilesystem = hdfsEnvironment.getFileSystem(hdfsContext, targetParent);

        List<HiveFileInfo> targetPathsHiveFileInfos = getTargetPathsHiveFileInfos(
                path,
                partition.getPartition(),
                targetParent,
                currentTargetPaths,
                hiveDirectoryContext,
                targetFilesystem,
                directoryLister,
                table,
                namenodeStats,
                session);

        InternalHiveSplitFactory splitFactory = getHiveSplitFactory(
                targetFilesystem,
                targetInputFormat,
                s3SelectPushdownEnabled,
                storage,
                targetParent.toUri().toString(),
                partitionName,
                partitionKeys,
                partitionDataColumnCount,
                partition,
                Optional.empty());

        return targetPathsHiveFileInfos.stream()
                .map(hiveFileInfo -> splitFactory.createInternalHiveSplit(hiveFileInfo, splittable))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .iterator();
    }

    private ListenableFuture<?> getSymlinkSplits(
            Path path,
            InputFormat<?, ?> inputFormat,
            boolean s3SelectPushdownEnabled,
            Storage storage,
            List<HivePartitionKey> partitionKeys,
            String partitionName,
            int partitionDataColumnCount,
            boolean stopped,
            HivePartitionMetadata partition,
            HiveSplitSource hiveSplitSource,
            List<Path> targetPaths)
            throws IOException
    {
        ListenableFuture<?> lastResult = COMPLETED_FUTURE;
        for (Path targetPath : targetPaths) {
            // The input should be in TextInputFormat.
            TextInputFormat targetInputFormat = new TextInputFormat();
            // the splits must be generated using the file system for the target path
            // get the configuration for the target path -- it may be a different hdfs instance
            ExtendedFileSystem targetFilesystem = hdfsEnvironment.getFileSystem(hdfsContext, targetPath);

            Configuration targetConfiguration = targetFilesystem.getConf();
            if (targetConfiguration instanceof HiveCachingHdfsConfiguration.CachingJobConf) {
                targetConfiguration = ((HiveCachingHdfsConfiguration.CachingJobConf) targetConfiguration).getConfig();
            }
            if (targetConfiguration instanceof CopyOnFirstWriteConfiguration) {
                targetConfiguration = ((CopyOnFirstWriteConfiguration) targetConfiguration).getConfig();
            }

            JobConf targetJob = toJobConf(targetConfiguration);

            targetJob.setInputFormat(TextInputFormat.class);
            targetInputFormat.configure(targetJob);
            targetJob.set(SPLIT_MINSIZE, Long.toString(getMaxSplitSize(session).toBytes()));
            FileInputFormat.setInputPaths(targetJob, targetPath);
            InputSplit[] targetSplits = targetInputFormat.getSplits(targetJob, 0);

            InternalHiveSplitFactory splitFactory = getHiveSplitFactory(
                    targetFilesystem,
                    inputFormat,
                    s3SelectPushdownEnabled,
                    storage,
                    path.toUri().toString(),
                    partitionName,
                    partitionKeys,
                    partitionDataColumnCount,
                    partition,
                    Optional.empty());
            lastResult = addSplitsToSource(targetSplits, splitFactory, hiveSplitSource, stopped);
            if (stopped) {
                return COMPLETED_FUTURE;
            }
        }
        return lastResult;
    }

    private ListenableFuture<?> handleGetSplitsFromInputFormat(Configuration configuration,
            Path path,
            Properties schema,
            InputFormat<?, ?> inputFormat,
            boolean stopped,
            HiveSplitSource hiveSplitSource,
            InternalHiveSplitFactory splitFactory)
            throws IOException
    {
        if (tableBucketInfo.isPresent()) {
            throw new PrestoException(NOT_SUPPORTED, "Presto cannot read bucketed partition in an input format with UseFileSplitsFromInputFormat annotation: " + inputFormat.getClass().getSimpleName());
        }
        JobConf jobConf = toJobConf(configuration);
        FileInputFormat.setInputPaths(jobConf, path);
        // SerDes parameters and Table parameters passing into input format
        fromProperties(schema).forEach(jobConf::set);
        jobConf.set(SPLIT_MINSIZE, Long.toString(getMaxSplitSize(session).toBytes()));
        InputSplit[] splits = inputFormat.getSplits(jobConf, 0);

        return addSplitsToSource(splits, splitFactory, hiveSplitSource, stopped);
    }

    private InternalHiveSplitFactory getHiveSplitFactory(ExtendedFileSystem fs,
            InputFormat<?, ?> inputFormat,
            boolean s3SelectPushdownEnabled,
            Storage storage,
            String path,
            String partitionName,
            List<HivePartitionKey> partitionKeys,
            int partitionDataColumnCount,
            HivePartitionMetadata partition,
            Optional<HiveSplit.BucketConversion> bucketConversion)
    {
        return new InternalHiveSplitFactory(
                fs,
                inputFormat,
                infoColumnConstraints,
                getNodeSelectionStrategy(session),
                getMaxInitialSplitSize(session),
                s3SelectPushdownEnabled,
                new HiveSplitPartitionInfo(
                        storage,
                        path,
                        partitionKeys,
                        partitionName,
                        partitionDataColumnCount,
                        partition.getTableToPartitionMapping(),
                        bucketConversion,
                        partition.getRedundantColumnDomains(),
                        partition.getRowIdPartitionComponent()),
                schedulerUsesHostAddresses,
                partition.getEncryptionInformation());
    }

    @Override
    public ListenableFuture<?> loadPartition(HivePartitionMetadata partition, HiveSplitSource hiveSplitSource, boolean stopped)
            throws IOException
    {
        String partitionName = partition.getHivePartition().getPartitionId().getPartitionName();
        Storage storage = partition.getPartition().map(Partition::getStorage).orElse(table.getStorage());
        Properties schema = getPartitionSchema(table, partition.getPartition());
        String inputFormatName = storage.getStorageFormat().getInputFormat();
        String serDe = storage.getStorageFormat().getSerDe();
        int partitionDataColumnCount = partition.getPartition()
                .map(p -> p.getColumns().size())
                .orElseGet(table.getDataColumns()::size);
        List<HivePartitionKey> partitionKeys = getPartitionKeys(table, partition.getPartition(), partitionName);
        String location = getPartitionLocation(table, partition.getPartition());
        if (location.isEmpty()) {
            checkState(!shouldCreateFilesForMissingBuckets(table, session), "Empty location is only allowed for empty temporary table when zero-row file is not created");
            return COMPLETED_FUTURE;
        }
        Path path = new Path(location);
        Configuration configuration = hdfsEnvironment.getConfiguration(hdfsContext, path);
        // This is required for HUDI MOR realtime tables only.
        // Similar changes are implemented in HudiDirectoryLister for HUDI COW and MOR read-optimised tables.
        if (directoryLister instanceof HudiDirectoryLister) {
            if (configuration instanceof HiveCachingHdfsConfiguration.CachingJobConf) {
                configuration = ((HiveCachingHdfsConfiguration.CachingJobConf) configuration).getConfig();
            }
            if (configuration instanceof CopyOnFirstWriteConfiguration) {
                configuration = ((CopyOnFirstWriteConfiguration) configuration).getConfig();
            }
        }
        InputFormat<?, ?> inputFormat = getInputFormat(configuration, inputFormatName, serDe, false);
        ExtendedFileSystem fs = hdfsEnvironment.getFileSystem(hdfsContext.getIdentity().getUser(), path, configuration);
        boolean s3SelectPushdownEnabled = shouldEnablePushdownForTable(session, table, path.toString(), partition.getPartition());

        // Streaming aggregation works at the granularity of individual files
        // Partial aggregation pushdown works at the granularity of individual files
        // therefore we must not split files when either is enabled.
        // Skip header / footer lines are not splittable except for a special case when skip.header.line.count=1
        boolean splittable = isFileSplittable(session) &&
                !isOrderBasedExecutionEnabled(session) &&
                !partialAggregationsPushedDown &&
                getFooterCount(schema) == 0 && getHeaderCount(schema) <= 1;

        if (inputFormat instanceof SymlinkTextInputFormat) {
            return handleSymlinkTextInputFormat(fs, path, inputFormat, s3SelectPushdownEnabled, storage, partitionKeys, partitionName,
                    partitionDataColumnCount, stopped, partition, hiveSplitSource, configuration, splittable);
        }

        Optional<HiveSplit.BucketConversion> bucketConversion = Optional.empty();
        boolean bucketConversionRequiresWorkerParticipation = false;
        if (partition.getPartition().isPresent()) {
            Optional<HiveBucketProperty> partitionBucketProperty = partition.getPartition().get().getStorage().getBucketProperty();
            if (tableBucketInfo.isPresent() && partitionBucketProperty.isPresent()) {
                int tableBucketCount = tableBucketInfo.get().getTableBucketCount();
                int partitionBucketCount = partitionBucketProperty.get().getBucketCount();
                // Validation was done in HiveSplitManager#getPartitionMetadata.
                // Here, it's just trying to see if its needs the BucketConversion.
                if (tableBucketCount != partitionBucketCount) {
                    bucketConversion = Optional.of(new HiveSplit.BucketConversion(tableBucketCount, partitionBucketCount, tableBucketInfo.get().getBucketColumns()));
                    if (tableBucketCount > partitionBucketCount) {
                        bucketConversionRequiresWorkerParticipation = true;
                    }
                }
            }
        }
        InternalHiveSplitFactory splitFactory = getHiveSplitFactory(
                fs,
                inputFormat,
                s3SelectPushdownEnabled,
                storage,
                location,
                partitionName,
                partitionKeys,
                partitionDataColumnCount,
                partition,
                bucketConversionRequiresWorkerParticipation ? bucketConversion : Optional.empty());

        if (shouldUseFileSplitsFromInputFormat(inputFormat, directoryLister)) {
            return handleGetSplitsFromInputFormat(configuration, path, schema, inputFormat, stopped, hiveSplitSource, splitFactory);
        }

        // Bucketed partitions are fully loaded immediately since all files must be loaded to determine the file to bucket mapping
        if (tableBucketInfo.isPresent()) {
            if (tableBucketInfo.get().isVirtuallyBucketed()) {
                // For virtual bucket, bucket conversion must not be present because there is no physical partition bucket count
                checkState(!bucketConversion.isPresent(), "Virtually bucketed table must not have partitions that are physically bucketed");
                checkState(
                        tableBucketInfo.get().getTableBucketCount() == tableBucketInfo.get().getReadBucketCount(),
                        "Table and read bucket count should be the same for virtual bucket");
                return hiveSplitSource.addToQueue(getVirtuallyBucketedSplits(path, fs, splitFactory, tableBucketInfo.get().getReadBucketCount(), partition.getPartition(), splittable));
            }
            return hiveSplitSource.addToQueue(getBucketedSplits(path, fs, splitFactory, tableBucketInfo.get(), bucketConversion, partitionName, partition.getPartition(), splittable));
        }

        fileIterators.addLast(createInternalHiveSplitIterator(path, fs, splitFactory, splittable, partition.getPartition()));
        return COMPLETED_FUTURE;
    }

    private ListenableFuture<?> addSplitsToSource(InputSplit[] targetSplits, InternalHiveSplitFactory splitFactory, HiveSplitSource hiveSplitSource, boolean stopped)
            throws IOException
    {
        ListenableFuture<?> lastResult = COMPLETED_FUTURE;
        for (InputSplit inputSplit : targetSplits) {
            Optional<InternalHiveSplit> internalHiveSplit = splitFactory.createInternalHiveSplit((FileSplit) inputSplit);
            if (internalHiveSplit.isPresent()) {
                lastResult = hiveSplitSource.addToQueue(internalHiveSplit.get());
            }
            if (stopped) {
                return COMPLETED_FUTURE;
            }
        }
        return lastResult;
    }

    private Iterator<InternalHiveSplit> createInternalHiveSplitIterator(
            Path path,
            ExtendedFileSystem fileSystem,
            InternalHiveSplitFactory splitFactory,
            boolean splittable,
            Optional<Partition> partition)
    {
        boolean cacheable = isUseListDirectoryCache(session);
        if (partition.isPresent()) {
            // Use cache only for sealed partitions
            cacheable &= partition.get().isSealedPartition();
        }

        HiveDirectoryContext hiveDirectoryContext = new HiveDirectoryContext(
                recursiveDirWalkerEnabled ? RECURSE : IGNORED,
                cacheable,
                isSkipEmptyFilesEnabled(session),
                hdfsContext.getIdentity(),
                buildDirectoryContextProperties(session),
                session.getRuntimeStats());
        return stream(directoryLister.list(fileSystem, table, path, partition, namenodeStats, hiveDirectoryContext))
                .map(hiveFileInfo -> splitFactory.createInternalHiveSplit(hiveFileInfo, splittable))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .iterator();
    }

    private List<InternalHiveSplit> getBucketedSplits(
            Path path,
            ExtendedFileSystem fileSystem,
            InternalHiveSplitFactory splitFactory,
            BucketSplitInfo bucketSplitInfo,
            Optional<HiveSplit.BucketConversion> bucketConversion,
            String partitionName,
            Optional<Partition> partition,
            boolean splittable)
    {
        int readBucketCount = bucketSplitInfo.getReadBucketCount();
        int tableBucketCount = bucketSplitInfo.getTableBucketCount();
        int partitionBucketCount = bucketConversion.map(HiveSplit.BucketConversion::getPartitionBucketCount).orElse(tableBucketCount);

        checkState(readBucketCount <= tableBucketCount, "readBucketCount(%s) should be less than or equal to tableBucketCount(%s)", readBucketCount, tableBucketCount);

        // list all files in the partition
        List<HiveFileInfo> fileInfos = new ArrayList<>(partitionBucketCount);
        try {
            Iterators.addAll(fileInfos, directoryLister.list(fileSystem, table, path, partition, namenodeStats, new HiveDirectoryContext(
                    FAIL,
                    isUseListDirectoryCache(session),
                    isSkipEmptyFilesEnabled(session),
                    hdfsContext.getIdentity(),
                    buildDirectoryContextProperties(session),
                    session.getRuntimeStats())));
        }
        catch (HiveFileIterator.NestedDirectoryNotAllowedException e) {
            // Fail here to be on the safe side. This seems to be the same as what Hive does
            throw new PrestoException(
                    HIVE_INVALID_BUCKET_FILES,
                    format("Hive table '%s' is corrupt. Found sub-directory in bucket directory for partition: %s",
                            table.getSchemaTableName(),
                            partitionName));
        }

        ListMultimap<Integer, HiveFileInfo> bucketToFileInfo = computeBucketToFileInfoMapping(fileInfos, partitionBucketCount, partitionName);

        // convert files internal splits
        return convertFilesToInternalSplits(bucketSplitInfo, bucketConversion, bucketToFileInfo, splitFactory, splittable);
    }

    private ListMultimap<Integer, HiveFileInfo> computeBucketToFileInfoMapping(List<HiveFileInfo> fileInfos,
            int partitionBucketCount,
            String partitionName)
    {
        ListMultimap<Integer, HiveFileInfo> bucketToFileInfo = ArrayListMultimap.create();

        if (!shouldCreateFilesForMissingBuckets(table, session)) {
            fileInfos.stream()
                    .forEach(fileInfo -> {
                        String fileName = fileInfo.getFileName();
                        OptionalInt bucket = getBucketNumber(fileName);
                        if (bucket.isPresent()) {
                            bucketToFileInfo.put(bucket.getAsInt(), fileInfo);
                        }
                        else {
                            throw new PrestoException(HIVE_INVALID_BUCKET_FILES, format("invalid hive bucket file name: %s", fileName));
                        }
                    });
        }
        else {
            // build mapping of file name to bucket
            for (HiveFileInfo file : fileInfos) {
                String fileName = file.getFileName();
                OptionalInt bucket = getBucketNumber(fileName);
                if (bucket.isPresent()) {
                    bucketToFileInfo.put(bucket.getAsInt(), file);
                    continue;
                }

                // legacy mode requires exactly one file per bucket
                if (fileInfos.size() != partitionBucketCount) {
                    throw new PrestoException(
                            HIVE_INVALID_BUCKET_FILES,
                            format("Hive table '%s' is corrupt. File '%s' does not match the standard naming pattern, and the number " +
                                            "of files in the directory (%s) does not match the declared bucket count (%s) for partition: %s",
                                    table.getSchemaTableName(),
                                    fileName,
                                    fileInfos.size(),
                                    partitionBucketCount,
                                    partitionName));
                }
                if (fileInfos.get(0).getFileName().matches("\\d+")) {
                    try {
                        // File names are integer if they are created when file_renaming_enabled is set to true
                        fileInfos.sort(Comparator.comparingInt(fileInfo -> Integer.parseInt(fileInfo.getFileName())));
                    }
                    catch (NumberFormatException e) {
                        throw new PrestoException(
                                HIVE_INVALID_FILE_NAMES,
                                format("Hive table '%s' is corrupt. Some of the filenames in the partition: %s are not integers",
                                        new SchemaTableName(table.getDatabaseName(), table.getTableName()),
                                        partitionName));
                    }
                }
                else {
                    // Sort FileStatus objects (instead of, e.g., fileStatus.getPath().toString). This matches org.apache.hadoop.hive.ql.metadata.Table.getSortedPaths
                    fileInfos.sort(null);
                }

                // Use position in sorted list as the bucket number
                bucketToFileInfo.clear();
                for (int i = 0; i < fileInfos.size(); i++) {
                    bucketToFileInfo.put(i, fileInfos.get(i));
                }
                break;
            }
        }

        return bucketToFileInfo;
    }

    private List<InternalHiveSplit> convertFilesToInternalSplits(BucketSplitInfo bucketSplitInfo,
            Optional<HiveSplit.BucketConversion> bucketConversion,
            ListMultimap<Integer, HiveFileInfo> bucketToFileInfo,
            InternalHiveSplitFactory splitFactory,
            boolean splittable)
    {
        int readBucketCount = bucketSplitInfo.getReadBucketCount();
        int tableBucketCount = bucketSplitInfo.getTableBucketCount();
        int partitionBucketCount = bucketConversion.map(HiveSplit.BucketConversion::getPartitionBucketCount).orElse(tableBucketCount);
        int bucketCount = max(readBucketCount, partitionBucketCount);
        List<InternalHiveSplit> splitList = new ArrayList<>();
        for (int bucketNumber = 0; bucketNumber < bucketCount; bucketNumber++) {
            // Physical bucket #. This determine file name. It also determines the order of splits in the result.
            int partitionBucketNumber = bucketNumber % partitionBucketCount;
            if (!bucketToFileInfo.containsKey(partitionBucketNumber)) {
                continue;
            }
            // Logical bucket #. Each logical bucket corresponds to a "bucket" from engine's perspective.
            int readBucketNumber = bucketNumber % readBucketCount;

            boolean containsIneligibleTableBucket = false;
            List<Integer> eligibleTableBucketNumbers = new ArrayList<>();
            for (int tableBucketNumber = bucketNumber % tableBucketCount; tableBucketNumber < tableBucketCount; tableBucketNumber += bucketCount) {
                // table bucket number: this is used for evaluating "$bucket" filters.
                if (bucketSplitInfo.isTableBucketEnabled(tableBucketNumber)) {
                    eligibleTableBucketNumbers.add(tableBucketNumber);
                }
                else {
                    containsIneligibleTableBucket = true;
                }
            }

            if (!eligibleTableBucketNumbers.isEmpty() && containsIneligibleTableBucket) {
                throw new PrestoException(
                        NOT_SUPPORTED,
                        "The bucket filter cannot be satisfied. There are restrictions on the bucket filter when all the following is true: " +
                                "1. a table has a different buckets count as at least one of its partitions that is read in this query; " +
                                "2. the table has a different but compatible bucket number with another table in the query; " +
                                "3. some buckets of the table is filtered out from the query, most likely using a filter on \"$bucket\". " +
                                "(table name: " + table.getTableName() + ", table bucket count: " + tableBucketCount + ", " +
                                "partition bucket count: " + partitionBucketCount + ", effective reading bucket count: " + readBucketCount + ")");
            }
            if (!eligibleTableBucketNumbers.isEmpty()) {
                for (HiveFileInfo fileInfo : bucketToFileInfo.get(partitionBucketNumber)) {
                    eligibleTableBucketNumbers.stream()
                            .map(tableBucketNumber -> splitFactory.createInternalHiveSplit(fileInfo, readBucketNumber, tableBucketNumber, splittable))
                            .forEach(optionalSplit -> optionalSplit.ifPresent(splitList::add));
                }
            }
        }
        return splitList;
    }

    private List<InternalHiveSplit> getVirtuallyBucketedSplits(Path path, ExtendedFileSystem fileSystem, InternalHiveSplitFactory splitFactory, int bucketCount, Optional<Partition> partition, boolean splittable)
    {
        // List all files recursively in the partition and assign virtual bucket number to each of them
        HiveDirectoryContext hiveDirectoryContext = new HiveDirectoryContext(
                recursiveDirWalkerEnabled ? RECURSE : IGNORED,
                isUseListDirectoryCache(session),
                isSkipEmptyFilesEnabled(session),
                hdfsContext.getIdentity(),
                buildDirectoryContextProperties(session),
                session.getRuntimeStats());
        return stream(directoryLister.list(fileSystem, table, path, partition, namenodeStats, hiveDirectoryContext))
                .map(fileInfo -> {
                    int virtualBucketNumber = getVirtualBucketNumber(bucketCount, fileInfo.getPath());
                    return splitFactory.createInternalHiveSplit(fileInfo, virtualBucketNumber, virtualBucketNumber, splittable);
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toImmutableList());
    }

    private List<Path> getTargetPathsFromSymlink(ExtendedFileSystem fileSystem, Path symlinkDir, Optional<Partition> partition)
    {
        try {
            HiveDirectoryContext hiveDirectoryContext = new HiveDirectoryContext(
                    IGNORED,
                    isUseListDirectoryCache(session),
                    isSkipEmptyFilesEnabled(session),
                    hdfsContext.getIdentity(),
                    buildDirectoryContextProperties(session),
                    session.getRuntimeStats());
            Iterator<HiveFileInfo> manifestFileInfos = directoryLister.list(fileSystem, table, symlinkDir, partition, namenodeStats, hiveDirectoryContext);
            return readSymlinkPaths(fileSystem, manifestFileInfos);
        }
        catch (IOException e) {
            throw new PrestoException(HIVE_BAD_DATA, "Error parsing symlinks from: " + symlinkDir, e);
        }
    }

    private static Properties getPartitionSchema(Table table, Optional<Partition> partition)
    {
        return partition.map(value -> getHiveSchema(value, table)).orElseGet(() -> getHiveSchema(table));
    }

    public static class BucketSplitInfo
    {
        private final List<HiveColumnHandle> bucketColumns;
        private final int tableBucketCount;
        private final int readBucketCount;
        private final IntPredicate bucketFilter;

        public static Optional<BucketSplitInfo> createBucketSplitInfo(Optional<HiveBucketHandle> bucketHandle, Optional<HiveBucketing.HiveBucketFilter> bucketFilter)
        {
            requireNonNull(bucketHandle, "bucketHandle is null");
            requireNonNull(bucketFilter, "buckets is null");

            if (!bucketHandle.isPresent()) {
                checkArgument(!bucketFilter.isPresent(), "bucketHandle must be present if bucketFilter is present");
                return Optional.empty();
            }

            int tableBucketCount = bucketHandle.get().getTableBucketCount();
            int readBucketCount = bucketHandle.get().getReadBucketCount();

            List<HiveColumnHandle> bucketColumns = bucketHandle.get().getColumns();
            IntPredicate predicate = bucketFilter
                    .<IntPredicate>map(filter -> filter.getBucketsToKeep()::contains)
                    .orElse(bucket -> true);
            return Optional.of(new BucketSplitInfo(bucketColumns, tableBucketCount, readBucketCount, predicate));
        }

        private BucketSplitInfo(List<HiveColumnHandle> bucketColumns, int tableBucketCount, int readBucketCount, IntPredicate bucketFilter)
        {
            this.bucketColumns = ImmutableList.copyOf(requireNonNull(bucketColumns, "bucketColumns is null"));
            this.tableBucketCount = tableBucketCount;
            this.readBucketCount = readBucketCount;
            this.bucketFilter = requireNonNull(bucketFilter, "bucketFilter is null");
        }

        public List<HiveColumnHandle> getBucketColumns()
        {
            return bucketColumns;
        }

        public int getTableBucketCount()
        {
            return tableBucketCount;
        }

        public int getReadBucketCount()
        {
            return readBucketCount;
        }

        public boolean isVirtuallyBucketed()
        {
            return bucketColumns.size() == 1 && bucketColumns.get(0).equals(pathColumnHandle());
        }

        /**
         * Evaluates whether the provided table bucket number passes the bucket predicate.
         * A bucket predicate can be present in two cases:
         * <ul>
         * <li>Filter on "$bucket" column. e.g. {@code "$bucket" between 0 and 100}
         * <li>Single-value equality filter on all bucket columns. e.g. for a table with two bucketing columns,
         * {@code bucketCol1 = 'a' AND bucketCol2 = 123}
         * </ul>
         */
        public boolean isTableBucketEnabled(int tableBucketNumber)
        {
            return bucketFilter.test(tableBucketNumber);
        }
    }
}
