/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.concurrent.WaitStrategy;

/**
 * Utility class to create various flavors of {@link  BalancedFluxProcessor Flux Processors}.
 *
 * @author Simon Baslé
 */
public final class Processors {

	/**
	 * Create a "direct" {@link BalancedFluxProcessor}: it can dispatch signals to zero to many
	 * {@link Subscriber Subscribers}, but has the limitation of not
	 * handling backpressure.
	 * <p>
	 * As a consequence, a direct Processor signals an {@link IllegalStateException} to its
	 * subscribers if you push N elements through it but at least one of its subscribers has
	 * requested less than N.
	 * <p>
	 * Once the Processor has terminated (usually through its {@link BalancedFluxProcessor#sink() Sink}
	 * {@link FluxSink#error(Throwable)} or {@link FluxSink#complete()} methods being called),
	 * it lets more subscribers subscribe but replays the termination signal to them immediately.
	 *
	 * @param <T> the type of the data flowing through the processor
	 * @return a new direct {@link BalancedFluxProcessor}
	 */
	@SuppressWarnings("deprecation")
	public static final <T> BalancedFluxProcessor<T> direct() {
		return new DirectProcessor<>();
	}

	/**
	 * Create an unbounded "unicast" {@link BalancedFluxProcessor}, which can deal with
	 * backpressure using an internal buffer, but <strong>can have at most one
	 * {@link Subscriber}</strong>.
	 * <p>
	 * The returned unicast Processor is unbounded: if you push any amount of data through
	 * it while its {@link Subscriber} has not yet requested data, it will buffer
	 * all of the data. Use the builder variant to provide a {@link Queue} through the
	 * {@link #unicast(Queue)} method, which returns a builder that allows further
	 * configuration.
	 *
	 * @param <T>
	 * @return a new unicast {@link BalancedFluxProcessor}
	 */
	public static final <T> BalancedFluxProcessor<T> unicast() {
		return UnicastProcessor.create();
	}

	/**
	 * Create a builder for a "unicast" {@link BalancedFluxProcessor}, which can deal with
	 * backpressure using an internal buffer, but <strong>can have at most one
	 * {@link Subscriber}</strong>.
	 * <p>
	 * This unicast Processor can be fine tuned through its builder, but it requires at
	 * least a {@link Queue}. If said queue is unbounded and if you push any amount of
	 * data through the processor while its {@link Subscriber} has not yet requested data,
	 * it will buffer all of the data. You can avoid that by providing a bounded {@link Queue}
	 * instead.
	 *
	 * @param queue the {@link Queue} to back the processor, making it bounded or unbounded
	 * @param <T>
	 * @return a builder to create a new unicast {@link BalancedFluxProcessor}
	 */
	public static final <T> UnicastProcessorBuilder<T> unicast(Queue<T> queue) {
		return new UnicastProcessorBuilder<>(queue);
	}

	/**
	 * Create a new "emitter" {@link BalancedFluxProcessor}, which is capable
	 * of emitting to several {@link Subscriber Subscribers} while honoring backpressure
	 * for each of its subscribers. It can also subscribe to a {@link Publisher} and relay
	 * its signals synchronously.
	 *
	 * <p>
	 * Initially, when it has no {@link Subscriber}, this emitter Processor can still accept
	 * a few data pushes up to {@link Queues#SMALL_BUFFER_SIZE}.
	 * After that point, if no {@link Subscriber} has come in and consumed the data,
	 * calls to {@link BalancedFluxProcessor#onNext(Object) onNext} block until the processor
	 * is drained (which can only happen concurrently by then).
	 *
	 * <p>
	 * Thus, the first {@link Subscriber} to subscribe receives up to {@code bufferSize}
	 * elements upon subscribing. However, after that, the processor stops replaying signals
	 * to additional subscribers. These subsequent subscribers instead only receive the
	 * signals pushed through the processor after they have subscribed. The internal buffer
	 * is still used for backpressure purposes.
	 *
	 * <p>
	 * The returned emitter Processor is {@code auto-cancelling}, which means that when
	 * all the Processor's subscribers are cancelled (ie. they have all un-subscribed),
	 * it will clear its internal buffer and stop accepting new subscribers. This can be
	 * tuned by creating a processor through the builder method, {@link #emitter(int)}.
	 *
	 * @return a new auto-cancelling emitter {@link BalancedFluxProcessor}
	 */
	public static final <T> BalancedFluxProcessor<T> emitter() {
		return new EmitterProcessorBuilder(-1).build();
	}

