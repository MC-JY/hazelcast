/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.exec.join;

import com.hazelcast.sql.impl.QueryContext;
import com.hazelcast.sql.impl.exec.AbstractUpstreamAwareExec;
import com.hazelcast.sql.impl.exec.Exec;
import com.hazelcast.sql.impl.exec.IterationResult;
import com.hazelcast.sql.impl.exec.UpstreamState;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.row.EmptyRowBatch;
import com.hazelcast.sql.impl.row.HeapRow;
import com.hazelcast.sql.impl.row.JoinRow;
import com.hazelcast.sql.impl.row.Row;
import com.hazelcast.sql.impl.row.RowBatch;

/**
 * Executor for local join.
 */
// TODO: Semi join support
public class NestedLoopJoinExec extends AbstractUpstreamAwareExec {
    /** Right input. */
    private final UpstreamState rightState;

    /** Filter. */
    private final Expression<Boolean> filter;

    /** Whether this is the outer join. */
    private final boolean outer;

    /** Empty right row. */
    private final Row rightEmptyRow;

    /** Current left row. */
    private Row leftRow;

    /** Whether at least one match from the right was found for the current left row. */
    private boolean rightMatchFound;

    /** Current row. */
    private RowBatch curRow;

    public NestedLoopJoinExec(
        Exec left,
        Exec right,
        Expression<Boolean> filter,
        boolean outer,
        int rightRowColumnCount
    ) {
        super(left);

        rightState = new UpstreamState(right);

        this.filter = filter;

        this.outer = outer;
        rightEmptyRow = outer ? new HeapRow(rightRowColumnCount) : null;
    }

    @Override
    protected void setup1(QueryContext ctx) {
        rightState.setup(ctx);
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    @Override
    public IterationResult advance() {
        while (true) {
            // Get the left row.
            if (leftRow == null) {
                assert !rightMatchFound;

                while (true) {
                    if (!state.advance()) {
                        return IterationResult.WAIT;
                    }

                    leftRow = state.nextIfExists();

                    if (leftRow != null) {
                        rightState.reset();

                        break;
                    } else if (state.isDone()) {
                        curRow = EmptyRowBatch.INSTANCE;

                        return IterationResult.FETCHED_DONE;
                    }
                }
            }

            // Iterate over the right input.
            do {
                if (!rightState.advance()) {
                    return IterationResult.WAIT;
                }

                for (Row rightRow : rightState) {
                    JoinRow row = new JoinRow(leftRow, rightRow);

                    // Evaluate the condition.
                    if (filter.eval(ctx, row)) {
                        curRow = row;

                        rightMatchFound = true;

                        if (state.isDone() && rightState.isDone()) {
                            leftRow = null;
                            rightMatchFound = false;

                            return IterationResult.FETCHED_DONE;
                        } else {
                            return IterationResult.FETCHED;
                        }
                    }
                }

            } while (!rightState.isDone());

            if (outer && !rightMatchFound) {
                // If no match were found, then create a [left, null] row for the outer join, and then clear the state.
                curRow = new JoinRow(leftRow, rightEmptyRow);

                leftRow = null;
                rightMatchFound = false;

                return IterationResult.FETCHED;
            } else {
                // Just clear the state.
                leftRow = null;
                rightMatchFound = false;
            }
        }
    }

    @Override
    public RowBatch currentBatch() {
        return curRow != null ? curRow : EmptyRowBatch.INSTANCE;
    }

    @Override
    public boolean canReset() {
        return super.canReset() && rightState.canReset();
    }

    @Override
    protected void reset1() {
        rightState.reset();

        curRow = null;
    }
}
