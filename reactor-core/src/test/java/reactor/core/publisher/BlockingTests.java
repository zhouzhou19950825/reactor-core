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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class BlockingTests {

	static Scheduler scheduler;

	@BeforeAll
	public static void before() {
		scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor());
	}

	@AfterAll
	public static void after() {
		scheduler.dispose();
	}

	@Test
	public void blockingFirst() {
		assertThat(Flux.range(1, 10)
				    .publishOn(scheduler)
				    .blockFirst())
				.isEqualTo(1);
	}

	@Test
	public void blockingFirst2() {
		assertThat(Flux.range(1, 10)
				    .publishOn(scheduler)
				    .blockFirst(Duration.ofSeconds(10)))
				.isEqualTo(1);
	}

	@Test
	public void blockingFirstTimeout() {
		assertThat(Flux.empty()
		               .blockFirst(Duration.ofMillis(1))).isNull();
	}

	@Test
	public void blockingLast() {
		assertThat(Flux.range(1, 10)
				    .publishOn(scheduler)
				    .blockLast())
		.isEqualTo(10);
	}

	@Test
	public void blockingLast2() {
		assertThat(Flux.range(1, 10)
				    .publishOn(scheduler)
				    .blockLast(Duration.ofSeconds(10)))
				.isEqualTo(10);
	}

	@Test
	public void blockingLastTimeout() {
		assertThat(Flux.empty()
		               .blockLast(Duration.ofMillis(1))).isNull();
	}

	@Test
	public void blockingFirstError() {
		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> Flux.error(new RuntimeException("test"))
			                      .publishOn(scheduler)
			                      .blockFirst());
	}

	@Test
	public void blockingFirstError2() {
		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> Flux.error(new RuntimeException("test"))
				                      .publishOn(scheduler)
		                              .blockFirst(Duration.ofSeconds(1)));
	}

	@Test
	public void blockingLastError() {
		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> Flux.defer(() -> Mono.error(new RuntimeException("test")))
				                      .subscribeOn(scheduler)
				                      .blockLast());
	}

	@Test
	public void blockingLastError2() {
		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> Flux.defer(() -> Mono.error(new RuntimeException("test")))
				                      .subscribeOn(scheduler)
				                      .blockLast(Duration.ofSeconds(1)));
	}

	@Test
	public void blockingLastInterrupted() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		Thread t = new Thread(() -> {
			try {
				Flux.never()
				    .blockLast();
			}
			catch (Exception e) {
				if (Exceptions.unwrap(e) instanceof InterruptedException) {
					latch.countDown();
				}
			}
		});

		t.start();
		Thread.sleep(1000);
		t.interrupt();

		assertThat(latch.await(3, TimeUnit.SECONDS))
				.as("should be interrupted ")
				.isTrue();
	}

	/*@Test
	public void fillIn() throws Exception {
		Path sourcePath = Paths.get(
				"/Users/smaldini/work/reactor-core/src/main/java/reactor/core/publisher");

		String template =
				"package reactor.core.publisher;\n\nimport org.junit.jupiter.api.Test;\n\npublic " + "class {name} { @Test public" + " void normal(){} }";

		Flux.fromStream(Files.list(sourcePath))
		    .map(Path::toFile)
		    .filter(f -> f.getName()
		                  .startsWith("Flux") || f.getName()
		                                          .startsWith("Mono"))
		    .map(f -> {
			    try {
				    return new File(f.getAbsolutePath()
				                     .replace("main", "test")
				                     .replace(".java", "Test.java"));
			    }
			    catch (Exception t) {
				    throw Exceptions.propagate(t);
			    }
		    })
		    .filter(f -> {
			    try {
				    return f.createNewFile();
			    }
			    catch (Exception t) {
				    throw Exceptions.propagate(t);
			    }
		    })
		    .doOnNext(f -> {
			    try (FileOutputStream fo = new FileOutputStream(f)) {
				    fo.write(template.replace("{name}",
						    f.getName()
						     .replace(".java", ""))
				                     .getBytes());
			    }
			    catch (Exception t) {
				    throw Exceptions.propagate(t);
			    }
		    })
		    .subscribe(System.out::println);
	}*/

	@Test
	public void fluxBlockFirstCancelsOnce() {
		AtomicLong cancelCount = new AtomicLong();
		Flux.range(1, 10)
	        .doOnCancel(cancelCount::incrementAndGet)
	        .blockFirst();

		assertThat(cancelCount.get()).isEqualTo(1);
	}

	@Test
	public void fluxBlockLastDoesntCancel() {
		AtomicLong cancelCount = new AtomicLong();
		Flux.range(1, 10)
	        .doOnCancel(cancelCount::incrementAndGet)
	        .blockLast();

		assertThat(cancelCount.get()).isEqualTo(0);
	}

	@Test
	public void monoBlockDoesntCancel() {
		AtomicLong cancelCount = new AtomicLong();
		Mono.just("data")
	        .doOnCancel(cancelCount::incrementAndGet)
	        .block();

		assertThat(cancelCount.get()).isEqualTo(0);
	}

	@Test
	public void monoBlockOptionalDoesntCancel() {
		AtomicLong cancelCount = new AtomicLong();
		Mono.just("data")
	        .doOnCancel(cancelCount::incrementAndGet)
	        .blockOptional();

		assertThat(cancelCount.get()).isEqualTo(0);
	}
}
