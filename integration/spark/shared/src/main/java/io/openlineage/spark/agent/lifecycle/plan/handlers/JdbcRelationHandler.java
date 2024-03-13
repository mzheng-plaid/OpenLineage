/*
/* Copyright 2018-2024 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.lifecycle.plan.handlers;

import io.openlineage.client.OpenLineage;
import io.openlineage.client.utils.DatasetIdentifier;
import io.openlineage.client.utils.JdbcUtils;
import io.openlineage.spark.agent.util.JdbcSparkUtils;
import io.openlineage.spark.api.DatasetFactory;
import io.openlineage.sql.ColumnMeta;
import io.openlineage.sql.DbTableMeta;
import io.openlineage.sql.SqlMeta;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.execution.datasources.LogicalRelation;
import org.apache.spark.sql.execution.datasources.jdbc.JDBCRelation;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

@Slf4j
public class JdbcRelationHandler<D extends OpenLineage.Dataset> {

  private final DatasetFactory<D> datasetFactory;

  public JdbcRelationHandler(DatasetFactory<D> datasetFactory) {
    this.datasetFactory = datasetFactory;
  }

  public List<D> handleRelation(LogicalRelation x) {
    // strip the jdbc: prefix from the url. this leaves us with a url like
    // postgresql://<hostname>:<port>/<database_name>?params
    // we don't parse the URI here because different drivers use different
    // connection
    // formats that aren't always amenable to how Java parses URIs. E.g., the oracle
    // driver format looks like oracle:<drivertype>:<user>/<password>@<database>
    // whereas postgres, mysql, and sqlserver use the scheme://hostname:port/db
    // format.
    JDBCRelation relation = (JDBCRelation) x.relation();
    return getDatasets(relation, relation.jdbcOptions().url());
  }

  public List<D> getDatasets(JDBCRelation relation, String url) {
    Optional<SqlMeta> sqlMeta = JdbcSparkUtils.extractQueryFromSpark(relation);
    if (!sqlMeta.isPresent()) {
      return Collections.emptyList();
    }
    if (sqlMeta.get().columnLineage().isEmpty()) {
      DatasetIdentifier di =
          JdbcUtils.getDatasetIdentifierFromJdbcUrl(
              url, sqlMeta.get().inTables().get(0).qualifiedName());
      return Collections.singletonList(
          datasetFactory.getDataset(di.getName(), di.getNamespace(), relation.schema()));
    }
    return sqlMeta.get().inTables().stream()
        .map(
            dbtm -> {
              DatasetIdentifier di =
                  JdbcUtils.getDatasetIdentifierFromJdbcUrl(url, dbtm.qualifiedName());
              return datasetFactory.getDataset(
                  di.getName(),
                  di.getNamespace(),
                  generateJDBCSchema(dbtm, relation.schema(), sqlMeta.get()));
            })
        .collect(Collectors.toList());
  }

  private static StructType generateJDBCSchema(
      DbTableMeta origin, StructType schema, SqlMeta sqlMeta) {
    StructType originSchema = new StructType();
    for (StructField f : schema.fields()) {
      List<ColumnMeta> fields =
          sqlMeta.columnLineage().stream()
              .filter(cl -> cl.descendant().name().equals(f.name()))
              .flatMap(
                  cl ->
                      cl.lineage().stream()
                          .filter(
                              cm -> cm.origin().isPresent() && cm.origin().get().equals(origin)))
              .collect(Collectors.toList());
      for (ColumnMeta cm : fields) {
        originSchema = originSchema.add(cm.name(), f.dataType());
      }
    }
    return originSchema;
  }
}
