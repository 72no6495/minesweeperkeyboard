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
        assertEquals(MinesweeperEngine.MIN_ENGINE_SIZE, e.rows)
        assertEquals(MinesweeperEngine.MAX_SIZE, e.cols)
        assertEquals(e.rows * e.cols - 1, e.mineCount)
        assertFalse(e.open(0, 0).exploded)
    }

    @Test
    fun openReportsOpenedPositionsStartingWithTappedCell() {
        val e = engine(5, 5, 1)
        e.setMinesForTest(listOf(0 to 0))
        val result = e.open(4, 4)
        assertEquals(4 to 4, result.openedPositions.first())
        assertEquals(24, result.openedPositions.toSet().size)
    }

    private val cells = listOf(0 to 0, 0 to 1, 1 to 0)

    @Test
    fun fixedTextRepeatsPerCell() {
        val s = TextSettings(mode = TextMode.FIXED, customText = "ｗ")
        assertEquals("ｗｗｗ", TextGimmick.textForOpened(cells, 9, s))
        assertEquals("ｗｗ", TextGimmick.textForOpened(cells, 9, s.copy(maxRepeat = 2)))
        assertEquals("ｗ", TextGimmick.textForOpened(cells, 9, s.copy(chainMode = ChainMode.ONCE)))
        assertEquals("", TextGimmick.textForOpened(emptyList(), 9, s))
    }

    @Test
    fun coordinateTableUsesCellPosition() {
        val s = TextSettings(mode = TextMode.COORDINATE_TABLE, charTable = TextGimmick.splitUnits("あいうえお"))
        // (0,0)=あ (0,1)=い (1,0)= 1*3+0=3 → え
        assertEquals("あいえ", TextGimmick.textForOpened(cells, 3, s))
        // 文字表が足りなければ先頭に戻る：(2,2) on 3 cols = 8 % 5 = 3 → え
        assertEquals("え", TextGimmick.tableCharAt(2, 2, 3, s.charTable))
        assertEquals("あ", TextGimmick.textForOpened(cells, 3, s.copy(chainMode = ChainMode.ONCE)))
    }

    @Test
    fun coordinateLabel() {
        assertEquals("A1", TextGimmick.coordinateLabel(0, 0))
        assertEquals("B3", TextGimmick.coordinateLabel(2, 1))
        assertEquals("Z10", TextGimmick.coordinateLabel(9, 25))
        assertEquals("AA1", TextGimmick.coordinateLabel(0, 26))
        val s = TextSettings(mode = TextMode.COORDINATE_LABEL)
        assertEquals("A1B1A2", TextGimmick.textForOpened(cells, 9, s))
    }

    @Test
    fun randomPicksFromPool() {
        val pool = TextGimmick.splitUnits("abc")
        val s = TextSettings(mode = TextMode.RANDOM, randomPool = pool)
        val many = List(50) { 0 to 0 }
        val text = TextGimmick.textForOpened(many, 9, s, Random(42))
        assertEquals(50, text.length)
        assertTrue(text.all { it.toString() in pool })
        assertTrue(text.toSet().size > 1)
    }

    @Test
    fun splitUnits() {
        assertEquals(listOf("あ", "い", "😀"), TextGimmick.splitUnits("あ い😀"))
        assertEquals(listOf("おはよう", "こんにちは"), TextGimmick.splitUnits("おはよう, こんにちは,"))
        assertEquals(listOf("草", "ｗ"), TextGimmick.splitUnits("草、ｗ"))
    }

    @Test
    fun blockedAndPresetFlagsNeverGetMines() {
        repeat(100) { seed ->
            val e = engine(4, 10, 20, seed)
            val blocked = 3 to 0
            val flags = listOf(0 to 0, 0 to 9, 3 to 9)
            e.prepare(blocked = listOf(blocked), flags = flags)
            assertEquals(3, e.flagCount)
            e.open(1, 5)
            assertFalse(e.cell(3, 0).isMine)
            flags.forEach { (r, c) -> assertFalse(e.cell(r, c).isMine) }
            // 使わないマスは開けず、旗も立てられない
            assertTrue(e.open(3, 0).isNoop)
            assertFalse(e.toggleFlag(3, 0))
            val mines = (0 until 4).sumOf { r -> (0 until 10).count { c -> e.cell(r, c).isMine } }
            assertEquals(e.mineCount, mines)
        }
    }

    @Test
    fun winIgnoresBlockedCells() {
        val e = engine(5, 5, 1)
        e.prepare(blocked = listOf(4 to 4))
        e.setMinesForTest(listOf(0 to 0))
        val r = e.open(2, 2)
        assertTrue(r.won)
        assertFalse(e.cell(4, 4).isOpen)
        assertEquals(23, e.openedCount)
    }

    @Test
    fun recentFlagsNewestFirst() {
        val e = engine(5, 5, 3)
        e.toggleFlag(0, 0)
        e.toggleFlag(1, 1)
        e.toggleFlag(2, 2)
        e.toggleFlag(3, 3)
        e.toggleFlag(1, 1) // 外した旗は含まない
        assertEquals(listOf(3 to 3, 2 to 2, 0 to 0), e.recentFlags(3))
        assertEquals(listOf(3 to 3), e.recentFlags(1))
    }
}
