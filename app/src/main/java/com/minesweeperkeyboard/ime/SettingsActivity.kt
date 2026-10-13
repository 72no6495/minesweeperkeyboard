package com.minesweeperkeyboard.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** 入力文字・盤面サイズなどの設定画面（レイアウトはコードで構築） */
class SettingsActivity : Activity() {

    private lateinit var prefs: ImePrefs

    private lateinit var customText: EditText
    private lateinit var chainGroup: RadioGroup
    private lateinit var maxChain: EditText
    private lateinit var chordBox: CheckBox
    private lateinit var hapticsBox: CheckBox
    private lateinit var difficultyGroup: RadioGroup
    private lateinit var rowsEdit: EditText
    private lateinit var colsEdit: EditText
    private lateinit var minesEdit: EditText
    private lateinit var heightSeek: SeekBar
    private lateinit var heightLabel: TextView
    private lateinit var statusText: TextView

    private val difficultyIds = HashMap<Int, Difficulty>()

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ImePrefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }

        // ---- セットアップ
        root.addView(header("① キーボードのセットアップ"))
        statusText = TextView(this)
        root.addView(statusText)
        root.addView(button("キーボードを有効にする（システム設定）") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(button("入力方法をマインスイーパーに切り替える") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        // ---- 文字入力ギミック
        root.addView(header("② 安全なマスを開いたときに入力する文字"))
        customText = EditText(this).apply {
            setText(prefs.customText)
            hint = "例：ｗ / 草 / あ"
            setSingleLine()
        }
        root.addView(customText)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (preset in listOf("ｗ", "草", "あ", "💣", "ﾋﾟｴﾝ")) {
            presets.addView(Button(this).apply {
                text = preset
                isAllCaps = false
                setOnClickListener { customText.setText(preset) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(presets)

        root.addView(label("連鎖オープン時の入力"))
        chainGroup = RadioGroup(this)
        val perCell = RadioButton(this).apply { id = View.generateViewId(); text = "開いたマスの数だけ連続入力" }
        val once = RadioButton(this).apply { id = View.generateViewId(); text = "1回のタップにつき1回だけ" }
        chainGroup.addView(perCell)
        chainGroup.addView(once)
        chainGroup.check(if (prefs.chainMode == ChainMode.ONCE) once.id else perCell.id)
        perCell.tag = ChainMode.PER_CELL
        once.tag = ChainMode.ONCE
        root.addView(chainGroup)

        root.addView(label("1回の連鎖で入力する最大文字数（0 = 無制限）"))
        maxChain = numberEdit(prefs.maxChainRepeat)
        root.addView(maxChain)

        chordBox = CheckBox(this).apply {
            text = "数字マスをタップしたとき、旗の数が合っていれば周囲を一括オープン"
            isChecked = prefs.chordEnabled
        }
        root.addView(chordBox)
        hapticsBox = CheckBox(this).apply {
            text = "振動フィードバック"
            isChecked = prefs.hapticsEnabled
        }
        root.addView(hapticsBox)

        // ---- 盤面
        root.addView(header("③ 盤面サイズ"))
        difficultyGroup = RadioGroup(this)
        for (d in Difficulty.entries) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = if (d == Difficulty.CUSTOM) getString(d.labelRes)
                else "${getString(d.labelRes)}（${d.cols}×${d.rows} / 地雷${d.mines}）"
            }
            difficultyIds[rb.id] = d
            difficultyGroup.addView(rb)
            if (d == prefs.difficulty) difficultyGroup.check(rb.id)
        }
        root.addView(difficultyGroup)

        val custom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rowsEdit = numberEdit(prefs.customRows)
        colsEdit = numberEdit(prefs.customCols)
        minesEdit = numberEdit(prefs.customMines)
        custom.addView(label("縦"))
        custom.addView(rowsEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        custom.addView(label("横"))
        custom.addView(colsEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        custom.addView(label("地雷"))
        custom.addView(minesEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(label("カスタムのサイズ（${MinesweeperEngine.MIN_SIZE}〜${MinesweeperEngine.MAX_SIZE}マス）"))
        root.addView(custom)

        heightLabel = label("")
        root.addView(heightLabel)
        heightSeek = SeekBar(this).apply {
            max = ImePrefs.MAX_HEIGHT_PERCENT - ImePrefs.MIN_HEIGHT_PERCENT
            progress = prefs.boardHeightPercent - ImePrefs.MIN_HEIGHT_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateHeightLabel()
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        updateHeightLabel()
        root.addView(heightSeek)

        root.addView(button("保存") {
            save()
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        })

        // ---- 試し打ち
        root.addView(header("④ 試し打ち"))
        root.addView(EditText(this).apply {
            hint = "ここをタップしてキーボードを試す"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        prefs.customText = customText.text.toString()
        prefs.chainMode = chainGroup.findViewById<RadioButton>(chainGroup.checkedRadioButtonId)?.tag as? ChainMode
            ?: ChainMode.PER_CELL
        prefs.maxChainRepeat = maxChain.text.toString().toIntOrNull() ?: 0
        prefs.chordEnabled = chordBox.isChecked
        prefs.hapticsEnabled = hapticsBox.isChecked
        prefs.difficulty = difficultyIds[difficultyGroup.checkedRadioButtonId] ?: Difficulty.BEGINNER

        val rows = (rowsEdit.text.toString().toIntOrNull() ?: prefs.customRows)
            .coerceIn(MinesweeperEngine.MIN_SIZE, MinesweeperEngine.MAX_SIZE)
        val cols = (colsEdit.text.toString().toIntOrNull() ?: prefs.customCols)
            .coerceIn(MinesweeperEngine.MIN_SIZE, MinesweeperEngine.MAX_SIZE)
        val mines = (minesEdit.text.toString().toIntOrNull() ?: prefs.customMines).coerceIn(1, rows * cols - 1)
        prefs.customRows = rows
        prefs.customCols = cols
        prefs.customMines = mines
        rowsEdit.setText(rows.toString())
        colsEdit.setText(cols.toString())
        minesEdit.setText(mines.toString())

        prefs.boardHeightPercent = heightSeek.progress + ImePrefs.MIN_HEIGHT_PERCENT
    }

    private fun updateHeightLabel() {
        heightLabel.text = "盤面の最大高さ：画面の ${heightSeek.progress + ImePrefs.MIN_HEIGHT_PERCENT}%"
    }

    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.startsWith("$packageName/") == true
        statusText.text = buildString {
            append(if (enabled) "✅ 有効化済み" else "⬜ まだ有効化されていません")
            append("\n")
            append(if (selected) "✅ 現在の入力方法です" else "⬜ 現在の入力方法ではありません")
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun numberEdit(value: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        setSingleLine()
    }
}
