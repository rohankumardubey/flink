/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.operators.join;

import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.disk.iomanager.IOManagerAsync;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.memory.MemoryManagerBuilder;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.runtime.generated.GeneratedJoinCondition;
import org.apache.flink.table.runtime.generated.GeneratedNormalizedKeyComputer;
import org.apache.flink.table.runtime.generated.GeneratedProjection;
import org.apache.flink.table.runtime.generated.GeneratedRecordComparator;
import org.apache.flink.table.runtime.generated.JoinCondition;
import org.apache.flink.table.runtime.generated.NormalizedKeyComputer;
import org.apache.flink.table.runtime.generated.Projection;
import org.apache.flink.table.runtime.generated.RecordComparator;
import org.apache.flink.table.runtime.operators.join.Int2HashJoinOperatorTestBase.MyProjection;
import org.apache.flink.table.runtime.operators.sort.IntNormalizedKeyComputer;
import org.apache.flink.table.runtime.operators.sort.IntRecordComparator;
import org.apache.flink.table.runtime.util.UniformBinaryRowGenerator;
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameters;
import org.apache.flink.util.MutableObjectIterator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.Collection;

import static org.apache.flink.table.runtime.operators.join.Int2HashJoinOperatorTest.joinAndAssert;
import static org.assertj.core.api.Assertions.fail;

/** Random test for {@link SortMergeJoinOperator}. */
@ExtendWith(ParameterizedTestExtension.class)
class Int2SortMergeJoinOperatorTest {

    private boolean leftIsSmaller;

    private MemoryManager memManager;
    private IOManager ioManager;

    public Int2SortMergeJoinOperatorTest(boolean leftIsSmaller) {
        this.leftIsSmaller = leftIsSmaller;
    }

    @Parameters(name = "leftIsSmaller={0}")
    private static Collection<Boolean> parameters() {
        return Arrays.asList(true, false);
    }

    @BeforeEach
    void setup() {
        this.memManager = MemoryManagerBuilder.newBuilder().setMemorySize(36 * 1024 * 1024).build();
        this.ioManager = new IOManagerAsync();
    }

    @AfterEach
    void tearDown() throws Exception {
        // shut down I/O manager and Memory Manager and verify the correct shutdown
        this.ioManager.close();
        if (!this.memManager.verifyEmpty()) {
            fail("Not all memory was properly released to the memory manager --> Memory Leak.");
        }
    }

    @TestTemplate
    void testInnerJoin() throws Exception {
        int numKeys = 100;
        int buildValsPerKey = 3;
        int probeValsPerKey = 10;

        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);

