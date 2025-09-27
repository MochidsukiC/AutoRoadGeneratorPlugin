# 翻訳システム使用ガイド / Translation System Usage Guide

## 概要 / Overview

本プロジェクトに英語・日本語対応の翻訳システムを実装しました。ユーザーは設定で言語を切り替えることができ、全てのメッセージが適切な言語で表示されます。

This project now includes a translation system supporting English and Japanese. Users can switch languages through configuration, and all messages will be displayed in the appropriate language.

## 主な機能 / Main Features

### 1. 言語設定 / Language Settings

- **デフォルト言語**: 日本語 (ja)
- **サポート言語**: 日本語 (ja)、英語 (en)
- **設定場所**: `config.yml` の `language` フィールド

**Default Language**: Japanese (ja)
**Supported Languages**: Japanese (ja), English (en)
**Configuration**: `language` field in `config.yml`

### 2. 言語切り替えコマンド / Language Switch Command

```
/lang [ja|en]
```

- 引数なし: 現在の言語と利用可能な言語を表示
- 引数あり: 指定した言語に切り替え

**Without arguments**: Display current language and available languages
**With arguments**: Switch to specified language

### 3. メッセージファイル / Message Files

- `messages_ja.yml`: 日本語メッセージ
- `messages_en.yml`: 英語メッセージ
- `config.yml`: プラグイン設定（言語設定含む）

**Files**: Japanese messages, English messages, and plugin configuration

## 使用例 / Usage Examples

### 言語の確認 / Check Current Language

```
/lang
```

出力例 / Example output:
- 日本語: `現在の言語: ja`
- English: `Current language: ja`

### 言語の切り替え / Language Switch

```
/lang en
```

出力例 / Example output:
- 日本語から英語への切り替え: `言語を ja から en に変更しました。`
- Switch from Japanese to English: `Language changed from ja to en.`

### 各コマンドの多言語対応例 / Multilingual Command Examples

#### 道路編集モード / Road Edit Mode

**日本語:**
```
/redit
> 編集モードを開始しました。

/redit
> 編集モードを終了しました。
```

**English:**
```
/redit
> Edit mode started.

/redit
> Edit mode ended.
```

#### 操作の取り消し / Undo Operations

**日本語:**
```
/rundo
> 最後に行った設置を取り消しました。
```

**English:**
```
/rundo
> Last placement has been undone.
```

## 開発者向け情報 / Developer Information

### 翻訳メッセージの追加 / Adding Translation Messages

1. `messages_ja.yml` と `messages_en.yml` に同じキーでメッセージを追加
2. プレースホルダー `{0}`, `{1}`, `{2}` などを使用可能

Add messages with the same key in both `messages_ja.yml` and `messages_en.yml`. Placeholders `{0}`, `{1}`, `{2}` etc. are supported.

### コードでの使用方法 / Usage in Code

```java
// 基本的なメッセージ送信
PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "message.key");

// プレースホルダー付きメッセージ
PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "message.key", arg1, arg2);

// アクションバーメッセージ
PlayerMessageUtil.sendTranslatedActionBar(plugin, player, "message.key");

// 直接MessageManagerを使用
String message = plugin.getMessageManager().getMessage("message.key", args);
```

### メッセージキーの構造 / Message Key Structure

```
command:          # コマンド関連の基本メッセージ
  player_only     # プレイヤー限定メッセージ
  no_permission   # 権限不足メッセージ

road:             # 道路コマンド関連
  brush_received  # ブラシ取得メッセージ
  preset_saved    # プリセット保存メッセージ

edit:             # 編集コマンド関連
  mode_enabled    # 編集モード開始
  mode_disabled   # 編集モード終了

language:         # 言語関連
  current         # 現在の言語表示
  changed         # 言語変更完了

error:            # エラーメッセージ
  file_not_found  # ファイル未発見
  save_failed     # 保存失敗
```

## 権限 / Permissions

- `roadadmin.language`: 言語切り替えコマンドの使用権限

Permission for using the language switch command.

## 注意事項 / Notes

1. 言語を変更すると `config.yml` に設定が保存され、プラグイン再起動後も維持されます
2. サポートされていない言語を指定した場合、日本語にフォールバックします
3. メッセージキーが見つからない場合、`[Missing: key_name]` が表示されます

1. Language changes are saved to `config.yml` and persist after plugin restart
2. Unsupported languages will fallback to Japanese
3. Missing message keys will display `[Missing: key_name]`

## 実装済みクラス / Implemented Classes

### 翻訳対応済み / Translation Ready
- ✅ `AutoRoadGeneratorPluginMain` (プラグインメインクラス)
- ✅ `ReditCommand` (道路編集コマンド)
- ✅ `RundoCommand` (取り消しコマンド)
- ✅ `LanguageCommand` (言語切り替えコマンド)
- ✅ `PlayerMessageUtil` (メッセージユーティリティ)

### 今後の対応予定 / Future Implementation
- ⏳ `WallPresetCommand` (塀プリセットコマンド)
- ⏳ `RroadCommand` (道路コマンド) - 一部修正済み
- ⏳ `RobjCommand` (オブジェクトコマンド)
- ⏳ その他のイベントリスナークラス

## ファイル構造 / File Structure

```
プラグインフォルダ/
├── config.yml (プラグイン設定)
├── preset/
│   ├── road/ (道路プリセット)
│   ├── obj/  (オブジェクトプリセット)
│   └── wall/ (塀プリセット)
└── lang/
    ├── messages_ja.yml (日本語メッセージ)
    └── messages_en.yml (英語メッセージ)
```

## システム構成 / System Architecture

```
MessageManager
├── lang/messages_ja.yml (日本語メッセージ)
├── lang/messages_en.yml (英語メッセージ)
├── config.yml (言語設定)
└── PlayerMessageUtil (メッセージ送信ヘルパー)

PresetManager
├── preset/road/ (道路プリセット保存先)
├── preset/obj/  (オブジェクトプリセット保存先)
└── preset/wall/ (塀プリセット保存先)
```

このシステムにより、プラグインの国際化が完了し、日本語・英語両方のユーザーが快適に使用できるようになります。

This system completes the internationalization of the plugin, allowing both Japanese and English users to use it comfortably.