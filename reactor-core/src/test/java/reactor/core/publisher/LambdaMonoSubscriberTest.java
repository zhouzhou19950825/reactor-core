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

package reactor.core.publisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.Scannable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class LambdaMonoSubscriberTest {

	@Test
	public void consumeOnSubscriptionNotifiesError() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaMonoSubscriber<String> tested = new LambdaMonoSubscriber<>(
				value -> {},
				errorHolder::set,
				() -> {},
				subscription -> { throw new IllegalArgumentException(); });

		TestSubscription testSubscription = new TestSubscription();

		//the error is expected to be propagated through onError
		tested.onSubscribe(testSubscription);

		assertThat(errorHolder.get()).isInstanceOf(IllegalArgumentException.class);
		assertThat(testSubscription.isCancelled).withFailMessage("subscription has not been cancelled").isTrue();
		assertThat(testSubscription.requested).as("requested").isEqualTo(-1L);
	}

	@Test
	public void consumeOnSubscriptionThrowsFatal() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaMonoSubscriber<String> tested = new LambdaMonoSubscriber<>(
				value -> {},
				errorHolder::set,
				() -> {},
				subscription -> { throw new OutOfMemoryError(); });

		TestSubscription testSubscription = new TestSubscription();

		//the error is expected to be thrown as it is fatal
		try {
			tested.onSubscribe(testSubscription);
			fail("Expected OutOfMemoryError to be thrown");
		}
		catch (OutOfMemoryError e) {
			//expected
		}

		assertThat(errorHolder.get()).isNull();
		assertThat(testSubscription.isCancelled).withFailMessage("subscription been cancelled despite fatal exception").isFalse();
		assertThat(testSubscription.requested).as("requested").isEqualTo(-1L);
	}

	@Test
	public void consumeOnSubscriptionReceivesSubscriptionAndRequests32() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);
		AtomicReference<Subscription> subscriptionHolder = new AtomicReference<>(null);
		LambdaMonoSubscriber<String> tested = new LambdaMonoSubscriber<>(
				value -> {},
				errorHolder::set,
				() -> { },
				s -> {
					subscriptionHolder.set(s);
					s.request(32);
				});
		TestSubscription testSubscription = new TestSubscription();

		tested.onSubscribe(testSubscription);

		assertThat(errorHolder.get()).isNull();
		assertThat(testSubscription.isCancelled).withFailMessage("subscription has been cancelled").isFalse();
		assertThat(subscriptionHolder.get()).withFailMessage("didn't consume the subscription")
		                                    .isEqualTo(testSubscription);
		assertThat(testSubscription.requested).as("requested").isEqualTo(32L);
	}

	@Test
	public void noSubscriptionConsumerTriggersRequestOfMax() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);
		LambdaMonoSubscriber<String> tested = new LambdaMonoSubscriber<>(
				value -> {},
				errorHolder::set,
				() -> {},
				null); //defaults to initial request of max
		TestSubscription testSubscription = new TestSubscription();

		tested.onSubscribe(testSubscription);

		assertThat(errorHolder.get()).isNull();
		assertThat(testSubscription.isCancelled).withFailMessage("subscription has been cancelled").isFalse();
		assertThat(testSubscription.requested)
				.as("requested some").isNotEqualTo(-1L)
				.as("requested max").isEqualTo(Long.MAX_VALUE);
	}

	@Test
	public void onNextConsumerExceptionBubblesUpDoesntTriggerCancellation() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaMonoSubscriber<String> tested = new LambdaMonoSubscriber<>(
				value -> { throw new IllegalArgumentException(); },
				errorHolder::set,
				() -> {},
				null);

		TestSubscription testSubscription = new TestSubscription();
		tested.onSubscribe(testSubscription);

		//as Mono is single-value, it cancels early on onNext. this leads to an exception
		//during onNext to be bubbled up as a BubbledException, not propagated through onNext
		try {
			tested.onNext("foo");
			fail("Expected a bubbling Exception");
		} catch (RuntimeException e) {
			assertThat(e).matches(Exceptions::isBubbling, "Expected a bubbling Exception")
			             .hasCauseInstanceOf(IllegalArgumentException.class);
		}

		assertThat(errorHolder.get()).isNull();
		assertThat(testSubscription.isCancelled).withFailMessage("subscription has been cancelled").isFalse();
	}

	@Test
	public void onNextConsumerFatalDoesntTriggerCancellation() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaMonoSubscriber<String> tested = new LambdaMonoSubscriber<>(
				value -> { throw new OutOfMemoryError(); },
				errorHolder::set,
				() -> {},
				null);

		TestSubscription testSubscription = new TestSubscription();
		tested.onSubscribe(testSubscription);

		//the error is expected to be thrown as it is fatal
		try {
			tested.onNext("foo");
			fail("Expected OutOfMemoryError to be thrown");
		}
		catch (OutOfMemoryError e) {
			//expected
		}

		assertThat(errorHolder.get()).isNull();
		assertThat(testSubscription.isCancelled)
				.withFailMessage("subscription has been cancelled")
				.isFalse();
	}

	@Test
	public void emptyMonoState() {
		Mono<Object> monoDirect = Mono.fromDirect(s -> {
			assertThat(s).isInstanceOf(LambdaMonoSubscriber.class);
			LambdaMonoSubscriber<?> bfs = (LambdaMonoSubscriber<?>) s;
			assertThat(bfs.scan(Scannable.Attr.PREFETCH)).as("scan PREFETCH").isEqualTo(Integer.MAX_VALUE);
			assertThat(bfs.scan(Scannable.Attr.TERMINATED)).as("scan TERMINATED").isFalse();
			bfs.onSubscribe(Operators.emptySubscription());
			bfs.onSubscribe(Operators.emptySubscription()); // noop
			s.onComplete();
			assertThat(bfs.scan(Scannable.Attr.TERMINATED)).as("scan TERMINATED after complete").isTrue();
			bfs.dispose();
			bfs.dispose();
		});

		Disposable sub = monoDirect.subscribe(s -> { }, null, () -> { });

		assertThat(sub.isDisposed()).as("subscription disposed").isTrue();

		assertThat(Mono.never().subscribe(null, null, () -> {}).isDisposed())
				.as("never() subscription disposed")
				.isFalse();
	}

	@Test
	public void errorMonoState(){
		Hooks.onErrorDropped(e -> assertThat(e).as("onErrorDropped").hasMessage("test2"));
		Hooks.onNextDropped(d -> assertThat(d).as("onNextDropped").isEqualTo("test2"));
		try {
			Mono.fromDirect(s -> {
				assertThat(s).isInstanceOf(LambdaMonoSubscriber.class);
				LambdaMonoSubscriber<?> bfs = (LambdaMonoSubscriber<?>) s;
				Operators.error(s, new Exception("test"));
				s.onComplete();
				s.onError(new Exception("test2"));
				s.onNext("test2");
				assertThat(bfs.scan(Scannable.Attr.TERMINATED)).as("scan TERMINATED").isTrue();
				bfs.dispose();
			})
			          .subscribe(s -> {
			          }, e -> {
			          }, () -> {
			          });
		}
		finally {
			Hooks.resetOnErrorDropped();
			Hooks.resetOnNextDropped();
		}
	}

	@Test
	public void completeHookErrorDropped() {
		Hooks.onErrorDropped(e -> assertThat(e).as("onErrorDropped").hasMessage("complete"));
		try {
			Mono.just("foo")
		        .subscribe(v -> {},
				        e -> {},
				        () -> { throw new IllegalStateException("complete");});
		}
		finally {
			Hooks.resetOnErrorDropped();
		}
	}

	@Test
	public void noErrorHookThrowsCallbackNotImplemented() {
		RuntimeException boom = new IllegalArgumentException("boom");
		Assertions.assertThatExceptionOfType(RuntimeException.class)
		          .isThrownBy(() -> Mono.error(boom).subscribe(v -> {}))
	              .withCause(boom)
	              .hasToString("reactor.core.Exceptions$ErrorCallbackNotImplemented: java.lang.IllegalArgumentException: boom");
	}

	@Test
	public void testCancel() {
		AtomicLong cancelCount = new AtomicLong();
		Mono.delay(Duration.ofMillis(500))
		    .doOnCancel(cancelCount::incrementAndGet)
		    .subscribe(v -> {})
		    .dispose();
		assertThat(cancelCount.get()).isEqualTo(1);
	}

	@Test
	public void scan() {
		LambdaMonoSubscriber<String> test = new LambdaMonoSubscriber<>(null, null, null, null);
		Subscription parent = Operators.emptySubscription();
		test.onSubscribe(parent);

		assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		assertThat(test.scan(Scannable.Attr.PREFETCH)).isEqualTo(Integer.MAX_VALUE);

		assertThat(test.scan(Scannable.Attr.TERMINATED)).isFalse();
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();

		test.dispose();

		assertThat(test.scan(Scannable.Attr.TERMINATED)).isTrue();
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	private static class TestSubscription implements Subscription {

		volatile boolean isCancelled = false;
		volatile long    requested   = -1L;

		@Override
		public void request(long n) {
			this.requested = n;
		}

		@Override
		public void cancel() {
			this.isCancelled = true;
		}
	}
}