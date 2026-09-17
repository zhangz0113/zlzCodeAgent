package com.zlzcode.codeagent.session.service;

import com.zlzcode.codeagent.session.exception.SessionException;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.session.model.SessionRunSnapshot;
import com.zlzcode.codeagent.session.store.SessionStore;
import com.zlzcode.codeagent.workspace.service.WorkspaceRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public final class SessionService {

    private final SessionStore sessionStore;
    private final WorkspaceRegistry workspaceRegistry;
    private final Clock clock;

    @Autowired
    public SessionService(SessionStore sessionStore, WorkspaceRegistry workspaceRegistry) {
        this(sessionStore, workspaceRegistry, Clock.systemUTC());
    }

    SessionService(SessionStore sessionStore, WorkspaceRegistry workspaceRegistry, Clock clock) {
        this.sessionStore = sessionStore;
        this.workspaceRegistry = workspaceRegistry;
        this.clock = clock;
    }

    public Session create(String workspaceId) {
        workspaceRegistry.resolve(workspaceId);
        Instant now = clock.instant();
        Session session = new Session(
                "session-" + UUID.randomUUID(), workspaceId, now, now, List.of());
        sessionStore.create(session);
        return session;
    }

    public Session read(String sessionId) {
        return sessionStore.read(sessionId);
    }

    public SessionRunSnapshot refreshRun(String sessionId, String runId, String prompt) {
        Session current = sessionStore.read(sessionId);
        return new SessionRunSnapshot(
                sessionId, runId, current.workspaceId(), prompt,
                current.turns().stream()
                        .filter(turn -> turn.state() == Session.TurnState.COMPLETED)
                        .toList());
    }

    /*
     * 背景：用户提交的 Prompt 必须在模型调用前可靠保存，但失败 Run 不能污染后续模型历史。
     * 设计意图：先写入只有 user 的 incomplete Turn，成功后再原子补齐 Assistant；不提前伪造完成消息对。
     * 关键约束：只有 completed Turn 能进入 completedHistory；同一 runId 不能重复追加，否则重试会复制用户输入。
     */
    public SessionRunSnapshot beginRun(String sessionId, String runId, String prompt) {
        Session current = sessionStore.read(sessionId);
        if (current.turns().stream().anyMatch(turn -> turn.runId().equals(runId))) {
            throw SessionException.runAlreadyExists();
        }
        Instant now = clock.instant();
        List<Session.Turn> updatedTurns = new ArrayList<>(current.turns());
        updatedTurns.add(new Session.Turn(
                runId,
                Session.TurnState.INCOMPLETE,
                now,
                new Session.UserMessage(prompt),
                null));
        Session updated = new Session(
                current.sessionId(), current.workspaceId(), current.createdAt(), now, updatedTurns);
        sessionStore.save(updated);
        List<Session.Turn> completedHistory = current.turns().stream()
                .filter(turn -> turn.state() == Session.TurnState.COMPLETED)
                .toList();
        return new SessionRunSnapshot(
                sessionId, runId, current.workspaceId(), prompt, completedHistory);
    }

    public void completeRun(
            SessionRunSnapshot run,
            Session.AssistantMessage assistant) {
        Session current = sessionStore.read(run.sessionId());
        List<Session.Turn> updatedTurns = new ArrayList<>(current.turns().size());
        boolean found = false;
        for (Session.Turn turn : current.turns()) {
            if (!turn.runId().equals(run.runId())) {
                updatedTurns.add(turn);
                continue;
            }
            if (turn.state() != Session.TurnState.INCOMPLETE
                    || !turn.user().content().equals(run.prompt())) {
                throw SessionException.runAlreadyExists();
            }
            updatedTurns.add(new Session.Turn(
                    turn.runId(),
                    Session.TurnState.COMPLETED,
                    turn.createdAt(),
                    turn.user(),
                    assistant));
            found = true;
        }
        if (!found) {
            throw SessionException.runNotFound();
        }
        Instant now = clock.instant();
        sessionStore.save(new Session(
                current.sessionId(), current.workspaceId(), current.createdAt(), now, updatedTurns));
    }

    /*
     * 背景：Run 失败前可能已经读取证据或执行工具，这些已发生事实不能因最终状态失败而全部消失。
     * 设计意图：把当前可用的语义记录附着到原 INCOMPLETE Turn，同时继续由 RunStore 独立保存 FAILED 生命周期状态。
     * 关键约束：不能把 Turn 标记为 COMPLETED，也不能覆盖已经成功收口的 Turn；否则失败探索会进入后续模型历史，或 RunStore 写入故障会破坏已完成会话。
     */
    public void recordIncompleteRun(
            String sessionId,
            String runId,
            Session.AssistantMessage assistant) {
        Session current = sessionStore.read(sessionId);
        List<Session.Turn> updatedTurns = new ArrayList<>(current.turns().size());
        boolean found = false;
        boolean changed = false;
        for (Session.Turn turn : current.turns()) {
            if (!turn.runId().equals(runId)) {
                updatedTurns.add(turn);
                continue;
            }
            found = true;
            if (turn.state() == Session.TurnState.COMPLETED) {
                updatedTurns.add(turn);
                continue;
            }
            updatedTurns.add(new Session.Turn(
                    turn.runId(), turn.state(), turn.createdAt(), turn.user(), assistant));
            changed = true;
        }
        if (!found) {
            throw SessionException.runNotFound();
        }
        if (!changed) {
            return;
        }
        Instant now = clock.instant();
        sessionStore.save(new Session(
                current.sessionId(), current.workspaceId(), current.createdAt(), now, updatedTurns));
    }

}
