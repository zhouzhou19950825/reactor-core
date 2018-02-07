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

package reactor.core.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;

public class SchedulersTest {

	final static class TestSchedulers implements Schedulers.Factory {

		final Scheduler      elastic  = Schedulers.Factory.super.newElastic(60, Thread::new);
		final Scheduler      single   = Schedulers.Factory.super.newSingle(Thread::new);
		final Scheduler      parallel =	Schedulers.Factory.super.newParallel(1, Thread::new);

		TestSchedulers(boolean disposeOnInit) {
			if (disposeOnInit) {
				elastic.dispose();
				single.dispose();
				parallel.dispose();
			}
		}

		public final Scheduler newElastic(int ttlSeconds, ThreadFactory threadFactory) {
			assertThat(((Schedulers.SchedulerThreadFactory)threadFactory).get()).isEqualTo("unused");
			return elastic;
		}

		public final Scheduler newParallel(int parallelism, ThreadFactory threadFactory) {
			assertThat(((Schedulers.SchedulerThreadFactory)threadFactory).get()).isEqualTo("unused");
			return parallel;
		}

		public final Scheduler newSingle(ThreadFactory threadFactory) {
			assertThat(((Schedulers.SchedulerThreadFactory)threadFactory).get()).isEqualTo("unused");
			return single;
		}
	}

	final static Condition<Scheduler> CACHED_SCHEDULER = new Condition<>(
			s -> s instanceof Schedulers.CachedScheduler, "a cached scheduler");

