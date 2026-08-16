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
    private volatile String lastUserMessage;

    public void enqueue(String response) {
        queue.add(response);
    }

    public String pollNext() {
        return queue.poll();
    }

    /**
     * The user message of the most recent call — how a spec asserts what was
     * PUT IN FRONT of the model, not only what came back. The shift-narrative
     * job builds its prompt from four SQL reads (spec §2's activity section),
     * and a pure-function test of the assembler would pass happily while the
     * queries returned nothing.
     */
    public void recordUserMessage(String text) {
        this.lastUserMessage = text;
    }

    public String lastUserMessage() {
        return lastUserMessage;
    }

    public void clear() {
        queue.clear();
        lastUserMessage = null;
    }
}
