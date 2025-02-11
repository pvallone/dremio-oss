/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
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
package com.dremio.exec.planner.sql.handlers.direct;

import java.util.Collections;
import java.util.List;

import org.apache.calcite.sql.SqlNode;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.ResolvedVersionContext;
import com.dremio.exec.catalog.TableMutationOptions;
import com.dremio.exec.catalog.VersionContext;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.exec.planner.sql.handlers.query.DataAdditionCmdHandler;
import com.dremio.exec.planner.sql.parser.SqlAlterTableDropColumn;
import com.dremio.service.namespace.DatasetHelper;
import com.dremio.service.namespace.NamespaceKey;

/**
 * Removes column from the table specified by {@link SqlAlterTableDropColumn}
 */
public class DropColumnHandler extends SimpleDirectHandler {

  private static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DropColumnHandler.class);

  private final Catalog catalog;
  private final SqlHandlerConfig config;

  public DropColumnHandler(Catalog catalog, SqlHandlerConfig config) {
    this.catalog = catalog;
    this.config = config;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    SqlAlterTableDropColumn sqlDropColumn = SqlNodeUtil.unwrap(sqlNode, SqlAlterTableDropColumn.class);

    NamespaceKey path = catalog.resolveSingle(sqlDropColumn.getTable());

    DremioTable table = catalog.getTableNoResolve(path);

    SimpleCommandResult validate = SqlHandlerUtil.validateSupportForDDLOperations(catalog, config, path, table);

    if (!validate.ok) {
      return Collections.singletonList(validate);
    }

    if (table.getSchema().getFields().stream()
        .noneMatch(field -> field.getName().equalsIgnoreCase(sqlDropColumn.getColumnToDrop()))) {
      throw UserException.validationError().message("Column [%s] is not present in table [%s]",
          sqlDropColumn.getColumnToDrop(), path).buildSilently();
    }

    if (table.getSchema().getFieldCount() == 1) {
      throw UserException.validationError().message("Cannot drop all columns of a table").buildSilently();
    }
    final String sourceName = path.getRoot();
    final VersionContext sessionVersion = config.getContext().getSession().getSessionVersionForSource(sourceName);
    ResolvedVersionContext resolvedVersionContext = CatalogUtil.resolveVersionContext(catalog, sourceName, sessionVersion);
    CatalogUtil.validateResolvedVersionIsBranch(resolvedVersionContext, path.toString());
    TableMutationOptions tableMutationOptions = TableMutationOptions.newBuilder()
      .setResolvedVersionContext(resolvedVersionContext)
      .build();
    catalog.dropColumn(path, sqlDropColumn.getColumnToDrop(), tableMutationOptions);

    if (!DatasetHelper.isInternalIcebergTableOrJsonTable(table.getDatasetConfig()) && !(CatalogUtil.requestedPluginSupportsVersionedTables(path, catalog))) {
      DataAdditionCmdHandler.refreshDataset(catalog, path, false);
    }
    return Collections.singletonList(SimpleCommandResult.successful(String.format("Column [%s] dropped",
        sqlDropColumn.getColumnToDrop())));
  }
}
