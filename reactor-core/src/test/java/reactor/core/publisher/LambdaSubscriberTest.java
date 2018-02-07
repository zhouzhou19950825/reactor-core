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

import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Scannable;

import static org.assertj.core.api.Assertions.fail;

public class LambdaSubscriberTest {

	@Test
	public void consumeOnSubscriptionNotifiesError() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaSubscriber<String> tested = new LambdaSubscriber<>(
				value -> {},
				errorHolder::set,
				() -> {},
				subscription -> { throw new IllegalArgumentException(); });

		TestSubscription testSubscription = new TestSubscription();

		//the error is expected to be propagated through onError
		tested.onSubscribe(testSubscription);

		Assertions.assertThat(errorHolder.get()).isInstanceOf(IllegalArgumentException.class);
		Assertions.assertThat(testSubscription.isCancelled).withFailMessage("subscription has not been cancelled").isTrue();
		Assertions.assertThat(testSubscription.requested).as("requested").isEqualTo(-1L);
	}

	@Test
	public void consumeOnSubscriptionThrowsFatal() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaSubscriber<String> tested = new LambdaSubscriber<>(
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

		Assertions.assertThat(errorHolder.get()).isNull();
		Assertions.assertThat(testSubscription.isCancelled)
		          .withFailMessage("subscription has been cancelled despite fatal exception")
		          .isFalse();
		Assertions.assertThat(testSubscription.requested).as("requested").isEqualTo(-1L);
	}

	@Test
	public void consumeOnSubscriptionReceivesSubscriptionAndRequests32() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);
		AtomicReference<Subscription> subscriptionHolder = new AtomicReference<>(null);
		LambdaSubscriber<String> tested = new LambdaSubscriber<>(
				value -> {},
				errorHolder::set,
				() -> { },
				s -> {
					subscriptionHolder.set(s);
					s.request(32);
				});
		TestSubscription testSubscription = new TestSubscription();

		tested.onSubscribe(testSubscription);

		Assertions.assertThat(errorHolder.get()).isNull();
		Assertions.assertThat(testSubscription.isCancelled).withFailMessage("subscription has been cancelled").isFalse();
		Assertions.assertThat(subscriptionHolder.get()).withFailMessage("didn't consume the subscription")
		          .isEqualTo(testSubscription);
		Assertions.assertThat(testSubscription.requested).as("requested").isEqualTo(32L);
	}

	@Test
	public void noSubscriptionConsumerTriggersRequestOfMax() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);
		LambdaSubscriber<String> tested = new LambdaSubscriber<>(
				value -> {},
				errorHolder::set,
				() -> {},
				null); //defaults to initial request of max
		TestSubscription testSubscription = new TestSubscription();

		tested.onSubscribe(testSubscription);

		Assertions.assertThat(errorHolder.get()).isNull();
		Assertions.assertThat(testSubscription.isCancelled).withFailMessage("subscription has been cancelled").isFalse();
		Assertions.assertThat(testSubscription.requested)
		          .as("requested subscription").isNotEqualTo(-1L)
		          .as("requested max").isEqualTo(Long.MAX_VALUE);
	}

	@Test
	public void onNextConsumerExceptionTriggersCancellation() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaSubscriber<String> tested = new LambdaSubscriber<>(
				value -> { throw new IllegalArgumentException(); },
				errorHolder::set,
				() -> {},
				null);

		TestSubscription testSubscription = new TestSubscription();
		tested.onSubscribe(testSubscription);

		//the error is expected to be propagated through onError
		tested.onNext("foo");

		Assertions.assertThat(errorHolder.get()).isInstanceOf(IllegalArgumentException.class);
		Assertions.assertThat(testSubscription.isCancelled).withFailMessage("subscription has not been cancelled").isTrue();
	}

	@Test
	public void onNextConsumerFatalDoesntTriggerCancellation() {
		AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

		LambdaSubscriber<String> tested = new LambdaSubscriber<>(
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

		Assertions.assertThat(errorHolder.get()).isNull();
		Assertions.assertThat(testSubscription.isCancelled)
		          .withFailMessage("subscription has been cancelled despite fatal exception").isFalse();
	}

	@Test
	public void scan() {
		LambdaSubscriber<String> test = new LambdaSubscriber<>(null, null, null, null);
		Subscription parent = Operators.emptySubscription();
		test.onSubscribe(parent);

		Assertions.assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		Assertions.assertThat(test.scan(Scannable.Attr.PREFETCH)).isEqualTo(Integer.MAX_VALUE);

		Assertions.assertThat(test.scan(Scannable.Attr.TERMINATED)).isFalse();
		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();

		test.dispose();

		Assertions.assertThat(test.scan(Scannable.Attr.TERMINATED)).isTrue();
		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
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