package com.example.elasticreactive;


import com.fasterxml.jackson.databind.ObjectMapper;
import javax.annotation.PostConstruct;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.client.reactive.ReactiveElasticsearchClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

@Component
@Slf4j
@RequiredArgsConstructor
public class Indexer {

    @Value("${indexer.max_bulk_active}")
    private Integer indexerMaxBulkActive;

    @Value("${indexer.min_concurrency}")
    private Integer indexerMinConcurrency;

    @Value("${indexer.max_concurrency}")
    private Integer indexerMaxConcurrency;

    @Value("${indexer.batch_size}")
    private Integer indexerBatchSize;

    @Value("${indexer.batch_count}")
    private Integer indexerBatchCount;

    @Value("${indexer.index_name}")
    private String indexerIndexName;

    @Value("${indexer.index_shards}")
    private Integer indexerIndexShards;

    @Value("${indexer.index_replicas}")
    private Integer indexerIndexReplicas;

    @Value("${indexer.index_refresh_interval}")
    private String indexerIndexRefreshInterval;

    @Autowired
    private ReactiveElasticsearchClient reactiveElasticsearchClient;

    @Autowired
    private PersonGenerator personGenerator;

    @Autowired
    private RestHighLevelClient restHighLevelClient;

    private final ObjectMapper objectMapper;

    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final Timer indexTimer = registry.timer("es.timer");
    private final LongAdder concurrent = registry.gauge("es.concurrent", new LongAdder());
    private final Counter successes = registry.counter("es.index", "result", "success");
    private final Counter failures = registry.counter("es.index", "result", "failure");


    // use semaphore as blocker against too high concurrency
    // normally controlling the concurrency would be enough
    // but semaphore is a brute force defence to make sure that no matter how many worker threads try to send data
    // no more than max bulk operations are in progress
    // this way there is less chance they do timeout
    // another limit on asynch concurrency is number of connections in WebClient pool
    // but blocking on this limit may cause timeouts so better control in the application code
    private Semaphore available;

    private Flux<BulkResponse> indexMany(int batchSize, int batchCount, int concurrency) {
        log.info("indexMany concurrency={}", concurrency);
        return personGenerator
                .infinite()
                .take(batchSize)
                .collectList()
                .repeat()
                .take(batchCount)
                // .flatMap(docs -> indexManyDocSwallowErrors(docs), concurrency);
                .flatMap(docs -> countConcurrent(measure(indexManyDocSwallowErrors(docs))), concurrency);
    }

    private Mono<BulkResponse> indexManyDocSwallowErrors(List<Doc> docs) {
        final long startTime = System.currentTimeMillis();
        return indexManyDocs(docs)
                .doOnSuccess(response -> {
                    available.release();
                    successes.increment();
                    long duration = System.currentTimeMillis() - startTime;
                    log.debug("success reactive bulk after {}", duration);
                    if (duration > 1000) {
                        log.warn("success reactive bulk after long time {}", duration);
                    }
                })
                .doOnError(e -> {
                    available.release();
                    log.error("Unable to index after {}", System.currentTimeMillis() - startTime, e);
                })
                .doOnError(e -> failures.increment())
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<BulkResponse> indexManyDocs(List<Doc> docs) {

        final BulkRequest bulkRequest = new BulkRequest();
        docs.stream().forEach(doc -> {
            IndexRequest indexRequest = new IndexRequest(indexerIndexName, "person", doc.getUsername());
            indexRequest.source(doc.getJson(), XContentType.JSON);
            bulkRequest.add(indexRequest);
        });

        try {
            available.acquire();
            log.debug("calling reactive bulk");
            return reactiveElasticsearchClient.bulk(bulkRequest);
        } catch (InterruptedException exc) {
            log.error("interrupted ", exc);
        }
        return Mono.empty();
    }

    private <T> Mono<T> countConcurrent(Mono<T> input) {
        return input
                .doOnSubscribe(s -> concurrent.increment())
                .doOnTerminate(concurrent::decrement);
    }

    private <T> Mono<T> measure(Mono<T> input) {
        return Mono
                .fromCallable(System::currentTimeMillis)
                .flatMap(time ->
                        input.doOnSuccess(x -> {
                            long duration = System.currentTimeMillis() - time;
                            log.debug("took {}", duration);
                            indexTimer.record(duration, TimeUnit.MILLISECONDS);
                        })
                );
    }

    private void createIndex() {

        try {
            CreateIndexRequest request = new CreateIndexRequest(indexerIndexName);

            request.settings(Settings.builder()
                    .put("index.number_of_shards", indexerIndexShards)
                    .put("index.number_of_replicas", indexerIndexReplicas)
                    .put("index.refresh_interval", indexerIndexRefreshInterval).build()
            );

            CreateIndexResponse createIndexResponse = restHighLevelClient.indices().create(request, RequestOptions.DEFAULT);
            log.info("index created {}", createIndexResponse.isAcknowledged());
        } catch(Exception exc) {
            log.error("index create failed", exc);
        }
    }

    private void deleteIndex() {
        try {
            DeleteIndexRequest request = new DeleteIndexRequest(indexerIndexName);
            AcknowledgedResponse deleteIndexResponse = restHighLevelClient.indices().delete(request, RequestOptions.DEFAULT);
            log.info("index deleted {}", deleteIndexResponse.isAcknowledged());
        } catch(Exception exc) {
            log.error("index delete failed", exc);
        }
    }

    @PostConstruct
    void startIndexing() {
        available = new Semaphore(indexerMaxBulkActive, true);

        deleteIndex();
        createIndex();

        long startTime = System.currentTimeMillis();
        Flux
                .range(indexerMinConcurrency, indexerMaxConcurrency)
                .concatMap(concurrency -> indexMany(indexerBatchSize, indexerBatchCount, concurrency))
                .window(Duration.ofSeconds(1))
                .flatMap(Flux::count)
                .subscribe(winSize -> log.info(
                        "Got responses/sec={} elapsed from start sec {}",
                        winSize,
                        (System.currentTimeMillis() - startTime)/1000.0));
    }
}