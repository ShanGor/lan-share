package io.github.shangor.lan.transfer.core.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TaskIdGenerator {
    private static final Set<Integer> IN_USE = ConcurrentHashMap.newKeySet();

    private TaskIdGenerator() {
    }

    static int nextId() {
        while (true) {
            int candidate = (int) (System.currentTimeMillis() & 0xFFFF);
            if (IN_USE.add(candidate)) {
                return candidate;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return candidate;
            }
        }
    }

    static void release(int id) {
        IN_USE.remove(id);
    }
}