	/**
	 * Create a builder for an "emitter" {@link BalancedFluxProcessor}, which is capable
	 * of emitting to several {@link Subscriber Subscribers} while honoring backpressure
	 * for each of its subscribers. It can also subscribe to a {@link Publisher} and relay
	 * its signals synchronously.
	 *
	 * <p>
	 * Initially, when it has no {@link Subscriber}, an emitter Processor can still accept
	 * a few data pushes up to a configurable {@code bufferSize}.
	 * After that point, if no {@link Subscriber} has come in and consumed the data,
	 * calls to {@link BalancedFluxProcessor#onNext(Object) onNext} block until the processor
	 * is drained (which can only happen concurrently by then).
	 *
	 * <p>
	 * Thus, the first {@link Subscriber} to subscribe receives up to {@code bufferSize}
	 * elements upon subscribing. However, after that, the processor stops replaying signals
	 * to additional subscribers. These subsequent subscribers instead only receive the
	 * signals pushed through the processor after they have subscribed. The internal buffer
	 * is still used for backpressure purposes.
	 *
	 * <p>
	 * By default, if all of the emitter Processor's subscribers are cancelled (which
	 * basically means they have all un-subscribed), it will clear its internal buffer and
	 * stop accepting new subscribers. This can be deactivated by using the
	 * {@link EmitterProcessorBuilder#noAutoCancel()} method in the builder.
	 *
	 * @param bufferSize the size of the initial replay buffer (must be positive)
	 * @return a builder to create a new emitter {@link BalancedFluxProcessor}
	 */
	public static final EmitterProcessorBuilder emitter(int bufferSize) {
		if (bufferSize < 0) {
			throw new IllegalArgumentException("bufferSize must be positive");
		}
		return new EmitterProcessorBuilder(bufferSize);
	}

	/**
	 * Create a new unbounded "replay" {@link BalancedFluxProcessor}, which caches
	 * elements that are either pushed directly through its {@link BalancedFluxProcessor#sink() sink}
	 * or elements from an upstream {@link Publisher}, and replays them to late
	 * {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Replay Processors can be created in multiple configurations (this method creates
	 * an unbounded variant):
	 * <ul>
	 *     <li>
	 *         Caching an unbounded history (call to this method, or the {@link #replay(int) size variant}
	 *         of the replay builder with a call to its {@link ReplayProcessorBuilder#unbounded()}
	 *         method).
	 *     </li>
	 *     <li>
	 *         Caching a bounded history ({@link #replay(int)}).
	 *     </li>
	 *     <li>
	 *         Caching time-based replay windows (by only specifying a TTL in the time-oriented
	 *         builder {@link #replay(Duration)}).
	 *     </li>
	 *     <li>
	 *         Caching combination of history size and time window (by using the time-oriented
	 *         builder {@link #replay(Duration)} and configuring a size on it via its
	 *         {@link ReplayTimeProcessorBuilder#historySize(int)} method).
	 *     </li>
	 * </ul>
	 *
	 * @return a builder to create a new replay {@link BalancedFluxProcessor}
	 */
	public static final <T> BalancedFluxProcessor<T> replay() {
		return ReplayProcessor.create();
	}

	/**
	 * Create a builder for a "replay" {@link BalancedFluxProcessor}, which caches
	 * elements that are either pushed directly through its {@link BalancedFluxProcessor#sink() sink}
	 * or elements from an upstream {@link Publisher}, and replays them to late
	 * {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Replay Processors can be created in multiple configurations (this builder allows to
	 * create all time-oriented variants):
	 * <ul>
	 *     <li>
	 *         Caching an unbounded history (call to {@link #replay()}, or the
	 *         {@link #replay(int) size variant} of the replay builder with a call to its
	 *         {@link ReplayProcessorBuilder#unbounded()} method).
	 *     </li>
	 *     <li>
	 *         Caching a bounded history ({@link #replay(int)}).
	 *     </li>
	 *     <li>
	 *         Caching time-based replay windows (by only specifying a TTL in this builder).
	 *     </li>
	 *     <li>
	 *         Caching combination of history size and time window (by using this builder
	 *         to configure a size via the {@link ReplayTimeProcessorBuilder#historySize(int)}
	 *         method).
	 *     </li>
	 * </ul>
	 *
	 * @return a builder to create a new replay {@link BalancedFluxProcessor}
	 */
	public static final ReplayTimeProcessorBuilder replay(Duration maxAge) {
		return new ReplayTimeProcessorBuilder(maxAge);
	}

