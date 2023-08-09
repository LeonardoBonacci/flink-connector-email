package com.tngtech.flink.connector.email.foo;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.table.data.RowData;

public class FooSinkFunction extends RichSinkFunction<RowData> {

	static String[] KEYS = new String[] {"a", "b", "c"};

  public static final ConfigOption<String> HOST = ConfigOptions.key("host").stringType().noDefaultValue();
  public static final ConfigOption<Integer> PORT = ConfigOptions.key("port").intType().noDefaultValue();

  private final String hostname;
  private final int port;

  public FooSinkFunction(String hostname, int port) {
    this.hostname = hostname;
    this.port = port;
  }

  @Override
  public void invoke(RowData data) throws Exception {
  	System.out.println(data);
  }

  @Override
  public void open(Configuration parameters) throws Exception {
  }

  @Override
  public void close() throws Exception {
  }
}  
