package com.example.elasticreactive;

import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import jdk.net.ExtendedSocketOptions;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.reactive.ReactiveElasticsearchClient;
import org.springframework.data.elasticsearch.client.reactive.ReactiveRestClients;
import org.springframework.data.elasticsearch.config.AbstractReactiveElasticsearchConfiguration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;


@Configuration
public class Config extends AbstractReactiveElasticsearchConfiguration {

    @Value("${es.protocol}")
    private String esProtocol;

    @Value("${es.host}")
    private String esHost;

    @Value("${es.port}")
    private Integer esPort;

    @Value("${es.connect_timeout_millis}")
    private Integer esConnectTimeoutMillis;

    @Value("${es.response_timeout_millis}")
    private Integer esResponseTimeoutMillis;

    @Value("${es.max_netty_connections}")
    private Integer esMaxNettyConnections;

    @Value("${es.max_bulk_size}")
    private Integer esMaxBulkSize;

    @Override
    @Bean
    public ReactiveElasticsearchClient reactiveElasticsearchClient() {
        final ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .connectedTo(esHost + ":" + esPort)
                .withClientConfigurer(
                        ReactiveRestClients.WebClientConfigurationCallback.from(webClient -> {
                            String connectionProviderName = "myConnectionProvider";
                            HttpClient httpClient = HttpClient
                                    .create(ConnectionProvider.create(connectionProviderName, esMaxNettyConnections))
                                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, esConnectTimeoutMillis)
                                    // enabling keep alive - but it should not matter much
                                    // https://www.baeldung.com/spring-webflux-timeout
                                    .option(ChannelOption.SO_KEEPALIVE, true)
                                    // this might work on linux
//                                    .option(EpollChannelOption.TCP_KEEPIDLE, 60)
//                                    .option(EpollChannelOption.TCP_KEEPINTVL, 60)
//                                    .option(EpollChannelOption.TCP_KEEPCNT, 5)
                                    // this is good for mac os x
                                    .option(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE), 60)
                                    .option(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL), 60)
                                    .option(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT), 5)
                                    .responseTimeout(Duration.ofMillis(esResponseTimeoutMillis));

                            return webClient
                                    .mutate()
                                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                                    .exchangeStrategies(ExchangeStrategies
                                            .builder()
                                            .codecs(codecs -> codecs
                                                    .defaultCodecs()
                                                    .maxInMemorySize(esMaxBulkSize))
                                            .build())
                                    .build();
                        }))
                .build();
        return ReactiveRestClients.create(clientConfiguration);
    }

    @Bean
    RestHighLevelClient restHighLevelClient() {
        return new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost(esHost, esPort, esProtocol)));
    }
}
