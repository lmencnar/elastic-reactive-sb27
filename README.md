# Reactive Elasticsearch Client Concurrency Test

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

It was observed running on 6 core 12 desktop class machine with local elastic as downloaded with any settings changes.
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