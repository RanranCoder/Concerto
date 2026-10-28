package com.pams.module.activity.dto;

import com.pams.module.activity.entity.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Preview and save use the same proposed task; the token binds saving to the observed graph. */
public final class TaskPlan {
    private TaskPlan() {}

    public record Request(Long id, @NotNull @Valid TaskRequest task, String previewToken, Boolean cascade) {}
    public record Impact(Task before, Task after) {}
    public record Preview(Task candidate, List<Impact> impacts, String token) {}
    public record UndoEntry(@NotNull Long id, @NotNull Long expectedRevision, @Valid TaskRequest before) {}
    public record Undo(@NotNull Long activityId, @NotEmpty List<@Valid UndoEntry> entries) {}
    public record Result(List<Task> tasks, Undo undo) {}
}
