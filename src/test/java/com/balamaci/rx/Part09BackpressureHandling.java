package com.balamaci.rx;

import com.balamaci.rx.util.Helpers;
import io.reactivex.rxjava3.core.BackpressureOverflowStrategy;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Backpressure is related to preventing overloading the subscriber with too many events.
 * It can be the case of a slow consumer that cannot keep up with the producer.
 * Backpressure relates to a feedback mechanism through which the subscriber can signal
 * to the producer how much data it can consume.
 *
 * However the producer must be 'backpressure-aware' in order to know how to throttle back.
 *
 * If the producer is not 'backpressure-aware', in order to prevent an OutOfMemory due to an unbounded increase of events,
 * we still can define a BackpressureStrategy to specify how we should deal with piling events.
 * If we should buffer(BackpressureStrategy.BUFFER) or drop(BackpressureStrategy.DROP, BackpressureStrategy.LATEST)
 * incoming events.
 *
 * @author sbalamaci
 */
public class Part09BackpressureHandling implements BaseTestObservables {



    @Test
    public void customBackpressureAwareFlux() {
        Flowable<Integer> flux = new CustomRangeFlowable(5, 10);

        flux.subscribe(new Subscriber<Integer>() {

            private Subscription subscription;
            private int backlogItems;

            private final int BATCH = 2;
            private final int INITIAL_REQ = 5;

            @Override
            public void onSubscribe(Subscription subscription) {
                this.subscription = subscription;
                backlogItems = INITIAL_REQ;

                log.info("Initial request {}", backlogItems);
                subscription.request(backlogItems);
            }

            @Override
            public void onNext(Integer val) {
                log.info("Subscriber received {}", val);
                backlogItems--;

                if (backlogItems == 0) {
                    backlogItems = BATCH;
                    subscription.request(BATCH);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.info("Subscriber encountered error");
            }

            @Override
            public void onComplete() {
                log.info("Subscriber completed");
            }
        });
    }

    /**
     * We use BackpressureStrategy.DROP in create() to handle events that are emitted outside
     * the request amount from the downstream subscriber.
     *
     * We see that events that reach the subscriber are those only 3 requested by the
     * observeOn operator, the events produced outside of the requested amount
     * are discarded (BackpressureStrategy.DROP).
     *
     * observeOn has a default request size from upstream of 128(system parameter {@code rx2.buffer-size})
     *
     */
    @Test
    public void createFlowableWithBackpressureStrategy() {
        BackpressureStrategy backpressureStrategy =
                BackpressureStrategy.DROP
//              BackpressureStrategy.BUFFER
//              BackpressureStrategy.LATEST
//              BackpressureStrategy.ERROR
                ;

        Flowable<Integer> flowable = createFlowable(5, backpressureStrategy);

        //we need to switch threads to not run the producer in the same thread as the subscriber(which waits some time
        // to simulate a slow subscriber)
        flowable = flowable
                .observeOn(Schedulers.io(), false, 3);

        subscribeWithSlowSubscriberAndWait(flowable);
    }


    /**
     * There are operators for specifying backpressure strategy anywhere in the operators chain,
     * not just at Flowable.create().
     * - onBackpressureBuffer
     * - onBackpressureDrop
     * - onBackpressureLatest
     * These operators request from upstream the Long.MAX_VALUE(unbounded amount) and then they buffer(onBackpressureBuffer)
     * the events for downstream and send the events as requested.
     *
     * In the example we specify a buffering strategy in the example, however since the buffer is not very large,
     * we still get an exception after the 8th value - 3(requested) + 5(buffer)
     *
     * We create the Flowable with BackpressureStrategy.MISSING saying we don't care about backpressure
     * but let one of the onBackpressureXXX operators handle it.
     *
     */
    @Test
    public void bufferingBackpressureOperator() {
        Flowable<Integer> flowable = createFlowable(10, BackpressureStrategy.MISSING)
                .onBackpressureBuffer(5, () -> log.info("Buffer has overflown"));

        flowable = flowable
                .observeOn(Schedulers.io(), false, 3);

        subscribeWithSlowSubscriberAndWait(flowable);
    }

    /**
     * We can opt for a variant of the onBackpressureBuffer, to drop events that do not fit
     * inside the buffer
     */
    @Test
    public void bufferingThenDroppingEvents() {
        Flowable<Integer> flowable = createFlowable(10, BackpressureStrategy.MISSING)
                .onBackpressureBuffer(5, () -> log.info("Buffer has overflown"),
                        BackpressureOverflowStrategy.DROP_OLDEST);

        //we need to switch threads to not run the producer in the same thread as the subscriber(which waits some time
        // to simulate a slow subscriber)
        flowable = flowable
                .observeOn(Schedulers.io(), false, 3);

        subscribeWithSlowSubscriberAndWait(flowable);
    }


    /**
     * Not only a slow subscriber triggers backpressure, but also a slow operator
     * that slows down the handling of events and new request calls for new items
     */
    @Test
    public void throwingBackpressureNotSupportedSlowOperator() {
        Flowable<String> flowable = createFlowable(10, BackpressureStrategy.MISSING)
                .onBackpressureDrop((val) -> log.info("Dropping {}", val))
                .observeOn(Schedulers.io(), false, 3)
                .map(val -> {
                    Helpers.sleepMillis(50);
                    return "*" + val + "*";
                });

        subscribeWithLogOutputWaitingForComplete(flowable); //notice it's not the slowSubscribe method used
    }

    /**
     * Backpressure operators can be added whenever necessary and it's not limited to
     * cold publishers and we can use them on hot publishers also
     */
    @Test
    public void backpressureWithHotPublisher() {
        CountDownLatch latch = new CountDownLatch(1);

        PublishSubject<Integer> subject = PublishSubject.create();

        Flowable<Integer> flowable = subject
                .toFlowable(BackpressureStrategy.MISSING)
                .onBackpressureDrop(val -> log.info("Dropped {}", val));

        flowable = flowable.observeOn(Schedulers.io(), false, 3);

        subscribeWithSlowSubscriber(flowable, latch);

        for (int i = 1; i <= 10; i++) {
            log.info("Emitting {}", i);
            subject.onNext(i);
        }
        subject.onComplete();

        Helpers.wait(latch);
    }

    /**
     * Chaining together multiple onBackpressureXXX operators doesn't actually make sense
     * Using
     *                 .onBackpressureBuffer(5)
     *                 .onBackpressureDrop((val) -> log.info("Dropping {}", val))
     * is not behaving as maybe expected - buffer 5 values, and then dropping overflowing events-.
     *
     * Because onBackpressureDrop subscribes to the previous onBackpressureBuffer operator
     * signaling its requesting Long.MAX_VALUE(unbounded amount) from it, the onBackpressureBuffer will never feel
     * its subscriber is overwhelmed and never "trigger" meaning that the last onBackpressureXXX operator overrides
     * the previous one.
     *
     * Of course for implementing an event dropping strategy after a full buffer, there is the special overrided
     * version of onBackpressureBuffer that takes a BackpressureOverflowStrategy.
     */
    @Test
    public void cascadingOnBackpressureXXXOperators() {
        Flowable<Integer> flowable = createFlowable(10, BackpressureStrategy.MISSING)
                .onBackpressureBuffer(5)
                .onBackpressureDrop((val) -> log.info("Dropping {}", val))
                .observeOn(Schedulers.io(), false, 3);

        subscribeWithSlowSubscriberAndWait(flowable);
    }


    /**
     * Zipping a slow stream with a faster one also can cause a backpressure problem
     */
    @Test
    public void zipOperatorHasALimit() {
        Flowable<Integer> fast = createFlowable(200, BackpressureStrategy.MISSING);
        Flowable<Long> slowStream = Flowable.interval(100, TimeUnit.MILLISECONDS);

        Flowable<String> observable = Flowable.zip(fast, slowStream,
                (val1, val2) -> val1 + " " + val2);

        subscribeWithSlowSubscriberAndWait(observable);
    }

    @Test
    public void backpressureAwareObservable() {
        Flowable<Integer> flowable = Flowable.range(0, 10);

        flowable = flowable
                .observeOn(Schedulers.io(), false, 3);

        subscribeWithSlowSubscriberAndWait(flowable);
    }


    private Flowable<Integer> createFlowable(int items,
                                    BackpressureStrategy backpressureStrategy) {
        return Flowable.<Integer>create(subscriber -> {
            log.info("Started emitting");

            for (int i = 0; i < items; i++) {
                if(subscriber.isCancelled()) {
                    return;
                }

                log.info("Emitting {}", i);
                subscriber.onNext(i);
            }

            subscriber.onComplete();
        }, backpressureStrategy);
    }


    private <T> void subscribeWithSlowSubscriberAndWait(Flowable<T> flowable) {
        CountDownLatch latch = new CountDownLatch(1);
        flowable.subscribe(logNextAndSlowByMillis(50), logError(latch), logComplete(latch));

        Helpers.wait(latch);
    }

    private <T> void subscribeWithSlowSubscriber(Flowable<T> flowable, CountDownLatch latch) {
        flowable.subscribe(logNextAndSlowByMillis(50), logError(latch), logComplete(latch));
    }

    private class CustomRangeFlowable extends Flowable<Integer> {

        private int startFrom;
        private int count;

        CustomRangeFlowable(int startFrom, int count) {
            this.startFrom = startFrom;
            this.count = count;
        }

        @Override
        public void subscribeActual(Subscriber<? super Integer> subscriber) {
            subscriber.onSubscribe(new CustomRangeSubscription(startFrom, count, subscriber));
        }

        class CustomRangeSubscription implements Subscription {

            volatile boolean cancelled;
            boolean completed = false;
            private int count;
            private int currentCount;
            private int startFrom;

            private Subscriber<? super Integer> actualSubscriber;

            CustomRangeSubscription(int startFrom, int count, Subscriber<? super Integer> actualSubscriber) {
                this.count = count;
                this.startFrom = startFrom;
                this.actualSubscriber = actualSubscriber;
            }

            @Override
            public void request(long items) {
                log.info("Downstream requests {} items", items);
                for(int i=0; i < items; i++) {
                    if(cancelled || completed) {
                        return;
                    }

                    if(currentCount == count) {
                        completed = true;
                        if(cancelled) {
                            return;
                        }

                        actualSubscriber.onComplete();
                        return;
                    }

                    int emitVal = startFrom + currentCount;
                    currentCount++;
                    actualSubscriber.onNext(emitVal);
                }
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }
    }


}
