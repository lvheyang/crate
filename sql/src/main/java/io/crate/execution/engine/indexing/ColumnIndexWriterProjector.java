/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.engine.indexing;

import io.crate.data.BatchIterator;
import io.crate.data.CollectingBatchIterator;
import io.crate.data.Input;
import io.crate.data.Projector;
import io.crate.data.Row;
import io.crate.execution.TransportActionProvider;
import io.crate.execution.dml.ShardRequest;
import io.crate.execution.dml.upsert.ShardInsertRequest;
import io.crate.execution.dml.upsert.ShardUpsertRequest;
import io.crate.execution.dml.upsert.ShardWriteRequest.Mode;
import io.crate.execution.engine.collect.CollectExpression;
import io.crate.execution.engine.collect.RowShardResolver;
import io.crate.execution.jobs.NodeJobsCounter;
import io.crate.expression.InputRow;
import io.crate.expression.symbol.Assignments;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.Functions;
import io.crate.metadata.Reference;
import io.crate.metadata.TransactionContext;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.ShardId;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

public class ColumnIndexWriterProjector implements Projector {

    private final ShardingUpsertExecutor<? extends ShardRequest<?, ?>, ? extends ShardRequest.Item> shardingUpsertExecutor;

    public ColumnIndexWriterProjector(ClusterService clusterService,
                                      NodeJobsCounter nodeJobsCounter,
                                      ScheduledExecutorService scheduler,
                                      Executor executor,
                                      TransactionContext txnCtx,
                                      Functions functions,
                                      Settings settings,
                                      int targetTableNumShards,
                                      int targetTableNumReplicas,
                                      Supplier<String> indexNameResolver,
                                      TransportActionProvider transportActionProvider,
                                      List<ColumnIdent> primaryKeyIdents,
                                      List<? extends Symbol> primaryKeySymbols,
                                      @Nullable Symbol routingSymbol,
                                      ColumnIdent clusteredByColumn,
                                      List<Reference> columnReferences,
                                      List<Input<?>> insertInputs,
                                      List<? extends CollectExpression<Row, ?>> collectExpressions,
                                      boolean ignoreDuplicateKeys,
                                      @Nullable Map<Reference, Symbol> updateAssignments,
                                      int bulkActions,
                                      boolean autoCreateIndices,
                                      List<Symbol> returnValues,
                                      UUID jobId
                                      ) {
        RowShardResolver rowShardResolver = new RowShardResolver(
            txnCtx, functions, primaryKeyIdents, primaryKeySymbols, clusteredByColumn, routingSymbol);
        assert columnReferences.size() == insertInputs.size()
            : "number of insert inputs must be equal to the number of columns";

        String[] updateColumnNames;
        Symbol[] assignments;
        if (updateAssignments == null) {
            updateColumnNames = null;
            assignments = null;
        } else {
            Assignments convert = Assignments.convert(updateAssignments);
            updateColumnNames = convert.targetNames();
            assignments = convert.sources();
        }

        Symbol[] returnValueOrNull = returnValues.isEmpty() ? null : returnValues.toArray(new Symbol[0]);
        // Optimization for the plain insert usecase, no return values no update-on-conflict
        if (returnValueOrNull == null && updateAssignments == null &&
            !clusterService.state().getNodes().getMinNodeVersion().onOrAfter(Version.V_4_2_0)
            ) {
            Function<ShardId, ShardInsertRequest> requestFactory = new ShardInsertRequest.Builder(
                txnCtx.sessionSettings(),
                ShardingUpsertExecutor.BULK_REQUEST_TIMEOUT_SETTING.setting().get(settings),
                true, // continueOnErrors
                columnReferences.toArray(new Reference[columnReferences.size()]),
                jobId,
                true,
                ignoreDuplicateKeys ? Mode.DUPLICATE_KEY_IGNORE : Mode.DUPLICATE_KEY_UPDATE_OR_FAIL
            )::newRequest;

            InputRow insertValues = new InputRow(insertInputs);
            Function<String, ShardInsertRequest.Item> itemFactory = id -> new ShardInsertRequest.Item(id,
                                                                                                      insertValues.materialize(),
                                                                                                      null,
                                                                                                      null,
                                                                                                      null);

            shardingUpsertExecutor = new ShardingUpsertExecutor<>(
                clusterService,
                nodeJobsCounter,
                scheduler,
                executor,
                bulkActions,
                jobId,
                rowShardResolver,
                itemFactory,
                requestFactory,
                collectExpressions,
                indexNameResolver,
                autoCreateIndices,
                transportActionProvider.transportShardInsertAction()::execute,
                transportActionProvider.transportBulkCreateIndicesAction(),
                targetTableNumShards,
                targetTableNumReplicas,
                UpsertResultContext.forRowCount());

        } else {
            Function<ShardId, ShardUpsertRequest> requestFactory = new ShardUpsertRequest.Builder(
                txnCtx.sessionSettings(),
                ShardingUpsertExecutor.BULK_REQUEST_TIMEOUT_SETTING.setting().get(settings),
                true, // continueOnErrors
                updateColumnNames,
                columnReferences.toArray(new Reference[columnReferences.size()]),
                returnValueOrNull,
                jobId,
                true,
                ignoreDuplicateKeys ? Mode.DUPLICATE_KEY_IGNORE : Mode.DUPLICATE_KEY_UPDATE_OR_FAIL
            )::newRequest;

            InputRow insertValues = new InputRow(insertInputs);
            Function<String, ShardUpsertRequest.Item> itemFactory = id -> new ShardUpsertRequest.Item(id,
                                                                                                      assignments,
                                                                                                      insertValues.materialize(),
                                                                                                      null,
                                                                                                      null,
                                                                                                      null);
            var upsertResultContext = returnValues.isEmpty() ? UpsertResultContext.forRowCount() : UpsertResultContext.forResultRows();

            shardingUpsertExecutor = new ShardingUpsertExecutor<>(
                clusterService,
                nodeJobsCounter,
                scheduler,
                executor,
                bulkActions,
                jobId,
                rowShardResolver,
                itemFactory,
                requestFactory,
                collectExpressions,
                indexNameResolver,
                autoCreateIndices,
                transportActionProvider.transportShardUpsertAction()::execute,
                transportActionProvider.transportBulkCreateIndicesAction(),
                targetTableNumShards,
                targetTableNumReplicas,
                upsertResultContext
            );
        }
    }

    @Override
    public BatchIterator<Row> apply(BatchIterator<Row> batchIterator) {
        return CollectingBatchIterator.newInstance(batchIterator, shardingUpsertExecutor, batchIterator.hasLazyResultSet());
    }

    @Override
    public boolean providesIndependentScroll() {
        return false;
    }
}