        buildJoin(
                buildInput,
                probeInput,
                FlinkJoinType.INNER,
                numKeys * buildValsPerKey * probeValsPerKey,
                numKeys,
                165);
    }

    @TestTemplate
    void testLeftOutJoin() throws Exception {

        int numKeys1 = 9;
        int numKeys2 = 10;
        int buildValsPerKey = 3;
        int probeValsPerKey = 10;

        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

        buildJoin(
                buildInput,
                probeInput,
                FlinkJoinType.LEFT,
                numKeys1 * buildValsPerKey * probeValsPerKey,
                numKeys1,
                165);
    }

    @TestTemplate
    void testRightOutJoin() throws Exception {
        int numKeys1 = 9;
        int numKeys2 = 10;
        int buildValsPerKey = 3;
        int probeValsPerKey = 10;

        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

        buildJoin(buildInput, probeInput, FlinkJoinType.RIGHT, 280, numKeys2, -1);
    }

    @TestTemplate
    void testFullOutJoin() throws Exception {
        int numKeys1 = 9;
        int numKeys2 = 10;
        int buildValsPerKey = 3;
        int probeValsPerKey = 10;

        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

        buildJoin(buildInput, probeInput, FlinkJoinType.FULL, 280, numKeys2, -1);
    }

    @TestTemplate
    void testSemiJoin() throws Exception {

        int numKeys1 = 10;
        int numKeys2 = 9;
        int buildValsPerKey = 10;
        int probeValsPerKey = 3;
        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

        StreamOperator operator = newOperator(FlinkJoinType.SEMI, false);
        joinAndAssert(operator, buildInput, probeInput, 90, 9, 45, true);
    }

    @TestTemplate
    void testAntiJoin() throws Exception {

        int numKeys1 = 10;
        int numKeys2 = 9;
        int buildValsPerKey = 10;
        int probeValsPerKey = 3;
        MutableObjectIterator<BinaryRowData> buildInput =
                new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
        MutableObjectIterator<BinaryRowData> probeInput =
                new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

        StreamOperator operator = newOperator(FlinkJoinType.ANTI, false);
        joinAndAssert(operator, buildInput, probeInput, 10, 1, 45, true);
    }

    private void buildJoin(
            MutableObjectIterator<BinaryRowData> input1,
            MutableObjectIterator<BinaryRowData> input2,
            FlinkJoinType type,
            int expertOutSize,
            int expertOutKeySize,
            int expertOutVal)
            throws Exception {

        joinAndAssert(
                getOperator(type),
                input1,
                input2,
                expertOutSize,
                expertOutKeySize,
                expertOutVal,
                false);
    }

    private StreamOperator getOperator(FlinkJoinType type) {
        return newOperator(type, leftIsSmaller);
    }

    static StreamOperator newOperator(FlinkJoinType type, boolean leftIsSmaller) {
        return new SortMergeJoinOperator(getJoinFunction(type, leftIsSmaller));
    }

    public static SortMergeJoinFunction getJoinFunction(FlinkJoinType type, boolean leftIsSmaller) {
        int maxNumFileHandles =
                ExecutionConfigOptions.TABLE_EXEC_SORT_MAX_NUM_FILE_HANDLES.defaultValue();
        boolean compressionEnable =
                ExecutionConfigOptions.TABLE_EXEC_SPILL_COMPRESSION_ENABLED.defaultValue();
        int compressionBlockSize =
                (int)
                        ExecutionConfigOptions.TABLE_EXEC_SPILL_COMPRESSION_BLOCK_SIZE
                                .defaultValue()
                                .getBytes();
        boolean asyncMergeEnable =
                ExecutionConfigOptions.TABLE_EXEC_SORT_ASYNC_MERGE_ENABLED.defaultValue();
        return new SortMergeJoinFunction(
                0,
                type,
                leftIsSmaller,
                maxNumFileHandles,
                compressionEnable,
                compressionBlockSize,
                asyncMergeEnable,
                new GeneratedJoinCondition("", "", new Object[0]) {
                    @Override
                    public JoinCondition newInstance(ClassLoader classLoader) {
                        return new Int2HashJoinOperatorTest.TrueCondition();
                    }
                },
                new GeneratedProjection("", "", new Object[0]) {
                    @Override
                    public Projection newInstance(ClassLoader classLoader) {
                        return new MyProjection();
                    }
                },
                new GeneratedProjection("", "", new Object[0]) {
                    @Override
                    public Projection newInstance(ClassLoader classLoader) {
                        return new MyProjection();
                    }
                },
                new GeneratedNormalizedKeyComputer("", "") {
                    @Override
                    public NormalizedKeyComputer newInstance(ClassLoader classLoader) {
                        return new IntNormalizedKeyComputer();
                    }
                },
                new GeneratedRecordComparator("", "", new Object[0]) {
                    @Override
                    public RecordComparator newInstance(ClassLoader classLoader) {
                        return new IntRecordComparator();
                    }
                },
                new GeneratedNormalizedKeyComputer("", "") {
                    @Override
                    public NormalizedKeyComputer newInstance(ClassLoader classLoader) {
                        return new IntNormalizedKeyComputer();
                    }
                },
                new GeneratedRecordComparator("", "", new Object[0]) {
                    @Override
                    public RecordComparator newInstance(ClassLoader classLoader) {
                        return new IntRecordComparator();
                    }
                },
                new GeneratedRecordComparator("", "", new Object[0]) {
                    @Override
                    public RecordComparator newInstance(ClassLoader classLoader) {
                        return new IntRecordComparator();
                    }
                },
                new boolean[] {true});
    }
}
