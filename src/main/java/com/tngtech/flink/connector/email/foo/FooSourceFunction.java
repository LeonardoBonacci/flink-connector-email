package com.tngtech.flink.connector.email.foo;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;

public class FooSourceFunction extends RichSourceFunction<RowData> {

	static String[] KEYS = new String[] {"a", "b", "c"};

  public static final ConfigOption<String> HOST = ConfigOptions.key("host").stringType().noDefaultValue();
  public static final ConfigOption<Integer> PORT = ConfigOptions.key("port").intType().noDefaultValue();

  private final String hostname;
  private final int port;

  private volatile boolean isRunning = true;

  public FooSourceFunction(String hostname, int port) {
    this.hostname = hostname;
    this.port = port;
  }

  public void open(Configuration parameters) throws Exception {
  }

  @Override
  public void run(SourceContext<RowData> ctx) throws Exception {
    while (isRunning) {

			for (String k : KEYS) {

				ctx.collect(GenericRowData.of(
	          k, 
	          k
						)
				);
			}

			System.out.println("---------------------------------------");
			Thread.sleep(1000);
    }
  }

  @Override
  public void cancel() {
    isRunning = false;
    try {

    } catch (Throwable t) {
      // ignore
    }
  }
}  
