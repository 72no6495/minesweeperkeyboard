package com.minesweeperkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MinesweeperEngineTest {

    private var now = 0L
    private fun engine(rows: Int, cols: Int, mines: Int, seed: Int = 1) =
        MinesweeperEngine(rows, cols, mines, Random(seed)) { now }

    @Test
    fun firstOpenIsAlwaysSafeAndOpensNeighborhood() {
        repeat(200) { seed ->
            val e = engine(9, 9, 10, seed)
            val result = e.open(4, 4)
            assertFalse(result.exploded)
            assertEquals(0, e.cell(4, 4).adjacentMines)
            assertTrue(result.openedCells >= 9)
            assertEquals(10, (0 until 9).sumOf { r -> (0 until 9).count { c -> e.cell(r, c).isMine } })
        }
    }

    @Test
    fun adjacencyIsComputed() {
        val e = engine(5, 5, 2)
        e.setMinesForTest(listOf(0 to 0, 0 to 2))
        assertEquals(2, e.cell(0, 1).adjacentMines)
        assertEquals(1, e.cell(1, 0).adjacentMines)
        assertEquals(2, e.cell(1, 1).adjacentMines)
        assertEquals(0, e.cell(4, 4).adjacentMines)
    }

    @Test
    fun floodFillStopsAtNumbers() {
        val e = engine(5, 5, 1)
        e.setMinesForTest(listOf(0 to 0))
        val result = e.open(4, 4)
        // 地雷以外すべて開いてクリア
        assertEquals(24, result.openedCells)
        assertTrue(result.won)
        assertEquals(MinesweeperEngine.State.WON, e.state)
        assertTrue(e.cell(0, 0).isFlagged)
    }

    @Test
    fun steppingOnMineLoses() {
        val e = engine(5, 5, 1)
        e.setMinesForTest(listOf(2 to 2))
        val result = e.open(2, 2)
        assertTrue(result.exploded)
        assertEquals(MinesweeperEngine.State.LOST, e.state)
        assertTrue(e.cell(2, 2).isExploded)
        // 終了後の操作は無効
        assertTrue(e.open(4, 4).isNoop)
    }

    @Test
    fun flagsBlockOpenAndCount() {
        val e = engine(5, 5, 2)
        e.setMinesForTest(listOf(0 to 0, 4 to 4))
        assertTrue(e.toggleFlag(0, 0))
        assertEquals(1, e.remainingMines)
        assertTrue(e.open(0, 0).isNoop)
        assertTrue(e.toggleFlag(0, 0))
        assertEquals(2, e.remainingMines)
    }

    @Test
    fun chordOpensNeighborsWhenFlagsMatch() {
        val e = engine(5, 5, 1)
        e.setMinesForTest(listOf(0 to 0))
        e.open(1, 1)
        assertEquals(1, e.cell(1, 1).adjacentMines)
        // 旗なしではコードしない
        assertTrue(e.chord(1, 1).isNoop)
        e.toggleFlag(0, 0)
        val r = e.chord(1, 1)
        assertFalse(r.exploded)
        assertTrue(r.won)
    }

    @Test
    fun wrongChordExplodes() {
        val e = engine(5, 5, 1)
        e.setMinesForTest(listOf(0 to 0))
        e.open(1, 1)
        e.toggleFlag(0, 1)
        val r = e.chord(1, 1)
        assertTrue(r.exploded)
        assertTrue(e.cell(0, 1).isWrongFlag)
    }

    @Test
    fun timerMeasuresSeconds() {
        val e = engine(5, 5, 1)
        now = 1_000L
        e.setMinesForTest(listOf(0 to 0))
        now = 46_500L
        assertEquals(45, e.elapsedSeconds())
        e.open(4, 4)
        now = 100_000L
        assertEquals(45, e.elapsedSeconds())
    }

    @Test
    fun sizesAreClamped() {
        val e = engine(1, 100, 10_000)
        assertEquals(MinesweeperEngine.MIN_SIZE, e.rows)
        assertEquals(MinesweeperEngine.MAX_SIZE, e.cols)
        assertEquals(e.rows * e.cols - 1, e.mineCount)
        assertFalse(e.open(0, 0).exploded)
    }

    @Test
    fun textGimmick() {
        assertEquals("ｗｗｗ", TextGimmick.textForOpenedCells(3, "ｗ", ChainMode.PER_CELL, 0))
        assertEquals("草草", TextGimmick.textForOpenedCells(5, "草", ChainMode.PER_CELL, 2))
        assertEquals("あ", TextGimmick.textForOpenedCells(5, "あ", ChainMode.ONCE, 0))
        assertEquals("", TextGimmick.textForOpenedCells(0, "あ", ChainMode.PER_CELL, 0))
    }
}
