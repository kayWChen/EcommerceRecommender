package com.kay.kafkastreaming;

import com.kay.kafkastreaming.LogProcessor.LogProcessor;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.processor.TopologyBuilder;

import java.util.Properties;


/**
 * the application will accept the topic naming 'log' and sent as a new top 'recommender' after processing
 */

public class Application {

    public static void main(String[] args){

        String brokers = "hadoop102:9092";
        String zookeepers = "hadoop102:2181";

        // input and output topic
        String from = "log";
        String to = "recommender";

        // kafka streaming configs
        Properties settings = new Properties();
        settings.put(StreamsConfig.APPLICATION_ID_CONFIG, "logFilter");
        settings.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        settings.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, zookeepers);

        StreamsConfig config = new StreamsConfig(settings);

        // topology construction
        TopologyBuilder builder = new TopologyBuilder();


        builder.addSource("SOURCE", from)
                .addProcessor("PROCESS", () -> new LogProcessor(), "SOURCE")
                .addSink("SINK", to, "PROCESS");

        KafkaStreams streams = new KafkaStreams(builder, config);
        streams.start();
    }
}