	/**
	 * Create a builder for a "replay" {@link BalancedFluxProcessor}, which caches
	 * elements that are either pushed directly through its {@link BalancedFluxProcessor#sink() sink}
	 * or elements from an upstream {@link Publisher}, and replays them to late
	 * {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Replay Processors can be created in multiple configurations (this builder exclusively
	 * creates purely size-oriented variants):
	 * <ul>
	 *     <li>
	 *         Caching an unbounded history (call to {@link #replay()} or this builder
	 *         with a call to its {@link ReplayProcessorBuilder#unbounded()} method).
	 *     </li>
	 *     <li>
	 *         Caching a bounded history (this builder).
	 *     </li>
	 *     <li>
	 *         Caching time-based replay windows (by only specifying a TTL in the time-oriented
	 *         builder {@link #replay(Duration)}).
	 *     </li>
	 *     <li>
	 *         Caching combination of history size and time window (by using the time-oriented
	 *         builder {@link #replay(Duration)} and configuring a size on it via its
	 *         {@link ReplayTimeProcessorBuilder#historySize(int)} method).
	 *     </li>
	 * </ul>
	 *
	 * @return a builder to create a new replay {@link BalancedFluxProcessor}
	 */
	public static final ReplayProcessorBuilder replay(int historySize) {
		return new ReplayProcessorBuilder(historySize);
	}

	/**
	 * Create a new "replay" {@link BalancedFluxProcessor} (see {@link #replay()}) that
	 * caches the last element it has pushed, replaying it to late {@link Subscriber subscribers}.
	 *
	 * @param <T>
	 * @return a new replay {@link BalancedFluxProcessor} that caches its last pushed element
	 */
	public static final <T> BalancedFluxProcessor<T> cacheLast() {
		return ReplayProcessor.cacheLast();
	}

	/**
	 * Create a new "replay" {@link BalancedFluxProcessor} (see {@link #replay()}) that
	 * caches the last element it has pushed, replaying it to late {@link Subscriber subscribers}.
	 * If a {@link Subscriber} comes in <strong>before</strong> any value has been pushed,
	 * then the {@code defaultValue} is emitted instead.
	 *
	 * @param <T>
	 * @return a new replay {@link BalancedFluxProcessor} that caches its last pushed element
	 */
	public static final <T> BalancedFluxProcessor<T> cacheLastOrDefault(T defaultValue) {
		return ReplayProcessor.cacheLastOrDefault(defaultValue);
	}

	/**
	 * Create a builder for a "fan out" {@link BalancedFluxProcessor}, which is an
	 * <strong>asynchronous</strong> processor optionally capable of relaying elements from multiple
	 * upstream {@link Publisher Publishers} when created in the shared configuration (see the {@link
	 * FanOutProcessorBuilder#share(boolean)} option of the builder).
	 *
	 * <p>
	 * Note that the share option is mandatory if you intend to concurrently call the Processor's
	 * {@link BalancedFluxProcessor#onNext(Object)}, {@link BalancedFluxProcessor#onComplete()}, or
	 * {@link BalancedFluxProcessor#onError(Throwable)} methods directly or from a concurrent upstream
	 * {@link Publisher}.
	 *
	 * <p>
	 * Otherwise, such concurrent calls are illegal, as the processor is then fully compliant with
	 * the Reactive Streams specification.
	 *
	 * <p>
	 * A fan out processor is capable of fanning out to multiple {@link Subscriber Subscribers},
	 * with the added overhead of establishing resources to keep track of each {@link Subscriber}
	 * until an {@link BalancedFluxProcessor#onError(Throwable)} or {@link
	 * BalancedFluxProcessor#onComplete()} signal is pushed through the processor or until the
	 * associated {@link Subscriber} is cancelled.
	 *
	 * <p>
	 * This variant uses a {@link Thread}-per-{@link Subscriber} model.
	 *
	 * <p>
	 * The maximum number of downstream subscribers for this processor is driven by the {@link
	 * FanOutProcessorBuilder#executor(ExecutorService) executor} builder option. Provide a bounded
	 * {@link ExecutorService} to limit it to a specific number.
	 *
	 * <p>
	 * There is also an {@link FanOutProcessorBuilder#autoCancel(boolean) autoCancel} builder
	 * option: If set to {@code true} (the default), it results in the source {@link Publisher
	 * Publisher(s)} being cancelled when all subscribers are cancelled.
	 *
	 * @return a builder to create a new fan out {@link BalancedFluxProcessor}
	 */
	public static final FanOutProcessorBuilder fanOut() {
		return new TopicProcessor.Builder<>();
	}

