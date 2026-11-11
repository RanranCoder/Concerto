package com.pams.module.agent.service;

import java.util.List;

/** Adds later user instructions in their accepted order, preserving the original goal. */
public final class SteeringInput {
    private SteeringInput() {}

    public static String appendTo(String goal, List<AgentRequestService.Instruction> inputs) {
        var text = new StringBuilder(goal);
        for (var input : inputs) {
            text.append("\n\n用户在执行中补充的要求（与前文冲突时，以此为准）：\n").append(input.text());
        }
        return text.toString();
    }
}
