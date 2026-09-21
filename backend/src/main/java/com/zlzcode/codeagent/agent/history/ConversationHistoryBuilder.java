package com.zlzcode.codeagent.agent.history;

import com.zlzcode.codeagent.agent.model.LlmMessage;
import com.zlzcode.codeagent.agent.model.LlmToolCall;
import com.zlzcode.codeagent.session.model.Session;
import com.zlzcode.codeagent.task.model.RunIntervention;
import com.zlzcode.codeagent.validation.RequestContractException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class ConversationHistoryBuilder {

    public List<LlmMessage> projectTurns(
            List<Session.Turn> completedTurns,
            String callIdPrefix) {
        if (callIdPrefix == null || callIdPrefix.isBlank()) {
            throw invalid("历史工具调用前缀无效");
        }
        List<Session.Turn> history = completedTurns == null
                ? List.of()
                : List.copyOf(completedTurns);
        List<LlmMessage> messages = new ArrayList<>();

        for (int turnIndex = 0; turnIndex < history.size(); turnIndex++) {
            Session.Turn turn = history.get(turnIndex);
            if (turn == null
                    || turn.state() != Session.TurnState.COMPLETED
                    || turn.user() == null
                    || turn.assistant() == null) {
                throw invalid("历史 Turn 必须完整完成");
            }
            messages.add(new LlmMessage.TextMessage(
                    LlmMessage.MessageRole.USER, turn.user().content()));
            List<RunIntervention> interventions = turn.assistant().interventions();
            int interventionIndex = appendInterventionsAtRound(
                    messages, interventions, 0, 0);
            /*
             * 背景：Session 保存供应商无关的工具批次，后续模型请求仍要求合法的 assistant tool_calls -> tool 序列。
             * 设计意图：按原批次和顺序重建消息，并为本次请求生成临时关联 ID；不持久化供应商原始 ID。
             * 关键约束：同批调用必须位于一条 AssistantToolCalls 消息中，且每个结果严格按调用顺序追加；拆批或重排会改变模型看到的协议语义。
             */
            for (int roundIndex = 0;
                    roundIndex < turn.assistant().toolRounds().size();
                    roundIndex++) {
                Session.ToolRound round = turn.assistant().toolRounds().get(roundIndex);
                List<LlmToolCall> calls = new ArrayList<>(round.calls().size());
                for (int callIndex = 0; callIndex < round.calls().size(); callIndex++) {
                    Session.ToolCall storedCall = round.calls().get(callIndex);
                    String callId = callIdPrefix + "_" + turnIndex + "_"
                            + roundIndex + "_" + callIndex;
                    calls.add(new LlmToolCall(
                            callId, storedCall.name(), storedCall.arguments()));
                }
                messages.add(new LlmMessage.AssistantToolCallsMessage(
                        round.assistantContent(), null, calls));
                for (int callIndex = 0; callIndex < round.calls().size(); callIndex++) {
                    Session.ToolCall storedCall = round.calls().get(callIndex);
                    String callId = callIdPrefix + "_" + turnIndex + "_"
                            + roundIndex + "_" + callIndex;
                    messages.add(new LlmMessage.ToolResultMessage(
                            callId, storedCall.result().modelContent()));
                }
                interventionIndex = appendInterventionsAtRound(
                        messages,
                        interventions,
                        interventionIndex,
                        roundIndex + 1);
            }
            if (interventionIndex != interventions.size()) {
                throw invalid("历史介入位置超出工具轮次");
            }
            messages.add(new LlmMessage.TextMessage(
                    LlmMessage.MessageRole.ASSISTANT, turn.assistant().content()));
        }

        return messages;
    }

    /*
     * 背景：Run Intervention 是用户在一个 Turn 内追加的目标约束，必须保留其相对于完整工具轮次的原始位置。
     * 设计意图：Session 只保存 afterToolRound 索引，投影时在对应工具结果批次之后恢复普通 user message。
     * 关键约束：不能统一挪到 Turn 开头或结尾；否则后续模型会误以为约束在更早或更晚时已经生效，改变任务语义。
     */
    private int appendInterventionsAtRound(
            List<LlmMessage> messages,
            List<RunIntervention> interventions,
            int startIndex,
            int toolRound) {
        int index = startIndex;
        while (index < interventions.size()
                && interventions.get(index).appliedAfterToolRound() == toolRound) {
            messages.add(new LlmMessage.TextMessage(
                    LlmMessage.MessageRole.USER,
                    interventions.get(index).content()));
            index++;
        }
        return index;
    }

    private RequestContractException invalid(String message) {
        return new RequestContractException("HISTORY_INVALID", message);
    }

}
