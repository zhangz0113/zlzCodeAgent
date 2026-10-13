package com.zlzcode.codeagent.retrieval.service;

import com.zlzcode.codeagent.retrieval.exception.RetrievalException;
import com.zlzcode.codeagent.retrieval.model.RetrievalIndex.*;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class WorkspaceIndexCoordinator {
    private final ConcurrentHashMap<String, Lease> active = new ConcurrentHashMap<>();
    public Lease acquire(String identity, Operation operation, Duration budget) {
        Lease lease = new Lease(identity, operation, budget);
        if (active.putIfAbsent(identity, lease) != null)
            throw new RetrievalException(RetrievalException.Code.BUSY, "当前工作区索引正在操作，请稍后重试");
        return lease;
    }
    public Lease active(String identity) { return active.get(identity); }

    public final class Lease implements AutoCloseable {
        private final String identity;
        private final long deadline;
        private final Operation operation;
        private volatile Stage stage = Stage.IDLE;
        private volatile int processed;
        private volatile Integer total;
        private Lease(String identity, Operation operation, Duration budget) {
            this.identity = identity; this.operation = operation;
            deadline = System.nanoTime() + budget.toNanos();
        }
        public Operation operation() { return operation; }
        public Stage stage() { return stage; }
        public int processed() { return processed; }
        public Integer total() { return total; }
        public void progress(Stage value, int done, Integer count) {
            check(); stage = value; processed = done; total = count;
        }
        public void stage(Stage value) { check(); stage = value; }
        public void check() {
            if (System.nanoTime() >= deadline)
                throw new RetrievalException(RetrievalException.Code.TIMEOUT, "检索操作超时，请先准备索引再重试");
        }
        public Duration remaining(Duration maximum) {
            check();
            return Duration.ofNanos(Math.min(maximum.toNanos(), deadline - System.nanoTime()));
        }
        /*
         * 背景：HTTP 取消或超时不代表后台文件覆盖已经退出。
         * 设计意图：执行权只由实际工作 finally 释放，不绑到订阅 doFinally。
         * 关键约束：响应结束不能提前删 active；否则下一次同步会与未退出的写入交错。
         */
        @Override public void close() { active.remove(identity, this); }
    }
}