	/**
	 * Create a builder for a "fan out" {@link BalancedFluxProcessor} with relaxed
	 * Reactive Streams compliance. This is an <strong>asynchronous</strong> processor
	 * optionally capable of relaying elements from multiple upstream {@link Publisher Publishers}
	 * when created in the shared configuration (see the {@link FanOutProcessorBuilder#share(boolean)}
	 * option of the builder).
	 *
	 * <p>
	 * Note that the share option is mandatory if you intend to concurrently call the Processor's
	 * {@link BalancedFluxProcessor#onNext(Object)}, {@link BalancedFluxProcessor#onComplete()}, or
	 * {@link BalancedFluxProcessor#onError(Throwable)} methods directly or from a concurrent upstream
	 * {@link Publisher}. Otherwise, such concurrent calls are illegal.
	 *
	 * <p>
	 * A fan out processor is capable of fanning out to multiple {@link Subscriber Subscribers},
	 * with the added overhead of establishing resources to keep track of each {@link Subscriber}
	 * until an {@link BalancedFluxProcessor#onError(Throwable)} or {@link
	 * BalancedFluxProcessor#onComplete()} signal is pushed through the processor or until the
	 * associated {@link Subscriber} is cancelled.
	 *
	 * <p>
	 * This variant uses a RingBuffer and doesn't have the overhead of keeping track of
	 * each {@link Subscriber} in its own Thread, so it scales better. As a trade-off,
	 * its compliance with the Reactive Streams specification is slightly
	 * relaxed, and its distribution pattern is to add up requests from all {@link Subscriber Subscribers}
	 * together and to relay signals to only one {@link Subscriber}, picked in a kind of
	 * round-robin fashion.
	 *
	 * <p>
	 * The maximum number of downstream subscribers for this processor is driven by the {@link
	 * FanOutProcessorBuilder#executor(ExecutorService) executor} builder option. Provide a bounded
	 * {@link ExecutorService} to limit it to a specific number.
	 *
	 * <p>
	 * There is also an {@link FanOutProcessorBuilder#autoCancel(boolean) autoCancel} builder
	 * option: If set to {@code true} (the default), it results in the source {@link Publisher
	 * Publisher(s)} being cancelled when all subscribers are cancelled.
	 *
	 * @return a builder to create a new round-robin fan out {@link BalancedFluxProcessor}
	 */
	@Deprecated
	public static final FanOutProcessorBuilder relaxedFanOut() {
		return new WorkQueueProcessor.Builder<>();
	}

	/**
	 * Create a "first" {@link BalancedMonoProcessor}, which will wait for a source
	 * {@link Subscriber#onSubscribe(Subscription)}. It will then propagate only the first
	 * incoming {@link Subscriber#onNext(Object) onNext} signal, and cancel the source
	 * subscription (unless it is a {@link Mono}). The processor will replay that signal
	 * to new {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Manual usage by pushing events through the {@link MonoSink} is naturally limited to at most
	 * one call to {@link MonoSink#success(Object)}.
	 *
	 * @param <T> the type of the processor
	 * @return a new "first" {@link BalancedMonoProcessor} that is detached
	 */
	public static final <T> BalancedMonoProcessor<T> first() {
		return new MonoProcessor<>(null);
	}

