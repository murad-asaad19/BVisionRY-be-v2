package com.bvisionry.e2e;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * FIFO queue of scripted responses for {@link FakeChatModel}. A test enqueues
 * the body it wants the next AI call to return; the model pops from this queue
 * before falling back to its schema-aware default. Process-wide state — fine
 * for E2E because the suite runs against a dedicated backend and reset
 * happens between runs, not specs.
 */
public class FakeChatResponseRegistry {

    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(String response) {
        queue.add(response);
    }

    public String pollNext() {
        return queue.poll();
    }

    public void clear() {
        queue.clear();
    }
}
