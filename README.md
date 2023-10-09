# Reactive Elasticsearch Client Concurrency Test

Version:
- springboot 2.7.15
- elastic data reactive 4.4.15 - using netty

Requirements:
- elasticsearch 7+ local or remote installation - single node or cluster without SSL
- java 17 or similar 

The program generates test data (person objects) and pushes them using 
bulk asynchronous operations in a growing number of concurrent threads.

The concurrency as configured grows from 1 to 30.
The size of the bulk request can be configured.
The number of bulk requests for each of the concurrency tests can be configured.
The number of connections in the pool can be configured.

Additionally, the concurrency can be limited by a semaphore guarding the bulk asynch request alone.

The timeouts can be configured.

It was observed running on 6 core 12 threads desktop class machine with local elastic as downloaded with any settings changes.
With settings as configured the usual bulk executes within 200-300 milliseconds.

But with growing concurrency some operations are stuck and complete after longer than 1 second.

Also occasional timeouts are observed over 20 seconds.
Those are reported as exceptions.
It appears that the library sets an additional timeout of 1 minute as seen in errors reported.
This timeout is not yet controlled by the program.
http://localhost:9200/_bulk?timeout=1m 
```
Error has been observed at the following site(s):
*__checkpoint ⇢ Request to POST http://localhost:9200/_bulk?timeout=1m [DefaultWebClient]
Original Stack Trace:
at org.springframework.web.reactive.function.client.ExchangeFunctions$DefaultExchangeFunction.lambda$wrapException$9(ExchangeFunctions.java:141)
at reactor.core.publisher.MonoErrorSupplied.subscribe(MonoErrorSupplied.java:55)
at reactor.core.publisher.Mono.subscribe(Mono.java:4490)
at reactor.core.publisher.FluxOnErrorResume$ResumeSubscriber.onError(FluxOnErrorResume.java:103)
```

Observed "tuning" effects of index settings:
- lower number of shards - ideally 1 - improve indexing performance - too high number of shards, above physical and os capacity (like 10) significantly 
  lower the indexing performance - like from 270 seconds total execution to 600 seconds and even leads to response timeouts
- higher number of shards (up to a point) should improve concurrent search performance - not tested
- high refresh interval - slightly improves indexing performance - like total execution takes 250 seconds vs 270 seconds,
  conclusion is that increasing this time from 1 second is good but it does not make much of a difference for it to be more than 30 or 60 seconds

Comments on connection timeouts and keep alive:
- HTTP REST normally would open new connection for every request, protocol is stateless
- the netty normally uses default connection provider with limited number of pool connections
- as configured in this program the custom connection provider is used with 30 max connections
- elastic own client libraries (not netty) using apache http client normally default to 30 max connections in the pool
- When using SSL the overhead of creating a connection is significant - in the order of 10-50 milliseconds
- the program as is does not handle SSL yet
- When using bulk requests which take hundreds of milliseconds or several seconds the role of http connection pool is lower
- The TCP keep alive is relevant to keep idle connections open even when not used
- For performance overall the TCP keep alive is hardly relevant, idle connection could and should be closed
- Attempts to keep the connections open by the client may conflict with what the server wants to achieve
