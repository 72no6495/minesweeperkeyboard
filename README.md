# マインスイーパーIME（minesweeperkeyboard）

キーボード全体がマインスイーパーの盤面になっている Android 用カスタムキーボード（`InputMethodService`）です。
**マスを開くことがそのまま文字入力になります。**

| 操作 | 入力される文字 |
| --- | --- |
| 安全なマスをタップ | 設定した文字（例：`ｗ`）を **開いたマスの数だけ** 入力。連鎖で 30 マス開けば `ｗ` が 30 個 |
| 開いている数字マスをタップ | その数字（`1`〜`8`）。旗の数が合っていれば周囲を一括オープン（設定で OFF 可） |
| 地雷を踏む | `💥 ゲームオーバー！(15マス開けた)` |
| 全安全マスを開く | `🎉 マインスイーパー クリア！ タイム: 45秒` |
| 長押し | 旗を立てる／外す（🚩モード中は逆：タップで旗、長押しで開く） |

## 画面構成

```
┌──────────────────────────────┐
│ [010]        (🙂)       [045] │ ← 残り地雷 / ニコちゃん（タップでリセット）/ タイマー
├──────────────────────────────┤
│ ▓▓▓▓▓▓▓▓▓  盤面（Canvas 描画） │ ← キーボード高さに収まるよう自動で縮小
├──────────────────────────────┤
│[初級][🚩][⚙][🌐][空白][⌫][⏎]│ ← 難易度切替 / 旗モード / 設定 / キーボード切替 …
└──────────────────────────────┘
```

* Windows 95/98 風：閉じたマスは凸型グレー、開いたマスは凹んだフラットグレー、数字は 1=青 2=緑 3=赤 4=紺 5=えんじ 6=青緑 7=黒 8=灰
* 赤い 7 セグメント LED カウンタ、押している間は 😮、負けると ×× 、勝つとサングラス
* 難易度ボタンで **初級 9×9/10 → 中級 16×16/40 → 上級 16×30/99 → カスタム** を切り替え
* 最初のタップは必ず安全（周囲 8 マスも安全）

## プロジェクト構成

```
app/src/main/
├── AndroidManifest.xml            IME サービスと設定 Activity の登録
├── res/xml/method.xml             IME のメタデータ
├── res/values/strings.xml         リザルト文言など
└── java/com/minesweeperkeyboard/ime/
    ├── MinesweeperEngine.kt       ゲームロジック（Android 非依存）
    ├── TextGimmick.kt             「開いたマス数 → 入力文字列」変換
    ├── MinesweeperIME.kt          InputMethodService 本体 / commitText
    ├── MinesweeperBoardView.kt    盤面の Canvas 描画・タップ/長押し判定
    ├── ClassicViews.kt            ステータスバー（LED・顔）とクラシックボタン
    ├── ClassicStyle.kt            立体枠・地雷・旗・顔・7セグの描画ヘルパー
    ├── ImePrefs.kt                SharedPreferences（入力文字・盤面サイズ等）
    └── SettingsActivity.kt        設定画面（ランチャーから起動）
app/src/test/…/MinesweeperEngineTest.kt  ロジックの単体テスト
.github/workflows/build-apk.yml   GitHub Actions で APK をビルド
```

## APK の入手（GitHub Actions）

push するたびに GitHub Actions が単体テストと APK ビルドを実行します。

1. GitHub のリポジトリ → **Actions** → **Build APK** → 最新の実行を開く
2. 下部の **Artifacts** から `minesweeper-ime-apk` をダウンロード（zip 内に `minesweeper-ime-debug.apk` / `minesweeper-ime-release.apk`）

※ どちらもデバッグ鍵で署名されています。実行ごとに鍵が変わるため、更新時に「署名が一致しない」と出たら一度アンインストールしてください。

## ローカルでのビルド

Android Studio（Ladybug 以降）でフォルダを開くか、Android SDK を入れた環境で：

```bash
./gradlew testDebugUnitTest   # ロジックのテスト
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

要件：JDK 17、compileSdk 35、minSdk 24（Android 7.0 以上）

## エミュレータ／実機での試し方

1. APK をインストールし、ランチャーの **マインスイーパーIME** を開く
2. 「キーボードを有効にする」→ システム設定で **マインスイーパーキーボード** を ON
3. 「入力方法をマインスイーパーに切り替える」で選択
4. 設定画面下部の「試し打ち」欄（またはメモアプリ等）をタップして遊ぶ
5. 好きな文字・連鎖時の入力方法・盤面サイズ・盤面の最大高さは設定画面で変更（⚙ ボタンからも開けます）

adb でまとめて設定する場合：

```bash
adb shell ime enable com.minesweeperkeyboard.ime/.MinesweeperIME
adb shell ime set    com.minesweeperkeyboard.ime/.MinesweeperIME
```
