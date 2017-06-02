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

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;
import javax.annotation.Nullable;

/**
 * Uses a resource, generated by a supplier for each individual Subscriber,
 * while streaming the values from a
 * Publisher derived from the same resource and makes sure the resource is released
 * if the sequence terminates or the Subscriber cancels.
 * <p>
 * <p>
 * Eager resource cleanup happens just before the source termination and exceptions
 * raised by the cleanup Consumer may override the terminal even. Non-eager
 * cleanup will drop any exception.
 *
 * @param <T> the value type streamed
 * @param <S> the resource type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxUsing<T, S> extends Flux<T> implements Fuseable {

	final Callable<S> resourceSupplier;

	final Function<? super S, ? extends Publisher<? extends T>> sourceFactory;

	final Consumer<? super S> resourceCleanup;

	final boolean eager;

	FluxUsing(Callable<S> resourceSupplier,
			Function<? super S, ? extends Publisher<? extends T>> sourceFactory,
			Consumer<? super S> resourceCleanup,
			boolean eager) {
		this.resourceSupplier =
				Objects.requireNonNull(resourceSupplier, "resourceSupplier");
		this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
		this.resourceCleanup = Objects.requireNonNull(resourceCleanup, "resourceCleanup");
		this.eager = eager;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(Subscriber<? super T> s) {
		S resource;

		try {
			resource = resourceSupplier.call();
		}
		catch (Throwable e) {
			Operators.error(s, Operators.onOperatorError(e));
			return;
		}

		Publisher<? extends T> p;

		try {
			p = Objects.requireNonNull(sourceFactory.apply(resource),
					"The sourceFactory returned a null value");
		}
		catch (Throwable e) {

			try {
				resourceCleanup.accept(resource);
			}
			catch (Throwable ex) {
				ex.addSuppressed(Operators.onOperatorError(e));
				e = ex;
			}

			Operators.error(s, Operators.onOperatorError(e));
			return;
		}

		if (p instanceof Fuseable) {
			p.subscribe(new UsingFuseableSubscriber<>(s,
					resourceCleanup,
					resource,
					eager));
		}
		else if (s instanceof ConditionalSubscriber) {
			p.subscribe(new UsingConditionalSubscriber<>((ConditionalSubscriber<? super T>) s,
					resourceCleanup,
					resource,
					eager));
		}
		else {
			p.subscribe(new UsingSubscriber<>(s, resourceCleanup,
					resource, eager));
		}
	}

	static final class UsingSubscriber<T, S>
			implements InnerOperator<T, T>, QueueSubscription<T> {

		final Subscriber<? super T> actual;

		final Consumer<? super S> resourceCleanup;

		final S resource;

		final boolean eager;

		Subscription s;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<UsingSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(UsingSubscriber.class, "wip");

		UsingSubscriber(Subscriber<? super T> actual,
				Consumer<? super S> resourceCleanup,
				S resource,
				boolean eager) {
			this.actual = actual;
			this.resourceCleanup = resourceCleanup;
			this.resource = resource;
			this.eager = eager;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == BooleanAttr.TERMINATED || key == BooleanAttr.CANCELLED) return wip == 1;
			if (key == ScannableAttr.PARENT) return s;

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public Subscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			if (WIP.compareAndSet(this, 0, 1)) {
				s.cancel();

				cleanup();
			}
		}

		void cleanup() {
			try {
				resourceCleanup.accept(resource);
			}
			catch (Throwable e) {
				Operators.onErrorDropped(e);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				}
				catch (Throwable e) {
					Throwable _e = Operators.onOperatorError(e);
					if (_e != t) {
						_e.addSuppressed(t);
					}
					t = _e;
				}
			}

			actual.onError(t);

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				}
				catch (Throwable e) {
					actual.onError(Operators.onOperatorError(e));
					return;
				}
			}

			actual.onComplete();

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public int requestFusion(int requestedMode) {
			return NONE; // always reject, upstream turned out to be non-fuseable after all
		}

		@Override
		public void clear() {
			// ignoring fusion methods
		}

		@Override
		public boolean isEmpty() {
			// ignoring fusion methods
			return true;
		}

		@Override
		@Nullable
		public T poll() {
			return null;
		}

		@Override
		public int size() {
			return 0;
		}
	}

	static final class UsingFuseableSubscriber<T, S>
			implements InnerOperator<T, T>, QueueSubscription<T> {

		final Subscriber<? super T> actual;

		final Consumer<? super S> resourceCleanup;

		final S resource;

		final boolean eager;

		QueueSubscription<T> s;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<UsingFuseableSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(UsingFuseableSubscriber.class,
						"wip");

		int mode;

		UsingFuseableSubscriber(Subscriber<? super T> actual,
				Consumer<? super S> resourceCleanup,
				S resource,
				boolean eager) {
			this.actual = actual;
			this.resourceCleanup = resourceCleanup;
			this.resource = resource;
			this.eager = eager;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == BooleanAttr.TERMINATED || key == BooleanAttr.CANCELLED)
				return wip == 1;
			if (key == ScannableAttr.PARENT) return s;

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public Subscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			if (WIP.compareAndSet(this, 0, 1)) {
				s.cancel();

				cleanup();
			}
		}

		void cleanup() {
			try {
				resourceCleanup.accept(resource);
			}
			catch (Throwable e) {
				Operators.onErrorDropped(e);
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = (QueueSubscription<T>) s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				}
				catch (Throwable e) {
					Throwable _e = Operators.onOperatorError(e);
					if (_e != t) {
						_e.addSuppressed(t);
					}
					t = _e;
				}
			}

			actual.onError(t);

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				}
				catch (Throwable e) {
					actual.onError(Operators.onOperatorError(e));
					return;
				}
			}

			actual.onComplete();

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void clear() {
			s.clear();
		}

		@Override
		public boolean isEmpty() {
			return s.isEmpty();
		}

		@Override
		@Nullable
		public T poll() {
			T v = s.poll();

			if (v == null && mode == SYNC) {
				if (WIP.compareAndSet(this, 0, 1)) {
					resourceCleanup.accept(resource);
				}
			}
			return v;
		}

		@Override
		public int requestFusion(int requestedMode) {
			int m = s.requestFusion(requestedMode);
			mode = m;
			return m;
		}

		@Override
		public int size() {
			return s.size();
		}
	}

	static final class UsingConditionalSubscriber<T, S>
			implements ConditionalSubscriber<T>, InnerOperator<T, T>,
			           QueueSubscription<T> {

		final ConditionalSubscriber<? super T> actual;

		final Consumer<? super S> resourceCleanup;

		final S resource;

		final boolean eager;

		Subscription s;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<UsingConditionalSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(UsingConditionalSubscriber.class,
						"wip");

		UsingConditionalSubscriber(ConditionalSubscriber<? super T> actual,
				Consumer<? super S> resourceCleanup,
				S resource,
				boolean eager) {
			this.actual = actual;
			this.resourceCleanup = resourceCleanup;
			this.resource = resource;
			this.eager = eager;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == BooleanAttr.TERMINATED || key == BooleanAttr.CANCELLED)
				return wip == 1;
			if (key == ScannableAttr.PARENT) return s;

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public Subscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			if (WIP.compareAndSet(this, 0, 1)) {
				s.cancel();

				cleanup();
			}
		}

		void cleanup() {
			try {
				resourceCleanup.accept(resource);
			}
			catch (Throwable e) {
				Operators.onErrorDropped(e);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public boolean tryOnNext(T t) {
			return actual.tryOnNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				}
				catch (Throwable e) {
					Throwable _e = Operators.onOperatorError(e);
					if (_e != t) {
						_e.addSuppressed(t);
					}
					t = _e;
				}
			}

			actual.onError(t);

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (eager && WIP.compareAndSet(this, 0, 1)) {
				try {
					resourceCleanup.accept(resource);
				}
				catch (Throwable e) {
					actual.onError(Operators.onOperatorError(e));
					return;
				}
			}

			actual.onComplete();

			if (!eager && WIP.compareAndSet(this, 0, 1)) {
				cleanup();
			}
		}

		@Override
		public int requestFusion(int requestedMode) {
			return NONE; // always reject, upstream turned out to be non-fuseable after all
		}

		@Override
		public void clear() {
			// ignoring fusion methods
		}

		@Override
		public boolean isEmpty() {
			// ignoring fusion methods
			return true;
		}

		@Override
		@Nullable
		public T poll() {
			return null;
		}

		@Override
		public int size() {
			return 0;
		}
	}
}
