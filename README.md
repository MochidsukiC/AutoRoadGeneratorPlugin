# AutoRoadGeneratorPlugin

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/mochidsuki/AutoRoadGeneratorPlugin)
[![Spigot](https://img.shields.io/badge/spigot-1.20.1-orange.svg)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/java-17-red.svg)](https://openjdk.java.net/projects/jdk/17/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

[English](#english) | [日本語](#japanese)

---

## English

### Description
AutoRoadGeneratorPlugin is an advanced road generation and editing plugin for Minecraft servers. It provides a comprehensive system for creating, editing, and managing road networks with preset support, object placement, and wall construction capabilities.

### Features
- **Advanced Road Generation**: Create complex road networks with smooth curves and intersections
- **Preset System**: Save and reuse road, object, and wall presets
- **Visual Editing**: Interactive route editing with visual feedback
- **Multi-language Support**: Available in English and Japanese
- **Object Placement**: Place objects along roads with customizable parameters
- **Wall Construction**: Build walls along road edges with offset options
- **Undo System**: Undo last placed structures
- **Performance Optimized**: Asynchronous processing for large constructions

### Requirements
- **Minecraft Version**: 1.20.1+
- **Server Software**: Spigot/Paper
- **Java Version**: 17+
- **Permissions**: See [Permissions](#permissions) section

### Installation
1. Download the latest release from [Releases](https://github.com/mochidsuki/AutoRoadGeneratorPlugin/releases)
2. Place the JAR file in your server's `plugins` folder
3. Restart your server
4. Configure permissions as needed

### Quick Start
1. Give yourself a road brush: `/rroad brush`
2. Click two points to create a path
3. Save your road as a preset: `/rroad save <name>`
4. Build the road: `/rroad build <name>`

### Commands

#### Road Commands (`/rroad`, `/rr`)
- `/rroad brush` - Get a road preset creation brush
- `/rroad save <name>` - Save selection as a road preset
- `/rroad paste <name>` - Paste road preset at your location
- `/rroad build <preset> [-onlyair] [--noupdateblockdata]` - Build road along route

#### Object Commands (`/robj`, `/ro`)
- `/robj brush` - Get an object preset creation brush
- `/robj save <name>` - Save selection as an object preset
- `/robj place <preset> [options]` - Place objects along route

#### Wall Commands (`/rwall`, `/rw`)
- `/rwall brush` - Get a wall preset creation brush
- `/rwall save <name>` - Save selection as a wall preset
- `/rwall paste <name>` - Paste wall preset at your location
- `/rwall build <preset> <offset> [-onlyair]` - Build walls along route

#### Editing Commands (`/redit`, `/re`)
- `/redit` - Toggle road path editing mode
- `/redit brush` - Get a road editing brush

#### Utility Commands
- `/rundo` - Undo last placement
- `/lang [ja|en]` - Change plugin language

### Permissions
- `autoroadgen.*` - All permissions (default: op)
- `autoroadgen.road` - Road commands (default: true)
- `autoroadgen.object` - Object commands (default: true)
- `autoroadgen.wall` - Wall commands (default: true)
- `autoroadgen.edit` - Edit commands (default: true)
- `autoroadgen.undo` - Undo command (default: true)
- `autoroadgen.language` - Language command (default: true)
- `autoroadgen.admin` - Administrative functions (default: op)

### Configuration
The plugin creates a `config.yml` file with the following options:
```yaml
language: ja  # Default language (ja/en)
```

### Building from Source
```bash
git clone https://github.com/mochidsuki/AutoRoadGeneratorPlugin.git
cd AutoRoadGeneratorPlugin
mvn clean package
```
The compiled JAR will be in the `target` folder.

### Contributing
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

### Support
- **Issues**: [GitHub Issues](https://github.com/mochidsuki/AutoRoadGeneratorPlugin/issues)
- **Documentation**: See [TRANSLATION_README.md](TRANSLATION_README.md)

---

## Japanese

### 概要
AutoRoadGeneratorPluginは、Minecraftサーバー向けの高度な道路生成・編集プラグインです。プリセットシステム、オブジェクト配置、壁建設機能を備えた、包括的な道路ネットワーク作成・管理システムを提供します。

### 機能
- **高度な道路生成**: 滑らかなカーブと交差点を持つ複雑な道路ネットワークの作成
- **プリセットシステム**: 道路、オブジェクト、壁のプリセットの保存・再利用
- **ビジュアル編集**: 視覚的フィードバック付きのインタラクティブルート編集
- **多言語サポート**: 英語・日本語対応
- **オブジェクト配置**: カスタマイズ可能なパラメータでの道路沿いオブジェクト配置
- **壁建設**: オフセットオプション付きの道路端壁建設
- **取り消しシステム**: 最後に配置した構造物の取り消し
- **パフォーマンス最適化**: 大規模建設のための非同期処理

### 動作要件
- **Minecraftバージョン**: 1.20.1+
- **サーバーソフトウェア**: Spigot/Paper
- **Javaバージョン**: 17+
- **権限**: [権限](#権限)セクションを参照

### インストール
1. [Releases](https://github.com/mochidsuki/AutoRoadGeneratorPlugin/releases)から最新版をダウンロード
2. JARファイルをサーバーの`plugins`フォルダに配置
3. サーバーを再起動
4. 必要に応じて権限を設定

### クイックスタート
1. 道路ブラシを取得: `/rroad brush`
2. 2点をクリックしてパスを作成
3. 道路をプリセットとして保存: `/rroad save <名前>`
4. 道路を建設: `/rroad build <名前>`

### コマンド

#### 道路コマンド (`/rroad`, `/rr`)
- `/rroad brush` - 道路プリセット作成用ブラシを取得
- `/rroad save <名前>` - 選択範囲を道路プリセットとして保存
- `/rroad paste <名前>` - 足元に道路プリセットを配置
- `/rroad build <プリセット> [-onlyair] [--noupdateblockdata]` - ルートに沿って道路を建設

#### オブジェクトコマンド (`/robj`, `/ro`)
- `/robj brush` - オブジェクトプリセット作成用ブラシを取得
- `/robj save <名前>` - 選択範囲をオブジェクトプリセットとして保存
- `/robj place <プリセット> [オプション]` - ルートに沿ってオブジェクトを配置

#### 壁コマンド (`/rwall`, `/rw`)
- `/rwall brush` - 壁プリセット作成用ブラシを取得
- `/rwall save <名前>` - 選択範囲を壁プリセットとして保存
- `/rwall paste <名前>` - 足元に壁プリセットを配置
- `/rwall build <プリセット> <オフセット> [-onlyair]` - ルートに沿って壁を建設

#### 編集コマンド (`/redit`, `/re`)
- `/redit` - 道路パス編集モードの切り替え
- `/redit brush` - 道路編集ブラシを取得

#### ユーティリティコマンド
- `/rundo` - 最後の配置を取り消し
- `/lang [ja|en]` - プラグイン言語を変更

### 権限
- `autoroadgen.*` - 全権限（デフォルト: op）
- `autoroadgen.road` - 道路コマンド（デフォルト: true）
- `autoroadgen.object` - オブジェクトコマンド（デフォルト: true）
- `autoroadgen.wall` - 壁コマンド（デフォルト: true）
- `autoroadgen.edit` - 編集コマンド（デフォルト: true）
- `autoroadgen.undo` - 取り消しコマンド（デフォルト: true）
- `autoroadgen.language` - 言語コマンド（デフォルト: true）
- `autoroadgen.admin` - 管理機能（デフォルト: op）

### 設定
プラグインは以下のオプションを含む`config.yml`ファイルを作成します：
```yaml
language: ja  # デフォルト言語 (ja/en)
```

### ソースからのビルド
```bash
git clone https://github.com/mochidsuki/AutoRoadGeneratorPlugin.git
cd AutoRoadGeneratorPlugin
mvn clean package
```
コンパイルされたJARは`target`フォルダにあります。

### 貢献
1. リポジトリをフォーク
2. 機能ブランチを作成
3. 変更を実装
4. 十分にテスト
5. プルリクエストを送信

### サポート
- **問題報告**: [GitHub Issues](https://github.com/mochidsuki/AutoRoadGeneratorPlugin/issues)
- **ドキュメント**: [TRANSLATION_README.md](TRANSLATION_README.md)を参照

---

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Author
- **Mochidsuki** - [GitHub Profile](https://github.com/mochidsuki)