	/**
	 * Create a "first" {@link BalancedMonoProcessor}, which will wait for a source
	 * {@link Subscriber#onSubscribe(Subscription)}. It will then propagate only the first
	 * incoming {@link Subscriber#onNext(Object) onNext} signal, and cancel the source
	 * subscription (unless it is a {@link Mono}). The processor will replay that signal
	 * to new {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Manual usage by pushing events through the {@link MonoSink} is naturally limited to at most
	 * one call to {@link MonoSink#success(Object)}.
	 *
	 * @param waitStrategy the {@link WaitStrategy} to use in blocking methods of the processor
	 * @param <T> the type of the processor
	 * @return a new "first" {@link BalancedMonoProcessor} that is detached
	 */
	public static final <T> BalancedMonoProcessor<T> first(WaitStrategy waitStrategy) {
		return new MonoProcessor<>(null, waitStrategy);
	}

	/**
	 * Create a builder for a "first" {@link BalancedMonoProcessor} that is attached to a
	 * {@link Publisher} source, of which it will propagate only the first incoming
	 * {@link Subscriber#onNext(Object) onNext} signal. Once this is done, the processor
	 * cancels the source subscription (unless it is a {@link Mono}). It will then
	 * replay that signal to new {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Manual usage by pushing events through the {@link MonoSink} is naturally limited to at most
	 * one call to {@link MonoSink#success(Object)}.
	 *
	 * @param source the source to attach to
	 * @param <T> the type of the source, which drives the type of the processor
	 * @return a builder to create a new "first" {@link BalancedMonoProcessor} attached to a source
	 */
	public static final <T> MonoFirstProcessorBuilder<T> first(Publisher<? extends T> source) {
		return new MonoFirstProcessorBuilder<>(source);
	}

	//=== BUILDERS to replace factory method only processors ===

	/**
	 * A builder for the {@link #unicast()} flavor of {@link BalancedFluxProcessor}.
	 *
	 * @param <T>
	 */
	public static final class UnicastProcessorBuilder<T> {

		private final Queue<T> queue;
		private Consumer<? super T> onOverflow;
		private Disposable endcallback;

		/**
		 * A unicast Processor created through {@link Processors#unicast()} is unbounded.
		 * This can be changed by using this builder, which also allows further configuration.
		 * <p>
		 * Provide a custom {@link Queue} implementation for the internal buffering.
		 * If that queue is bounded, the processor could reject the push of a value when the
		 * buffer is full and not enough requests from downstream have been received.
		 * <p>
		 * In that bounded case, one can also set a callback to be invoked on each rejected
		 * element, allowing for cleanup of these rejected elements (see {@link #onOverflow(Consumer)}).
		 *
		 * @param q the {@link Queue} to be used for internal buffering
		 * @return the builder
		 */
		UnicastProcessorBuilder(Queue<T> q) {
			this.queue = q;
		}

		/**
		 * Set a callback that will be executed by the Processor on any terminating signal.
		 *
		 * @param e the callback
		 * @return the builder
		 */
		public UnicastProcessorBuilder<T> endCallback(Disposable e) {
			this.endcallback = e;
			return this;
		}

		/**
		 * When a bounded {@link #UnicastProcessorBuilder(Queue) queue} has been provided,
		 * set up a callback to be executed on every element rejected by the {@link Queue}
		 * once it is already full.
		 *
		 * @param c the cleanup consumer for overflowing elements in a bounded queue
		 * @return the builder
		 */
		public UnicastProcessorBuilder<T> onOverflow(Consumer<? super T> c) {
			this.onOverflow = c;
			return this;
		}

		/**
		 * Build the unicast {@link BalancedFluxProcessor} according to the builder's
		 * configuration.
		 *
		 * @return a new unicast {@link BalancedFluxProcessor Processor}
		 */
		public BalancedFluxProcessor<T> build() {
			if (endcallback != null && onOverflow != null) {
				return new UnicastProcessor<>(queue, onOverflow, endcallback);
			}
			else if (endcallback != null) {
				return new UnicastProcessor<>(queue, endcallback);
			}
			else if (onOverflow == null) {
				return new UnicastProcessor<>(queue);
			}
			else {
				return new UnicastProcessor<>(queue, onOverflow);
			}
		}
	}

	/**
	 * A builder for the {@link #emitter()} flavor of {@link BalancedFluxProcessor}.
	 */
	public static final class EmitterProcessorBuilder {

		private final int bufferSize;
		private boolean autoCancel = true;

