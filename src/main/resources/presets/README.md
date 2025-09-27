# Sample Presets / サンプルプリセット

This directory contains sample presets to help you get started with AutoRoadGeneratorPlugin.
このディレクトリには、AutoRoadGeneratorPluginを始めるためのサンプルプリセットが含まれています。

## Road Presets / 道路プリセット

### basic_stone_road.yml
- **Description**: A simple 3-block wide stone road perfect for beginners
- **Use**: `/rroad build basic_stone_road`
- **説明**: 初心者にぴったりな3ブロック幅のシンプルな石の道路
- **使用法**: `/rroad build basic_stone_road`

### cobblestone_path.yml
- **Description**: A medieval-style cobblestone path with mossy borders
- **Use**: `/rroad build cobblestone_path`
- **説明**: 苔むした境界線のある中世風の丸石の小道
- **使用法**: `/rroad build cobblestone_path`

### modern_concrete_road.yml
- **Description**: A modern road with concrete and yellow center line
- **Use**: `/rroad build modern_concrete_road`
- **説明**: コンクリートと黄色い中央線のある現代的な道路
- **使用法**: `/rroad build modern_concrete_road`

## Object Presets / オブジェクトプリセット

### street_lamp.yml
- **Description**: A simple street lamp for road lighting
- **Use**: `/robj place street_lamp --interval 20`
- **説明**: 道路照明用のシンプルな街灯
- **使用法**: `/robj place street_lamp --interval 20`

### road_sign.yml
- **Description**: A basic road sign with wooden post
- **Use**: `/robj place road_sign --interval 50`
- **説明**: 木製ポスト付きの基本的な道路標識
- **使用法**: `/robj place road_sign --interval 50`

## Wall Presets / 壁プリセット

### stone_barrier.yml
- **Description**: A stone wall barrier for road safety
- **Use**: `/rwall build stone_barrier 3`
- **説明**: 道路安全のための石の壁バリア
- **使用法**: `/rwall build stone_barrier 3`

### wooden_fence.yml
- **Description**: A decorative wooden fence for park roads
- **Use**: `/rwall build wooden_fence 2`
- **説明**: 公園道路用の装飾的な木製フェンス
- **使用法**: `/rwall build wooden_fence 2`

## How to Use / 使用方法

1. Create a route with `/redit` command / `/redit`コマンドでルートを作成
2. Build roads with preset names / プリセット名で道路を建設
3. Add objects and walls as needed / 必要に応じてオブジェクトと壁を追加

## Tips / ヒント

- Use `-onlyair` flag to avoid replacing existing blocks / 既存ブロックを置き換えないように`-onlyair`フラグを使用
- Adjust intervals for objects based on your needs / オブジェクトの間隔は必要に応じて調整
- Experiment with different offset values for walls / 壁のオフセット値を試してみてください

For more information, see the main README.md file.
詳細については、メインのREADME.mdファイルを参照してください。