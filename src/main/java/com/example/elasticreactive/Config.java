package com.example.elasticreactive;

import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jdk.net.ExtendedSocketOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.reactive.ReactiveElasticsearchClient;
import org.springframework.data.elasticsearch.client.reactive.ReactiveRestClients;
import org.springframework.data.elasticsearch.config.AbstractReactiveElasticsearchConfiguration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.time.Duration;


@Configuration
@Slf4j
public class Config extends AbstractReactiveElasticsearchConfiguration {

    @Value("${es.protocol:http}")
    private String esProtocol;

    @Value("${es.host}")
    private String esHost;

    @Value("${es.port}")
    private Integer esPort;

    @Value("${es.trust_store_file:#{null}}")
    private String esTrustStoreFile;

    @Value("${es.trust_store_password_file:#{null}}")
    private String esTrustStorePasswordFile;

    @Value("${es.user_name:#{null}}")
    private String esUserName;

    @Value("${es.user_password_file:#{null}}")
    private String esUserPasswordFile;

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

        ClientConfiguration.TerminalClientConfigurationBuilder configurationBuilder;
        if ("http".equals(esProtocol)) {
            configurationBuilder = ClientConfiguration.builder()
                    .connectedTo(esHost + ":" + esPort);
        } else {
            configurationBuilder = ClientConfiguration.builder()
                    .connectedTo(esHost + ":" + esPort)
                    .usingSsl();
        }

        final ClientConfiguration clientConfiguration = configurationBuilder
                .withConnectTimeout(Duration.ofMillis(esConnectTimeoutMillis))
                .withSocketTimeout(Duration.ofMillis(esResponseTimeoutMillis))
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

                            if(esTrustStoreFile != null && esTrustStorePasswordFile != null) {
                                httpClient = httpClient.secure(sc -> {
                                    try {
                                        sc.sslContext(createSSLContext());
                                    } catch (Exception exc) {
                                        log.error("failed to build SSLContext", exc);
                                    }
                                });
                            }

                            if(esUserName != null && esUserPasswordFile != null) {
                                try {
                                    webClient = webClient
                                            .mutate()
                                            .filter(ExchangeFilterFunctions
                                                    .basicAuthentication(esUserName, loadPassword(esUserPasswordFile)))
                                            .build();
                                } catch(IOException exc) {
                                    log.error("failed to set up basic auth with user password", exc);
                                }
                            }
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


    private SslContext createSSLContext()
            throws IOException, KeyStoreException, NoSuchAlgorithmException,
            CertificateException {

        KeyStore truststore = KeyStore.getInstance("pkcs12");
        String trustStorePassword = loadPassword(esTrustStorePasswordFile);
        try (InputStream is = Files.newInputStream(Paths.get(esTrustStoreFile))) {
            truststore.load(is, trustStorePassword.toCharArray());
        }

        return SslContextBuilder
                .forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
    }

    private String loadPassword(String fileName) throws IOException {

        return Files.readAllLines(Paths.get(fileName)).get(0);
    }
}
