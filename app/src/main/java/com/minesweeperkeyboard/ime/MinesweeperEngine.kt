package com.minesweeperkeyboard.ime

import kotlin.random.Random

/**
 * Android に依存しない純粋なマインスイーパーのゲームロジック。
 *
 * - 地雷は「最初に開いたマス」が必ず安全になるよう、初回オープン時に配置する
 * - 空白（周囲0）マスは BFS で連鎖的に開く
 * - 時間は [clock] から取得する（テストで差し替え可能）
 */
class MinesweeperEngine(
    rows: Int,
    cols: Int,
    mines: Int,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val rows: Int = rows.coerceIn(MIN_SIZE, MAX_SIZE)
    val cols: Int = cols.coerceIn(MIN_SIZE, MAX_SIZE)
    val mineCount: Int = mines.coerceIn(1, this.rows * this.cols - 1)

    enum class State { READY, PLAYING, WON, LOST }

    class Cell {
        var isMine = false
        var adjacentMines = 0
        var isOpen = false
        var isFlagged = false
        /** 踏んだ地雷 */
        var isExploded = false
        /** ゲームオーバー時に判明した誤った旗 */
        var isWrongFlag = false
    }

    /** 1回の操作（タップ／コード）の結果 */
    data class MoveResult(
        /** この操作で新たに開いた安全マス（開いた順） */
        val openedPositions: List<Pair<Int, Int>> = emptyList(),
        val exploded: Boolean = false,
        val won: Boolean = false,
    ) {
        /** この操作で新たに開いた安全マスの数 */
        val openedCells: Int get() = openedPositions.size
        val isNoop: Boolean get() = openedCells == 0 && !exploded && !won
    }

    private val cells = Array(this.rows) { Array(this.cols) { Cell() } }

    var state: State = State.READY
        private set

    /** これまでに開いた安全マスの総数 */
    var openedCount: Int = 0
        private set

    var flagCount: Int = 0
        private set

    private var startMillis = 0L
    private var endMillis = 0L

    val totalSafeCells: Int get() = rows * cols - mineCount
    val remainingMines: Int get() = mineCount - flagCount
    val isGameOver: Boolean get() = state == State.WON || state == State.LOST

    fun cell(r: Int, c: Int): Cell = cells[r][c]

    fun inBounds(r: Int, c: Int) = r in 0 until rows && c in 0 until cols

    /** 経過秒数（クラシック版同様 999 で頭打ち） */
    fun elapsedSeconds(): Int {
        val ms = when (state) {
            State.READY -> 0L
            State.PLAYING -> clock() - startMillis
            State.WON, State.LOST -> endMillis - startMillis
        }
        return (ms / 1000L).toInt().coerceIn(0, 999)
    }

    /** マスを開く */
    fun open(r: Int, c: Int): MoveResult {
        if (isGameOver || !inBounds(r, c)) return MoveResult()
        val target = cells[r][c]
        if (target.isOpen || target.isFlagged) return MoveResult()

        if (state == State.READY) {
            placeMines(r, c)
            state = State.PLAYING
            startMillis = clock()
        }

        if (target.isMine) {
            target.isExploded = true
            lose()
            return MoveResult(exploded = true)
        }

        val opened = ArrayList<Pair<Int, Int>>()
        floodOpen(r, c, opened)
        return MoveResult(opened, won = checkWin())
    }

    /**
     * 開いている数字マスの周囲の旗の数が数字と一致していれば、旗以外の周囲マスを一括で開く
     * （クラシック版の「両クリック」）。
     */
    fun chord(r: Int, c: Int): MoveResult {
        if (state != State.PLAYING || !inBounds(r, c)) return MoveResult()
        val center = cells[r][c]
        if (!center.isOpen || center.adjacentMines == 0) return MoveResult()
        val flags = neighbors(r, c).count { (nr, nc) -> cells[nr][nc].isFlagged }
        if (flags != center.adjacentMines) return MoveResult()

        val opened = ArrayList<Pair<Int, Int>>()
        var exploded = false
        for ((nr, nc) in neighbors(r, c)) {
            val n = cells[nr][nc]
            if (n.isOpen || n.isFlagged) continue
            if (n.isMine) {
                n.isExploded = true
                exploded = true
            } else {
                floodOpen(nr, nc, opened)
            }
        }
        if (exploded) {
            lose()
            return MoveResult(opened, exploded = true)
        }
        return MoveResult(opened, won = checkWin())
    }

    /** 旗の切り替え。変化があれば true */
    fun toggleFlag(r: Int, c: Int): Boolean {
        if (isGameOver || !inBounds(r, c)) return false
        val cell = cells[r][c]
        if (cell.isOpen) return false
        cell.isFlagged = !cell.isFlagged
        flagCount += if (cell.isFlagged) 1 else -1
        return true
    }

    fun neighbors(r: Int, c: Int): List<Pair<Int, Int>> {
        val list = ArrayList<Pair<Int, Int>>(8)
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val nr = r + dr
            val nc = c + dc
            if (inBounds(nr, nc)) list += nr to nc
        }
        return list
    }

    /** テスト用：地雷配置を固定する */
    internal fun setMinesForTest(positions: Collection<Pair<Int, Int>>) {
        require(positions.size == mineCount) { "mine count mismatch" }
        positions.forEach { (r, c) -> cells[r][c].isMine = true }
        computeAdjacency()
        state = State.PLAYING
        startMillis = clock()
    }

    private fun placeMines(safeR: Int, safeC: Int) {
        val total = rows * cols
        // 最初のマスとその周囲を安全にする（地雷が多すぎて無理なら最初のマスだけ）
        val excluded = HashSet<Int>()
        excluded += safeR * cols + safeC
        if (total - mineCount >= 9) {
            neighbors(safeR, safeC).forEach { (r, c) -> excluded += r * cols + c }
        }
        val candidates = (0 until total).filter { it !in excluded }.shuffled(random)
        candidates.take(mineCount).forEach { idx -> cells[idx / cols][idx % cols].isMine = true }
        computeAdjacency()
    }

    private fun computeAdjacency() {
        for (r in 0 until rows) for (c in 0 until cols) {
            cells[r][c].adjacentMines = neighbors(r, c).count { (nr, nc) -> cells[nr][nc].isMine }
        }
    }

    /** BFS で連鎖オープンし、開いたマスを [opened] に追加する */
    private fun floodOpen(startR: Int, startC: Int, opened: MutableList<Pair<Int, Int>>) {
        val before = opened.size
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.addLast(startR to startC)
        while (queue.isNotEmpty()) {
            val (r, c) = queue.removeFirst()
            val cell = cells[r][c]
            if (cell.isOpen || cell.isFlagged || cell.isMine) continue
            cell.isOpen = true
            opened += r to c
            if (cell.adjacentMines == 0) {
                for (n in neighbors(r, c)) {
                    val nc = cells[n.first][n.second]
                    if (!nc.isOpen && !nc.isFlagged) queue.addLast(n)
                }
            }
        }
        openedCount += opened.size - before
    }

    private fun checkWin(): Boolean {
        if (openedCount < totalSafeCells) return false
        state = State.WON
        endMillis = clock()
        // クリア時は残りの地雷すべてに旗を立てる（クラシック版と同じ演出）
        for (row in cells) for (cell in row) {
            if (cell.isMine && !cell.isFlagged) {
                cell.isFlagged = true
                flagCount++
            }
        }
        return true
    }

    private fun lose() {
        state = State.LOST
        endMillis = clock()
        for (row in cells) for (cell in row) {
            if (cell.isFlagged && !cell.isMine) cell.isWrongFlag = true
        }
    }

    companion object {
        const val MIN_SIZE = 5
        const val MAX_SIZE = 30
    }
}
