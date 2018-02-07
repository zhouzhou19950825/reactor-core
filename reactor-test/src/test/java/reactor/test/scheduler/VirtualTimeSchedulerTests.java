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

package reactor.test.scheduler;

import java.util.function.Supplier;

import org.junit.After;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 */
public class VirtualTimeSchedulerTests {

	@Test
	public void cancelledAndEmptyConstantsAreNotSame() {
		assertThat(VirtualTimeScheduler.CANCELLED).isNotSameAs(VirtualTimeScheduler.EMPTY);

		assertThat(VirtualTimeScheduler.CANCELLED.isDisposed()).isTrue();
		assertThat(VirtualTimeScheduler.EMPTY.isDisposed()).isFalse();
	}

	@Test
	public void allEnabled() {
		assertThat(Schedulers.newParallel("")).isNotInstanceOf(VirtualTimeScheduler.class);
		assertThat(Schedulers.newElastic("")).isNotInstanceOf(VirtualTimeScheduler.class);
		assertThat(Schedulers.newSingle("")).isNotInstanceOf(VirtualTimeScheduler.class);

		VirtualTimeScheduler.getOrSet();

		assertThat(Schedulers.newParallel("")).isInstanceOf(VirtualTimeScheduler.class);
		assertThat(Schedulers.newElastic("")).isInstanceOf(VirtualTimeScheduler.class);
		assertThat(Schedulers.newSingle("")).isInstanceOf(VirtualTimeScheduler.class);

		VirtualTimeScheduler t = VirtualTimeScheduler.get();

		assertThat(Schedulers.newParallel("")).isSameAs(t);
		assertThat(Schedulers.newElastic("")).isSameAs(t);
		assertThat(Schedulers.newSingle("")).isSameAs(t);
	}

	@Test
	public void enableProvidedAllSchedulerIdempotent() {
		VirtualTimeScheduler vts = VirtualTimeScheduler.create();

		VirtualTimeScheduler.getOrSet(vts);

		assertThat(vts).isSameAs(uncache(Schedulers.single()));
		assertThat(vts.shutdown).as("initially not shutdown").isFalse();

		VirtualTimeScheduler.getOrSet(vts);

		assertThat(vts).isSameAs(uncache(Schedulers.single()));
		assertThat(vts.shutdown).as("still not shutdown").isFalse();
	}

	@Test
	public void enableTwoSimilarSchedulersUsesFirst() {
		VirtualTimeScheduler vts1 = VirtualTimeScheduler.create();
		VirtualTimeScheduler vts2 = VirtualTimeScheduler.create();

		VirtualTimeScheduler firstEnableResult = VirtualTimeScheduler.getOrSet(vts1);
		VirtualTimeScheduler secondEnableResult = VirtualTimeScheduler.getOrSet(vts2);

		assertThat(vts1).isSameAs(firstEnableResult);
		assertThat(vts1).isSameAs(secondEnableResult);
		assertThat(vts1).isSameAs(uncache(Schedulers.single()));
		assertThat(vts1.shutdown).as("initially not shutdown").isFalse();
	}

	@Test
	public void disposedSchedulerIsStillCleanedUp() {
		VirtualTimeScheduler vts = VirtualTimeScheduler.create();
		vts.dispose();
		assertThat(VirtualTimeScheduler.isFactoryEnabled()).isFalse();

		StepVerifier.withVirtualTime(() -> Mono.just("foo"),
				() -> vts, Long.MAX_VALUE)
	                .then(() -> assertThat(VirtualTimeScheduler.isFactoryEnabled()).isTrue())
	                .then(() -> assertThat(VirtualTimeScheduler.get()).isSameAs(vts))
	                .expectNext("foo")
	                .verifyComplete();

		assertThat(VirtualTimeScheduler.isFactoryEnabled()).isFalse();

		StepVerifier.withVirtualTime(() -> Mono.just("foo"))
	                .then(() -> assertThat(VirtualTimeScheduler.isFactoryEnabled()).isTrue())
	                .then(() -> assertThat(VirtualTimeScheduler.get()).isNotSameAs(vts))
	                .expectNext("foo")
	                .verifyComplete();

		assertThat(VirtualTimeScheduler.isFactoryEnabled()).isFalse();
	}


	@SuppressWarnings("unchecked")
	private static Scheduler uncache(Scheduler potentialCached) {
		if (potentialCached instanceof Supplier) {
			return ((Supplier<Scheduler>) potentialCached).get();
		}
		return potentialCached;
	}

	@After
	public void cleanup() {
		VirtualTimeScheduler.reset();
	}

}