	@AfterEach
	public void resetSchedulers() {
		Schedulers.resetFactory();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerFusedCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(Mono.<String>fromCallable(() -> {
					throw new StackOverflowError("boom");
				}).subscribeOn(scheduler))
				            .expectFusion()
				            .expectErrorMessage("boom");

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerSyncCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(Mono.<String>fromCallable(() -> {
					throw new StackOverflowError("boom");
				}).hide()
				  .subscribeOn(scheduler))
				            .expectNoFusionSupport()
				            .expectErrorMessage("boom"); //ignored

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerSyncInnerCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(
						Flux.just("hi")
						    .flatMap(item -> Mono.<String>fromCallable(() -> {
							    throw new StackOverflowError("boom");
						    })
								    .hide()
								    .subscribeOn(scheduler))
				)
				            .expectNoFusionSupport()
				            .expectErrorMessage("boom"); //ignored

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void handleErrorWithJvmFatalForwardsToUncaughtHandlerFusedInnerCallable() {
		AtomicBoolean handlerCaught = new AtomicBoolean();
		Scheduler scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r);
			thread.setUncaughtExceptionHandler((t, ex) -> {
				handlerCaught.set(true);
				System.err.println("from uncaught handler: " + ex.toString());
			});
			return thread;
		}));

		final StepVerifier stepVerifier =
				StepVerifier.create(
						Flux.just("hi")
						    .flatMap(item -> Mono.<String>fromCallable(() -> {
							    throw new StackOverflowError("boom");
						    })
								    .subscribeOn(scheduler))
				)
				            .expectFusion()
				            .expectErrorMessage("boom"); //ignored

		//the exception is still fatal, so the StepVerifier should time out.
		assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> stepVerifier.verify(Duration.ofMillis(100)))
				.withMessageStartingWith("VerifySubscriber timed out on ");

		//nonetheless, the uncaught exception handler should have been invoked
		assertThat(handlerCaught).as("uncaughtExceptionHandler used").isTrue();
	}

	@Test
	public void testOverride() throws InterruptedException {

		TestSchedulers ts = new TestSchedulers(true);
		Schedulers.setFactory(ts);

		assertThat(Schedulers.newSingle("unused")).as("single").isSameAs(ts.single);
		assertThat(Schedulers.newElastic("unused")).as("elastic").isSameAs(ts.elastic);
		assertThat(Schedulers.newParallel("unused")).as("parallel").isSameAs(ts.parallel);

		Schedulers.resetFactory();

		Scheduler s = Schedulers.newSingle("unused");
		s.dispose();

		assertThat(s).as("s").isNotSameAs(ts.single);
	}

	@Test
	public void testShutdownOldOnSetFactory() {
		Schedulers.Factory ts1 = new Schedulers.Factory() { };
		Schedulers.Factory ts2 = new TestSchedulers(false);
		Schedulers.setFactory(ts1);
		Scheduler cachedTimerOld = Schedulers.single();
		Scheduler standaloneTimer = Schedulers.newSingle("standaloneTimer");

		assertThat(standaloneTimer).as("standalone").isNotSameAs(cachedTimerOld);
		assertThat(cachedTimerOld.schedule(() -> {})).as("cached scheduling").isNotNull();
		assertThat(standaloneTimer.schedule(() -> {})).as("standalone scheduling").isNotNull();

		Schedulers.setFactory(ts2);
		Scheduler cachedTimerNew = Schedulers.newSingle("unused");

		assertThat(cachedTimerNew)
				.as("cachedTimerNew")
				.isSameAs(Schedulers.newSingle("unused"))
				.isNotSameAs(cachedTimerOld);
		//assert that the old factory"s cached scheduler was shut down
		Assertions.assertThatExceptionOfType(RejectedExecutionException.class)
		          .isThrownBy(() -> cachedTimerOld.schedule(() -> {}));
		//independently created schedulers are still the programmer"s responsibility
		assertThat(standaloneTimer.schedule(() -> {})).as("2nd standalone scheduling").isNotNull();
		//new factory = new alive cached scheduler
		assertThat(cachedTimerNew.schedule(() -> {})).as("cachedTimerNew scheduling").isNotNull();
	}

	@Test
	public void testUncaughtHookCalledWhenOnErrorNotImplemented() {
		AtomicBoolean handled = new AtomicBoolean(false);
		Schedulers.onHandleError((t, e) -> handled.set(true));

		try {
			Schedulers.handleError(Exceptions.errorCallbackNotImplemented(new IllegalArgumentException()));
		} finally {
			Schedulers.resetOnHandleError();
		}
		assertThat(handled.get()).withFailMessage("errorCallbackNotImplemented not handled").isTrue();
	}

	@Test
	public void testUncaughtHookCalledWhenCommonException() {
		AtomicBoolean handled = new AtomicBoolean(false);
		Schedulers.onHandleError((t, e) -> handled.set(true));

		try {
			Schedulers.handleError(new IllegalArgumentException());
		} finally {
			Schedulers.resetOnHandleError();
		}
		assertThat(handled.get()).withFailMessage("IllegalArgumentException not handled").isTrue();
	}

	@Test
	public void testUncaughtHooksCalledWhenThreadDeath() {
		AtomicReference<Throwable> onHandleErrorInvoked = new AtomicReference<>();
		AtomicReference<Throwable> globalUncaughtInvoked = new AtomicReference<>();

		Schedulers.onHandleError((t, e) -> onHandleErrorInvoked.set(e));
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> globalUncaughtInvoked.set(e));

		ThreadDeath fatal = new ThreadDeath();

		//written that way so that we can always reset the hook
		Throwable thrown = catchThrowable(() -> Schedulers.handleError(fatal));
		Schedulers.resetOnHandleError();

		assertThat(thrown)
				.as("fatal exceptions not thrown")
				.isNull();

		assertThat(onHandleErrorInvoked).as("onHandleError invoked")
		                                .hasValue(fatal);
		assertThat(globalUncaughtInvoked).as("global uncaught handler invoked")
		                                 .hasValue(fatal);
	}

	@Test
	public void testRejectingSingleScheduler() {
		assertRejectingScheduler(Schedulers.newSingle("test"));
	}

	@Test
	public void testRejectingParallelScheduler() {
		assertRejectingScheduler(Schedulers.newParallel("test"));
	}

	@Test
	public void testRejectingExecutorServiceScheduler() {
		assertRejectingScheduler(Schedulers.fromExecutorService(Executors.newSingleThreadExecutor()));
	}

	public void assertRejectingScheduler(Scheduler scheduler) {
		try {
			DirectProcessor<String> p = DirectProcessor.create();

			AtomicReference<String> r = new AtomicReference<>();
			CountDownLatch l = new CountDownLatch(1);

			p.publishOn(scheduler)
			 .log()
			 .subscribe(r::set, null, l::countDown);

			scheduler.dispose();

			p.onNext("reject me");
			l.await(3, TimeUnit.SECONDS);
		}
		catch (Exception ree) {
			ree.printStackTrace();
			Throwable throwable = Exceptions.unwrap(ree);
			if (throwable instanceof RejectedExecutionException) {
				return;
			}
			fail(throwable + " is not a RejectedExecutionException");
		}
		finally {
			scheduler.dispose();
		}
	}

	//private final int             BUFFER_SIZE     = 8;
	private final AtomicReference<Throwable> exceptionThrown = new AtomicReference<>();
	private final int                        N               = 17;

	@Test
	public void testDispatch() throws Exception {
		Scheduler service = Schedulers.newSingle(r -> {
			Thread t = new Thread(r, "dispatcher");
			t.setUncaughtExceptionHandler((t1, e) -> exceptionThrown.set(e));
			return t;
		});

		service.dispose();
	}

	@Test
	public void immediateTaskIsExecuted() throws Exception {
		Scheduler serviceRB = Schedulers.newSingle("rbWork");
		Scheduler.Worker r = serviceRB.createWorker();

		long start = System.currentTimeMillis();
		AtomicInteger latch = new AtomicInteger(1);
		Consumer<String> c =  ev -> {
			latch.decrementAndGet();
			try {
				System.out.println("ev: "+ev);
				Thread.sleep(1000);
			}
			catch(InterruptedException ie){
				throw Exceptions.propagate(ie);
			}
		};
		r.schedule(() -> c.accept("Hello World!"));

		Thread.sleep(1200);
		long end = System.currentTimeMillis();

		serviceRB.dispose();

		assertThat(latch.intValue()).withFailMessage("Event missed").isZero();
		assertThat((end - start) >= 1000).withFailMessage("Timeout too long").isTrue();
	}

	@Test
	public void immediateTaskIsSkippedIfDisposeRightAfter() throws Exception {
		Scheduler serviceRB = Schedulers.newSingle("rbWork");
		Scheduler.Worker r = serviceRB.createWorker();

		long start = System.currentTimeMillis();
		AtomicInteger latch = new AtomicInteger(1);
		Consumer<String> c =  ev -> {
			latch.decrementAndGet();
			try {
				System.out.println("ev: "+ev);
				Thread.sleep(1000);
			}
			catch(InterruptedException ie){
				throw Exceptions.propagate(ie);
			}
		};
		r.schedule(() -> c.accept("Hello World!"));
		serviceRB.dispose();

		Thread.sleep(1200);
		long end = System.currentTimeMillis();

		assertThat(latch.intValue()).withFailMessage("Task not skipped").isEqualTo(1);
		assertThat((end - start) >= 1000).withFailMessage("Timeout too long").isTrue();
	}

	@Test
	public void singleSchedulerPipelining() throws Exception {
		Scheduler serviceRB = Schedulers.newSingle("rb", true);
		Scheduler.Worker dispatcher = serviceRB.createWorker();

		try {
			Thread t1 = Thread.currentThread();
			Thread[] t2 = { null };

			CountDownLatch cdl = new CountDownLatch(1);

			dispatcher.schedule(() -> { t2[0] = Thread.currentThread(); cdl.countDown(); });

			if (!cdl.await(5, TimeUnit.SECONDS)) {
				fail("single timed out");
			}

			assertThat(t1).isNotSameAs(t2[0]);
		} finally {
			dispatcher.dispose();
		}
	}

	@Test
	public void testCachedSchedulerDelegates() {
		Scheduler mock = new Scheduler() {
			@Override
			public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
				throw new IllegalStateException("scheduleTaskDelay");
			}

			@Override
			public Disposable schedulePeriodically(Runnable task, long initialDelay,
					long period, TimeUnit unit) {
				throw new IllegalStateException("schedulePeriodically");
			}

			@Override
			public Worker createWorker() {
				throw new IllegalStateException("createWorker");
			}

			@Override
			public Disposable schedule(Runnable task) {
				throw new IllegalStateException("scheduleTask");
			}

			@Override
			public boolean isDisposed() {
				throw new IllegalStateException("isDisposed");
			}

			@Override
			public void dispose() {
				throw new IllegalStateException("dispose");
			}

			@Override
			public long now(TimeUnit unit) {
				throw new IllegalStateException("now");
			}

			@Override
			public void start() {
				throw new IllegalStateException("start");
			}

		};

		Schedulers.CachedScheduler cached = new Schedulers.CachedScheduler("cached", mock);

		//dispose is bypassed by the cached version
		cached.dispose();
		cached.dispose();

		//other methods delegate
		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.schedule(null))
	            .withMessage("scheduleTask");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.schedule(null, 1000, TimeUnit.MILLISECONDS))
	            .withMessage("scheduleTaskDelay");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.schedulePeriodically(null, 1000, 1000, TimeUnit.MILLISECONDS))
	            .withMessage("schedulePeriodically");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> cached.now(TimeUnit.MILLISECONDS))
	            .withMessage("now");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(cached::start)
	            .withMessage("start");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(cached::createWorker)
	            .withMessage("createWorker");

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(cached::isDisposed)
	            .withMessage("isDisposed");
	}


	@Test
	public void parallelSchedulerThreadCheck() throws Exception{
		assertTimeout(Duration.ofSeconds(5), () -> {
			Scheduler s = Schedulers.newParallel("work", 2);
			try {
				Scheduler.Worker w = s.createWorker();

				Thread currentThread = Thread.currentThread();
				AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
				CountDownLatch latch = new CountDownLatch(1);

				w.schedule(() -> {
					taskThread.set(Thread.currentThread());
					latch.countDown();
				});

				latch.await();

				assertThat(taskThread.get()).isNotEqualTo(currentThread);
			}
			finally {
				s.dispose();
			}
		});
	}

	@Test
	public void singleSchedulerThreadCheck() throws Exception{
		assertTimeout(Duration.ofSeconds(5), () -> {
			Scheduler s = Schedulers.newSingle("work");
			try {
				Scheduler.Worker w = s.createWorker();

				Thread currentThread = Thread.currentThread();
				AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
				CountDownLatch latch = new CountDownLatch(1);

				w.schedule(() -> {
					taskThread.set(Thread.currentThread());
					latch.countDown();
				});

				latch.await();

				assertThat(taskThread.get()).isNotEqualTo(currentThread);
			}
			finally {
				s.dispose();
			}
		});
	}


	@Test
	public void elasticSchedulerThreadCheck() throws Exception{
		assertTimeout(Duration.ofSeconds(5), () -> {
			Scheduler s = Schedulers.newElastic("work");
			try {
				Scheduler.Worker w = s.createWorker();

				Thread currentThread = Thread.currentThread();
				AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
				CountDownLatch latch = new CountDownLatch(1);

				w.schedule(() -> {
					taskThread.set(Thread.currentThread());
					latch.countDown();
				});

				latch.await();

				assertThat(taskThread.get()).isNotEqualTo(currentThread);
			}
			finally {
				s.dispose();
			}
		});
	}

	@Test
	public void executorThreadCheck() throws Exception{
		assertTimeout(Duration.ofSeconds(5), () -> {
			ExecutorService es = Executors.newSingleThreadExecutor();
			Scheduler s = Schedulers.fromExecutor(es::execute);

			try {
				Scheduler.Worker w = s.createWorker();

				Thread currentThread = Thread.currentThread();
				AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
				CountDownLatch latch = new CountDownLatch(1);

				w.schedule(() -> {
					taskThread.set(Thread.currentThread());
					latch.countDown();
				});

				latch.await();

				assertThat(taskThread.get()).isNotEqualTo(currentThread);
			}
			finally {
				s.dispose();
				es.shutdownNow();
			}
		});
	}

	@Test
	public void executorThreadCheck2() throws Exception{
		assertTimeout(Duration.ofSeconds(5), () -> {
			ExecutorService es = Executors.newSingleThreadExecutor();
			Scheduler s = Schedulers.fromExecutor(es::execute, true);

			try {
				Scheduler.Worker w = s.createWorker();

				Thread currentThread = Thread.currentThread();
				AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
				CountDownLatch latch = new CountDownLatch(1);

				w.schedule(() -> {
					taskThread.set(Thread.currentThread());
					latch.countDown();
				});

				latch.await();

				assertThat(taskThread.get()).isNotEqualTo(currentThread);
			}
			finally {
				s.dispose();
				es.shutdownNow();
			}
		});
	}

	@Test
	public void sharedSingleCheck() throws Exception{
		assertTimeout(Duration.ofSeconds(5), () -> {
			Scheduler p = Schedulers.newParallel("shared");
			Scheduler s = Schedulers.single(p);

			try {
				for(int i = 0; i < 3; i++) {
					Scheduler.Worker w = s.createWorker();

					Thread currentThread = Thread.currentThread();
					AtomicReference<Thread> taskThread = new AtomicReference<>(currentThread);
					CountDownLatch latch = new CountDownLatch(1);

					w.schedule(() -> {
						taskThread.set(Thread.currentThread());
						latch.countDown();
					});

					latch.await();

					assertThat(taskThread.get()).isNotEqualTo(currentThread);
				}
			}
			finally {
				s.dispose();
				p.dispose();
			}
		});
	}

	void recursiveCall(Scheduler.Worker w, CountDownLatch latch, int data){
		if (data < 2) {
			latch.countDown();
			w.schedule(() -> recursiveCall(w,  latch,data + 1));
		}
	}

	@Test
	public void recursiveParallelCall() throws Exception {
		Scheduler s = Schedulers.newParallel("work", 4);
		try {
			Scheduler.Worker w = s.createWorker();

			CountDownLatch latch = new CountDownLatch(2);

			w.schedule(() -> recursiveCall(w, latch, 0));

			latch.await();
		}
		finally {
			s.dispose();
		}
	}

	@Test
	public void pingPongParallelCall() throws Exception {
		Scheduler s = Schedulers.newParallel("work", 4);
		try {
			Scheduler.Worker w = s.createWorker();
			Thread t = Thread.currentThread();
			AtomicReference<Thread> t1 = new AtomicReference<>(t);
			AtomicReference<Thread> t2 = new AtomicReference<>(t);

			CountDownLatch latch = new CountDownLatch(4);

			AtomicReference<Runnable> pong = new AtomicReference<>();

			Runnable ping = () -> {
				if(latch.getCount() > 0){
					t1.set(Thread.currentThread());
					w.schedule(pong.get());
					latch.countDown();
				}
			};

			pong.set(() -> {
				if(latch.getCount() > 0){
					t2.set(Thread.currentThread());
					w.schedule(ping);
					latch.countDown();
				}
			});

			w.schedule(ping);

			latch.await();

			assertThat(t).isNotEqualTo(t1.get());
			assertThat(t).isNotEqualTo(t2.get());
		}
		finally {
			s.dispose();
		}
	}

	@Test
	public void restartParallel() {
		restart(Schedulers.newParallel("test"));
	}

