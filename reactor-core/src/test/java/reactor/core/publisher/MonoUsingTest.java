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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Condition;
import org.junit.Test;
import reactor.test.StepVerifier;
import reactor.test.subscriber.AssertSubscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

public class MonoUsingTest {

	@Test
	public void resourceSupplierNull() {
		assertThatNullPointerException()
				.isThrownBy(() -> Mono.using(null, r -> Mono.empty(), r -> { }, false));
	}

	@Test
	public void sourceFactoryNull() {
		assertThatNullPointerException()
				.isThrownBy(() -> Mono.using(() -> 1, null, r -> { }, false));
	}

	@Test
	public void resourceCleanupNull() {
		assertThatNullPointerException()
				.isThrownBy(() -> Mono.using(() -> 1, r -> Mono.empty(), null, false));
	}

	@Test
	public void normal() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		AtomicInteger cleanup = new AtomicInteger();

		Mono.using(() -> 1, r -> Mono.just(1), cleanup::set, false)
		    .doAfterTerminate(() ->  assertThat(cleanup.get()).isZero())
		    .subscribe(ts);

		ts.assertValues(1)
		  .assertComplete()
		  .assertNoError();

		assertThat(cleanup.get()).isEqualTo(1);
	}

	@Test
	public void normalEager() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		AtomicInteger cleanup = new AtomicInteger();

		Mono.using(() -> 1, r -> Mono.just(1), cleanup::set)
		    .doAfterTerminate(() ->  assertThat(cleanup.get()).isZero())
		    .subscribe(ts);

		ts.assertValues(1)
		  .assertComplete()
		  .assertNoError();

		assertThat(cleanup.get()).isEqualTo(1);
	}

	void checkCleanupExecutionTime(boolean eager, boolean fail) {
		AtomicInteger cleanup = new AtomicInteger();
		AtomicBoolean before = new AtomicBoolean();

		AssertSubscriber<Integer> ts = new AssertSubscriber<Integer>() {
			@Override
			public void onError(Throwable t) {
				super.onError(t);
				before.set(cleanup.get() != 0);
			}

			@Override
			public void onComplete() {
				super.onComplete();
				before.set(cleanup.get() != 0);
			}
		};

		Mono.using(() -> 1, r -> {
			if (fail) {
				return Mono.error(new RuntimeException("forced failure"));
			}
			return Mono.just(1);
		}, cleanup::set, eager)
		    .subscribe(ts);

		if (fail) {
			ts.assertNoValues()
			  .assertError(RuntimeException.class)
			  .assertNotComplete()
			  .assertErrorMessage("forced failure");
		}
		else {
			ts.assertValues(1)
			  .assertComplete()
			  .assertNoError();
		}

		assertThat(cleanup.get()).as("cleanup").isEqualTo(1);
		assertThat(before.get()).as("before").isEqualTo(eager);
	}

	@Test
	public void checkNonEager() {
		checkCleanupExecutionTime(false, false);
	}

	@Test
	public void checkEager() {
		checkCleanupExecutionTime(true, false);
	}

	@Test
	public void checkErrorNonEager() {
		checkCleanupExecutionTime(false, true);
	}

	@Test
	public void checkErrorEager() {
		checkCleanupExecutionTime(true, true);
	}

	@Test
	public void resourceThrowsEager() {
		AssertSubscriber<Object> ts = AssertSubscriber.create();

		AtomicInteger cleanup = new AtomicInteger();

		Mono.using(() -> {
			throw new RuntimeException("forced failure");
		}, r -> Mono.just(1), cleanup::set, false)
		    .subscribe(ts);

		ts.assertNoValues()
		  .assertNotComplete()
		  .assertError(RuntimeException.class)
		  .assertErrorMessage("forced failure");

		assertThat(cleanup.get()).as("cleanup").isZero();
	}

	@Test
	public void factoryThrowsEager() {
		AssertSubscriber<Object> ts = AssertSubscriber.create();

		AtomicInteger cleanup = new AtomicInteger();

		Mono.using(() -> 1, r -> {
			throw new RuntimeException("forced failure");
		}, cleanup::set, false)
		    .subscribe(ts);

		ts.assertNoValues()
		  .assertNotComplete()
		  .assertError(RuntimeException.class)
		  .assertErrorMessage("forced failure");

		assertThat(cleanup.get()).as("cleanup").isEqualTo(1);
	}

	@Test
	public void factoryReturnsNull() {
		AssertSubscriber<Object> ts = AssertSubscriber.create();

		AtomicInteger cleanup = new AtomicInteger();

		Mono.<Integer, Integer>using(() -> 1,
				r -> null,
				cleanup::set,
				false).subscribe(ts);

		ts.assertNoValues()
		  .assertNotComplete()
		  .assertError(NullPointerException.class);

		assertThat(cleanup.get()).as("cleanup").isEqualTo(1);
	}

	@Test
	public void subscriberCancels() {
		AssertSubscriber<Integer> ts = AssertSubscriber.create();

		AtomicInteger cleanup = new AtomicInteger();

		MonoProcessor<Integer> tp = MonoProcessor.create();

		Mono.using(() -> 1, r -> tp, cleanup::set, true)
		    .subscribe(ts);

		assertThat(tp.hasDownstreams()).as("hasDownstreams").isTrue();

		tp.onNext(1);

		ts.assertValues(1)
		  .assertComplete()
		  .assertNoError();

		assertThat(cleanup.get()).as("cleanup").isEqualTo(1);
	}

	@Test
	public void sourceFactoryAndResourceCleanupThrow() {
		RuntimeException sourceEx = new IllegalStateException("sourceFactory");
		RuntimeException cleanupEx = new IllegalStateException("resourceCleanup");

		Condition<? super Throwable> suppressingFactory = new Condition<>(
				e -> {
					Throwable[] suppressed = e.getSuppressed();
					return suppressed != null && suppressed.length == 1 && suppressed[0] == sourceEx;
				}, "suppressing <%s>", sourceEx);

		Mono<String> test = new MonoUsing<>(() -> "foo",
				o -> { throw sourceEx; },
				s -> { throw cleanupEx; },
				false);

		StepVerifier.create(test)
		            .verifyErrorMatches(
				            e -> assertThat(e)
						            .hasMessage("resourceCleanup")
						            .is(suppressingFactory) != null);

	}
}