		/**
		 * Initialize the builder with the replay capacity of the emitter Processor,
		 * which defines how many elements it will retain while it has no current
		 * {@link Subscriber}. Once that size is reached, if there still is no
		 * {@link Subscriber} the emitter processor will block its calls to
		 * {@link Processor#onNext(Object)} until it is drained (which can only happen
		 * concurrently by then).
		 * <p>
		 * The first {@link Subscriber} to subscribe receives all of these elements, and
		 * further subscribers only see new incoming data after that.
		 *
		 * @param bufferSize the size of the initial no-subscriber bounded buffer
		 * @return the builder
		 */
		EmitterProcessorBuilder(int bufferSize) {
			this.bufferSize = bufferSize;
		}

		/**
		 * By default, if all of its {@link Subscriber Subscribers} are cancelled (which
		 * basically means they have all un-subscribed), the emitter Processor will clear
		 * its internal buffer and stop accepting new subscribers. This method changes
		 * that so that the emitter processor keeps on running.
		 *
		 * @return the builder
		 */
		public EmitterProcessorBuilder noAutoCancel() {
			this.autoCancel = false;
			return this;
		}

		/**
		 * Build the emitter {@link BalancedFluxProcessor} according to the builder's
		 * configuration.
		 *
		 * @return a new emitter {@link BalancedFluxProcessor}
		 */
		public <T> BalancedFluxProcessor<T> build() {
			if (bufferSize >= 0 && !autoCancel) {
				return new EmitterProcessor<>(false, bufferSize);
			}
			else if (bufferSize >= 0) {
				return new EmitterProcessor<>(true, bufferSize);
			}
			else if (!autoCancel) {
				return new EmitterProcessor<>(false, Queues.SMALL_BUFFER_SIZE);
			}
			else {
				return new EmitterProcessor<>(true, Queues.SMALL_BUFFER_SIZE);
			}
		}
	}

	/**
	 * A builder for the size-configured {@link #replay()} flavor of {@link BalancedFluxProcessor}.
	 */
	public static final class ReplayProcessorBuilder {

		private final int       size;
		private boolean         unbounded;

		/**
		 * Set the history capacity to a specific bounded size.
		 *
		 * @param size the history buffer capacity
		 * @return the builder, with a bounded capacity
		 */
		public ReplayProcessorBuilder(int size) {
			this.size = size;
			this.unbounded = false;
		}

		/**
		 * Despite the history capacity being set to a specific initial size, mark the
		 * processor as unbounded: the size will be considered as a hint instead of an
		 * hard limit.
		 *
		 * @return the builder, with an unbounded capacity
		 */
		public ReplayProcessorBuilder unbounded() {
			this.unbounded = true;
			return this;
		}

		/**
		 * Build the replay {@link BalancedFluxProcessor} according to the builder's configuration.
		 *
		 * @return a new replay {@link BalancedFluxProcessor}
		 */
		public <T> BalancedFluxProcessor<T> build() {
			if (size < 0) {
				return ReplayProcessor.create();
			}
			if (unbounded) {
				return ReplayProcessor.create(size, true);
			}
			return ReplayProcessor.create(size);
		}
	}

	/**
	 * A builder for the time-oriented {@link #replay()} flavors of {@link BalancedFluxProcessor}.
	 * This can also be used to build a time + size oriented replay processor.
	 */
	public static final class ReplayTimeProcessorBuilder {

		private final Duration  maxAge;
		private int       size = -1;
		private Scheduler scheduler = null;

		public ReplayTimeProcessorBuilder(Duration maxAge) {
			this.maxAge = maxAge;
		}

		/**
		 * Set the history capacity to a specific bounded size.
		 *
		 * @param size the history buffer capacity
		 * @return the builder, with a bounded capacity
		 */
		public ReplayTimeProcessorBuilder historySize(int size) {
			this.size = size;
			return this;
		}

		/**
		 * Set the {@link Scheduler} to use for measuring time.
		 *
		 * @param ttlScheduler the {@link Scheduler} to be used to enforce the TTL
		 * @return the builder
		 */
		public ReplayTimeProcessorBuilder scheduler(Scheduler ttlScheduler) {
			this.scheduler = ttlScheduler;
			return this;
		}

		/**
		 * Build the replay {@link BalancedFluxProcessor} according to the builder's configuration.
		 *
		 * @return a new replay {@link BalancedFluxProcessor}
		 */
		public <T> BalancedFluxProcessor<T> build() {
			//replay size and timeout
			if (size != -1) {
				if (scheduler != null) {
					return ReplayProcessor.createSizeAndTimeout(size, maxAge, scheduler);
				}
				return ReplayProcessor.createSizeAndTimeout(size, maxAge);
			}

			if (scheduler != null) {
				return ReplayProcessor.createTimeout(maxAge, scheduler);
			}
			return ReplayProcessor.createTimeout(maxAge);
		}
	}

