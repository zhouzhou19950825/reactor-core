/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher.scenarios;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.subscriber.AssertSubscriber;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * @author Stephane Maldini
 */
@Tag("scenarios")
public class MonoTests {

	@Test
	public void testDoOnEachSignal() {
		List<Signal<Integer>> signals = new ArrayList<>(4);
		Mono<Integer> mono = Mono.just(1)
		                         .doOnEach(signals::add);
		StepVerifier.create(mono)
		            .expectSubscription()
		            .expectNext(1)
		            .expectComplete()
		            .verify();

		assertThat(signals.size()).isEqualTo(2);
		assertThat(signals.get(0).get()).as("onNext").isEqualTo(1);
		assertThat(signals.get(1).isOnComplete()).as("onComplete expected").isTrue();
	}

	@Test
	public void testDoOnEachEmpty() {
		List<Signal<Integer>> signals = new ArrayList<>(4);
		Mono<Integer> mono = Mono.<Integer>empty()
		                         .doOnEach(signals::add);
		StepVerifier.create(mono)
		            .expectSubscription()
		            .expectComplete()
		            .verify();

		assertThat(signals.size()).isEqualTo(1);
		assertThat(signals.get(0).isOnComplete()).as("onComplete expected").isTrue();

	}

	@Test
	public void testDoOnEachSignalWithError() {
		List<Signal<Integer>> signals = new ArrayList<>(4);
		Mono<Integer> mono = Mono.<Integer>error(new IllegalArgumentException("foo"))
				.doOnEach(signals::add);
		StepVerifier.create(mono)
		            .expectSubscription()
		            .expectErrorMessage("foo")
		            .verify();

		assertThat(signals.size()).isEqualTo(1);
		assertThat(signals.get(0).isOnError()).as("onError expected").isTrue();
		assertThat(signals.get(0).getThrowable())
				.as("plain exception expected")
				.hasMessage("foo");
	}

	@Test
	public void testDoOnEachSignalNullConsumer() {
		assertThatNullPointerException()
				.isThrownBy(() -> Mono.just(1).doOnEach(null));
	}

	@Test
	public void testDoOnEachSignalToSubscriber() {
		AssertSubscriber<Integer> peekSubscriber = AssertSubscriber.create();
		Mono<Integer> mono = Mono.just(1)
		                         .doOnEach(s -> s.accept(peekSubscriber));
		StepVerifier.create(mono)
		            .expectSubscription()
		            .expectNext(1)
		            .expectComplete()
		            .verify();

		peekSubscriber.assertNotSubscribed();
		peekSubscriber.assertValues(1);
		peekSubscriber.assertComplete();
	}

	@Test
	public void testMonoThenManySupplier() {
		AssertSubscriber<String> ts = AssertSubscriber.create();
		Flux<String> test = Mono.just(1).thenMany(Flux.defer(() -> Flux.just("A", "B")));

		test.subscribe(ts);
		ts.assertValues("A", "B");
		ts.assertComplete();
	}

	// test issue https://github.com/reactor/reactor/issues/485
	@Test
	public void promiseOnErrorHandlesExceptions() throws Exception {
		CountDownLatch latch1 = new CountDownLatch(1);
		CountDownLatch latch2 = new CountDownLatch(1);

		try {
			Mono.fromCallable(() -> {
				throw new RuntimeException("Some Exception");
			})
			    .subscribeOn(Schedulers.parallel())
			    .doOnError(t -> latch1.countDown())
			    .doOnSuccess(v -> latch2.countDown())
			    .block();
		}
		catch (RuntimeException re){

		}
		assertThat(latch1.await(1, TimeUnit.SECONDS))
				.withFailMessage("Error latch was counted down").isTrue();
		assertThat(latch2.getCount()).withFailMessage("Complete latch was not counted down").isEqualTo(1L);
	}

	@Test
	public void promiseOnAfter() throws Exception {
		String h = Mono.fromCallable(() -> {
			Thread.sleep(400);
			return "hello";
		})
		               .subscribeOn(Schedulers.parallel())
		               .then(Mono.just("world"))
		               .block();
		assertThat(h)
				.withFailMessage("Alternate mono not seen")
				.isEqualTo("world");
	}

	@Test
	public void promiseDelays() throws Exception {
		Tuple2<Long, String> h = Mono.delay(Duration.ofMillis(3000))
		                             .log("time1")
		                             .map(d -> "Spring wins")
		                             .or(Mono.delay(Duration.ofMillis(2000)).log("time2").map(d -> "Spring Reactive"))
		                             .flatMap(t -> Mono.just(t+ " world"))
		                             .elapsed()
		                             .block();
		assertThat(h.getT2()).withFailMessage("Alternate mono not seen").isEqualTo("Spring Reactive world");
		System.out.println(h.getT1());
	}

	@Test
	public void testMono() throws Exception {
		MonoProcessor<String> promise = MonoProcessor.create();
		promise.onNext("test");
		final CountDownLatch successCountDownLatch = new CountDownLatch(1);
		promise.subscribe(v -> successCountDownLatch.countDown());
		assertThat(successCountDownLatch.await(10, TimeUnit.SECONDS))
				.as("latch await")
				.isTrue();
	}

	private static Mono<Integer> handle(String t) {
		return Mono.just(t.length());
	}

	@Test
	public void testMonoAndFunction() {
		StepVerifier.create(Mono.just("source")
		                        .zipWhen(t -> handle(t)))
		            .expectNextMatches(pair -> pair.getT1().equals("source") && pair.getT2() == 6)
		            .expectComplete()
		            .verify();
	}

	@Test
	public void testMonoAndFunctionEmpty() {
		StepVerifier.create(
				Mono.<String>empty().zipWhen(MonoTests::handle))
		            .expectComplete()
		            .verify();
	}

	@Test
	public void testMonoAndFunctionRightSideEmpty() {
		StepVerifier.create(
				Mono.just("foo").zipWhen(t -> Mono.empty()))
		            .expectComplete()
		            .verify();
	}
}
