package com.tngtech.flink.connector.email.foo;

import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceFunctionProvider;
import org.apache.flink.table.data.RowData;

public class FooDynamicTableSource implements ScanTableSource {

  private final String hostname;
  private final int port;

  public FooDynamicTableSource(
      String hostname,
      int port) {
    this.hostname = hostname;
    this.port = port;
  }

  @Override
  public ChangelogMode getChangelogMode() {
    return ChangelogMode.insertOnly();

  }

  @Override
  public ScanRuntimeProvider getScanRuntimeProvider(ScanContext runtimeProviderContext) {
    final SourceFunction<RowData> sourceFunction = new FooSourceFunction(
        hostname,
        port);

      return SourceFunctionProvider.of(sourceFunction, false);
  }

  @Override
  public DynamicTableSource copy() {
    return new FooDynamicTableSource(hostname, port);
  }

  @Override
  public String asSummaryString() {
    return "Foo Table Source";
  }
}