//	@Test
//	public void restartTimer() {
//		restart(Schedulers.newTimer("test"));
//	}
//
//	@Test
//	public void restartElastic() {
//		restart(Schedulers.newElastic("test"));
//	}

	@Test
	public void restartSingle(){
		restart(Schedulers.newSingle("test"));
	}

	void restart(Scheduler s){
		Thread t = Mono.fromCallable(Thread::currentThread)
		               .subscribeOn(s)
		               .block();

		s.dispose();
		s.start();

		Thread t2 = Mono.fromCallable(Thread::currentThread)
		                .subscribeOn(s)
		                .block();

		assertThat(t).isNotEqualTo(Thread.currentThread());
		assertThat(t).isNotEqualTo(t2);
	}

	@Test
	public void testDefaultMethods(){
		EmptyScheduler s = new EmptyScheduler();

		s.dispose();
		assertThat(s.disposeCalled).isTrue();

		EmptyScheduler.EmptyWorker w = s.createWorker();
		w.dispose();
		assertThat(w.disposeCalled).isTrue();


		EmptyTimedScheduler ts = new EmptyTimedScheduler();
		ts.dispose();//noop
		ts.start();
		EmptyTimedScheduler.EmptyTimedWorker tw = ts.createWorker();
		tw.dispose();

		long before = System.currentTimeMillis();

		assertThat(ts.now(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(before)
		                                         .isLessThanOrEqualTo(System.currentTimeMillis());

//		assertThat(tw.now(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(before)
//		                                        .isLessThanOrEqualTo(System.currentTimeMillis());

		//noop
		new Schedulers(){

		};

		//noop
		Schedulers.elastic().dispose();
	}

	@Test
	public void scanExecutorCapacity() {
		Executor plain = Runnable::run;
		ExecutorService plainService = Executors.newSingleThreadExecutor();

		ExecutorService threadPool = Executors.newFixedThreadPool(3);
		ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(4);

		DelegateServiceScheduler.UnsupportedScheduledExecutorService unsupportedScheduledExecutorService =
				new DelegateServiceScheduler.UnsupportedScheduledExecutorService(threadPool);

		try {
			assertThat(Schedulers.scanExecutor(plain, Scannable.Attr.CAPACITY))
					.as("plain").isEqualTo(null);
			assertThat(Schedulers.scanExecutor(plainService, Scannable.Attr.CAPACITY))
					.as("plainService").isEqualTo(null);
			assertThat(Schedulers.scanExecutor(threadPool, Scannable.Attr.CAPACITY))
					.as("threadPool").isEqualTo(3);
			assertThat(Schedulers.scanExecutor(scheduledThreadPool, Scannable.Attr.CAPACITY))
					.as("scheduledThreadPool").isEqualTo(Integer.MAX_VALUE);

			assertThat(Schedulers.scanExecutor(unsupportedScheduledExecutorService, Scannable.Attr.CAPACITY))
					.as("unwrapped").isEqualTo(3);
		}
		finally {
			plainService.shutdownNow();
			unsupportedScheduledExecutorService.shutdownNow();
			threadPool.shutdownNow();
			scheduledThreadPool.shutdownNow();
		}
	}

	@Test
	public void scanSupportBuffered() throws InterruptedException {
		Executor plain = Runnable::run;
		ExecutorService plainService = Executors.newSingleThreadExecutor();

		ExecutorService threadPool = Executors.newFixedThreadPool(3);
		ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(4);

		DelegateServiceScheduler.UnsupportedScheduledExecutorService unsupportedScheduledExecutorService =
				new DelegateServiceScheduler.UnsupportedScheduledExecutorService(threadPool);

		try {
			assertThat(Schedulers.scanExecutor(plain, Scannable.Attr.BUFFERED))
					.as("plain").isEqualTo(null);
			assertThat(Schedulers.scanExecutor(plainService, Scannable.Attr.BUFFERED))
					.as("plainService").isEqualTo(null);

			scheduledThreadPool.schedule(() -> {}, 500, TimeUnit.MILLISECONDS);
			scheduledThreadPool.schedule(() -> {}, 500, TimeUnit.MILLISECONDS);
			Thread.sleep(50); //give some leeway for the pool to have consistent accounting

			assertThat(Schedulers.scanExecutor(scheduledThreadPool, Scannable.Attr.BUFFERED))
					.as("scheduledThreadPool").isEqualTo(2);

			threadPool.submit(() -> {
				try { Thread.sleep(200); } catch (InterruptedException e) { e.printStackTrace(); }
			});

			assertThat(Schedulers.scanExecutor(threadPool, Scannable.Attr.BUFFERED))
					.as("threadPool").isEqualTo(1);
			assertThat(Schedulers.scanExecutor(unsupportedScheduledExecutorService, Scannable.Attr.BUFFERED))
					.as("unwrapped").isEqualTo(1);

			Thread.sleep(400);

			assertThat(Schedulers.scanExecutor(unsupportedScheduledExecutorService, Scannable.Attr.BUFFERED))
					.as("unwrapped after task").isEqualTo(0);
		}
		finally {
			plainService.shutdownNow();
			unsupportedScheduledExecutorService.shutdownNow();
			threadPool.shutdownNow();
			scheduledThreadPool.shutdownNow();
		}
	}

	final static class EmptyScheduler implements Scheduler {

		boolean disposeCalled;

		@Override
		public void dispose() {
			disposeCalled = true;
		}

		@Override
		public Disposable schedule(Runnable task) {
			return null;
		}

		@Override
		public EmptyWorker createWorker() {
			return new EmptyWorker();
		}

		static class EmptyWorker implements Worker {

			boolean disposeCalled;

			@Override
			public Disposable schedule(Runnable task) {
				return null;
			}

			@Override
			public void dispose() {
				disposeCalled = true;
			}
		}
	}

	@Test
	public void testDirectSchedulePeriodicallyCancelsSchedulerTask() throws Exception {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			CountDownLatch latch = new CountDownLatch(2);
			Disposable disposable = Schedulers.directSchedulePeriodically(executorService, () -> {
				latch.countDown();
			}, 0, 10, TimeUnit.MILLISECONDS);
			latch.await();

			disposable.dispose();

			assertThat(executorService.isAllTasksCancelled()).isTrue();
		}
	}

	@Test
	public void testWorkerSchedulePeriodicallyCancelsSchedulerTask() throws Exception {
		try(TaskCheckingScheduledExecutor executorService = new TaskCheckingScheduledExecutor()) {
			CountDownLatch latch = new CountDownLatch(2);
			Disposable.Composite tasks = Disposables.composite();
			Disposable disposable = Schedulers.workerSchedulePeriodically(executorService, tasks, () -> {
				latch.countDown();
			}, 0, 10, TimeUnit.MILLISECONDS);
			latch.await();

			disposable.dispose();

			assertThat(executorService.isAllTasksCancelled()).isTrue();
		}
	}

	final static class TaskCheckingScheduledExecutor extends ScheduledThreadPoolExecutor implements AutoCloseable {

		private final List<RunnableScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();

		TaskCheckingScheduledExecutor() {
			super(1);
		}

		protected <V> RunnableScheduledFuture<V> decorateTask(
				Runnable r, RunnableScheduledFuture<V> task) {
			tasks.add(task);
			return task;
		}

		protected <V> RunnableScheduledFuture<V> decorateTask(
				Callable<V> c, RunnableScheduledFuture<V> task) {
			tasks.add(task);
			return task;
		}

		boolean isAllTasksCancelled() {
			for(RunnableScheduledFuture<?> task: tasks) {
				if (!task.isCancelled()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public void close() throws Exception {
			shutdown();
		}
	}

	final static class EmptyTimedScheduler implements Scheduler {

		@Override
		public Disposable schedule(Runnable task) {
			return null;
		}

		@Override
		public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
			return null;
		}

//		@Override
//		public Disposable schedulePeriodically(Runnable task,
//				long initialDelay,
//				long period,
//				TimeUnit unit) {
//			return null;
//		}

		@Override
		public EmptyTimedWorker createWorker() {
			return new EmptyTimedWorker();
		}

		static class EmptyTimedWorker implements Worker {

			@Override
			public Disposable schedule(Runnable task) {
				return null;
			}

			@Override
			public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
				return null;
			}

			@Override
			public Disposable schedulePeriodically(Runnable task,
					long initialDelay,
					long period,
					TimeUnit unit) {
				return null;
			}

			@Override
			public void dispose() {
			}
		}
	}
}
