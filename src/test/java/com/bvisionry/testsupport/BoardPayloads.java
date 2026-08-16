package com.bvisionry.testsupport;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.bvisionry.programflow.dto.BoardResponse;
import com.bvisionry.programflow.dto.FieldUpsert;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.SaveBoardRequest;
import com.bvisionry.programflow.dto.SaveBoardRequest.ModuleUpsert;
import com.bvisionry.programflow.dto.SaveBoardRequest.TaskUpsert;
import com.bvisionry.programflow.dto.TaskDto;

/**
 * Builds the Curriculum builder's Save payload the way the browser does: take
 * the board a read handed back, change the one thing under test, send the WHOLE
 * thing back. Since the §13.10 rework that is the only way to write a board, so
 * a test that wants "rename this task" has to say it in a whole-board sentence
 * — this keeps that from drowning the assertion.
 */
public final class BoardPayloads {

    private BoardPayloads() {
    }

    /** The board, echoed unchanged — the no-op save, and the base for the rest. */
    public static SaveBoardRequest echo(BoardResponse board) {
        return new SaveBoardRequest(board.version(), false, modulesOf(board));
    }

    /** {@link #echo} with the given modules instead — for adds, deletes and moves. */
    public static SaveBoardRequest of(BoardResponse board, List<ModuleUpsert> modules) {
        return new SaveBoardRequest(board.version(), false, modules);
    }

    /** {@link #echo} with one task rewritten in place. */
    public static SaveBoardRequest edit(BoardResponse board, UUID taskId,
            UnaryOperator<TaskUpsert> edit) {
        return new SaveBoardRequest(board.version(), false, board.modules().stream()
                .map(m -> asUpsert(m, m.tasks().stream()
                        .map(t -> t.id().equals(taskId)
                                ? edit.apply(asUpsert(t)) : asUpsert(t))
                        .toList()))
                .toList());
    }

    public static List<ModuleUpsert> modulesOf(BoardResponse board) {
        return board.modules().stream()
                .map(m -> asUpsert(m, m.tasks().stream().map(BoardPayloads::asUpsert).toList()))
                .toList();
    }

    public static ModuleUpsert asUpsert(ModuleDto m, List<TaskUpsert> tasks) {
        return new ModuleUpsert(m.id(), m.name(), m.summary(), m.pillarLabel(), m.paced(),
                m.lockMode(), m.unlockAt(), m.audience().mode(), m.audience().memberIds(), tasks);
    }

    public static TaskUpsert asUpsert(TaskDto t) {
        return new TaskUpsert(t.id(), t.name(), t.dueDate(), t.status(), t.aiDraft(),
                t.taskType(), t.refId(), t.milestoneRole(),
                t.fields().stream()
                        .map(f -> new FieldUpsert(f.id(), f.type(), f.required(), f.config()))
                        .toList());
    }
}