	/**
	 * A builder for the {@link #fanOut()} flavor of {@link BalancedFluxProcessor}.
	 */
	public interface FanOutProcessorBuilder {

		/**
		 * Configures name for this builder. Name is reset to default if the provided
		 * <code>name</code> is null.
		 *
		 * @param name Use a new cached ExecutorService and assign this name to the created threads
		 *             if {@link #executor(ExecutorService)} is not configured.
		 * @return builder with provided name
		 */
		FanOutProcessorBuilder name(@Nullable String name);

		/**
		 * Configures buffer size for this builder. Default value is {@link Queues#SMALL_BUFFER_SIZE}.
		 * @param bufferSize the internal buffer size to hold signals, must be a power of 2
		 * @return builder with provided buffer size
		 */
		FanOutProcessorBuilder bufferSize(int bufferSize);

		/**
		 * Configures wait strategy for this builder. Default value is {@link WaitStrategy#liteBlocking()}.
		 * Wait strategy is push to default if the provided <code>waitStrategy</code> is null.
		 * @param waitStrategy A WaitStrategy to use instead of the default smart blocking wait strategy.
		 * @return builder with provided wait strategy
		 */
		FanOutProcessorBuilder waitStrategy(@Nullable WaitStrategy waitStrategy);

		/**
		 * Configures auto-cancel for this builder. Default value is true.
		 * @param autoCancel automatically cancel
		 * @return builder with provided auto-cancel
		 */
		FanOutProcessorBuilder autoCancel(boolean autoCancel);

		/**
		 * Configures an {@link ExecutorService} to execute as many event-loop consuming the
		 * ringbuffer as subscribers. Name configured using {@link #name(String)} will be ignored
		 * if executor is push.
		 * @param executor A provided ExecutorService to manage threading infrastructure
		 * @return builder with provided executor
		 */
		FanOutProcessorBuilder executor(@Nullable ExecutorService executor);

		/**
		 * Configures an additional {@link ExecutorService} that is used internally
		 * on each subscription.
		 * @param requestTaskExecutor internal request executor
		 * @return builder with provided internal request executor
		 */
		FanOutProcessorBuilder requestTaskExecutor(
				@Nullable ExecutorService requestTaskExecutor);

		/**
		 * Configures sharing state for this builder. A shared Processor authorizes
		 * concurrent onNext calls and is suited for multi-threaded publisher that
		 * will fan-in data.
		 * @param share true to support concurrent onNext calls
		 * @return builder with specified sharing
		 */
		FanOutProcessorBuilder share(boolean share);

		/**
		 * Creates a new fanout {@link BalancedFluxProcessor} according to the builder's
		 * configuration.
		 *
		 * @return a new fanout {@link BalancedFluxProcessor}
		 */
		<T> BalancedFluxProcessor<T> build();
	}

	/**
	 * A builder for the {@link #first()} flavor of {@link BalancedMonoProcessor},
	 * directly attached to a {@link Publisher} source.
	 *
	 * @param <T>
	 */
	public static final class MonoFirstProcessorBuilder<T> {

		private final Publisher<? extends T> source;

		public MonoFirstProcessorBuilder(Publisher<? extends T> source) {
			this.source = source;
		}

		/**
		 * Create the processor with a {@link WaitStrategy} and attach it to the source.
		 *
		 * @param waitStrategy the {@link WaitStrategy} to use for blocking methods of the processor
		 * @return a new "first" {@link BalancedMonoProcessor} with a wait strategy, that
		 * is attached to a source
		 */
		public BalancedMonoProcessor<T> withWaitStrategy(WaitStrategy waitStrategy) {
			return new MonoProcessor<>(this.source, waitStrategy);
		}

		/**
		 * Create the {@link BalancedMonoProcessor} by immediately attaching it to the
		 * {@link Publisher} source.
		 *
		 * @return a new first {@link BalancedMonoProcessor} that is attached to a source
		 */
		public BalancedMonoProcessor<T> build() {
			return new MonoProcessor<>(source);
		}
	}
}
