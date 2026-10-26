# koemoji

Discord のボイスチャンネル文字起こし bot です。参加者ごとの発話をローカルの CPU だけで認識し(クラウドの ASR は使いません)、話している最中は約 2 秒ごとに更新しながらテキストチャンネルへ投稿します。

## 動作環境

- Docker (Compose)。linux/amd64 のみ対応です。ネイティブライブラリと Docker イメージが amd64 向けのため、ARM では動きません。
- 音声認識は CPU だけで動きます。GPU は使いません。

## Bot の準備

1. [Discord Developer Portal](https://discord.com/developers/applications) でアプリケーションを作り、Bot のトークンを取得します。
2. Privileged Gateway Intents は、すべてオフのままで動きます。bot が使う Intent は `GUILD_VOICE_STATES` だけです。
3. OAuth2 の URL Generator で、スコープに `bot` と `applications.commands` を選び、サーバーへ招待します。
4. 権限は、文字起こしを投稿するテキストチャンネルで「チャンネルを見る」と「メッセージを送信」、対象のボイスチャンネルで「接続」が必要です。

## 使い方

```sh
umask 077
printf 'DISCORD_TOKEN=%s\nUPDATE_CONTROL_SECRET=%s\n' '取得した Bot token' "$(openssl rand -hex 32)" > .env
docker compose up -d --build
```

モデルは初回起動時に `./data/models` へ自動ダウンロードされ、キャッシュされます。事前に全部取得したい場合は `scripts/download-models.sh` を使います。

Discord 上で、文字起こしを投稿したいテキストチャンネルで `/register` を一度実行します(`channel` で投稿先を指定することもできます)。`engine` を選ぶと、その ASR エンジンだけ別のチャンネルへ投稿できます。engine を省略した `/register` は従来どおり default の投稿先を設定し、個別設定のない engine は default を使います。

```text
/register channel:#koemoji-transcripts
/register engine:sensevoice channel:#koemoji-sensevoice
/register engine:reazon-ja channel:#koemoji-reazon-ja
```

`/unregister engine:<engine>` はその engine の個別設定だけを解除します。default が設定されていれば、その engine は default へ戻ります。engine を省略した `/unregister` は全設定を解除して bot を退出させます。engine 固有設定だけが残っている場合も自動参加します。投稿先がない engine の結果は破棄されます。

選択できる engine は `ASR_ENGINES` に設定したものです。登録時には bot が対象チャンネルを閲覧でき、メッセージを送信できる必要があります。

- **自動参加**: 登録済みサーバーで bot がどの VC にもいないとき、人間が VC に入る(AFK チャンネルを除く)と bot も参加します。
- **自動移動**: 人間が bot のいるチャンネルから、より人間の多いチャンネルへ移ると bot も追従します。
- **自動退出**: bot のいるチャンネルから人間がいなくなる(最後の 1 人が AFK へ移った場合を含む)と bot も退出します。
- `/join [channel]` と `/leave` で手動操作もできます。`/unregister` でそのサーバーの機能をすべて無効にします。

コマンドはいずれも「サーバーの管理」権限が必要です。未登録のサーバーには参加も録音もしません。

投稿は既定で `` `ユーザー名`: `発言` `` の形式です。Reazon transducer (`reazon-ja`、`reazon-ja-en`) でスコアを取得できる場合は、投稿末尾に `(nnn%)` が付きます。この値は認識 token の平均確率から計算した参考スコアで、発話が正しい確率ではありません。他の engine やスコアを返さない結果には付きません。同じ投稿先 channel を共有する複数の engine は、`MESSAGE_FORMAT` に `{engine}` がなければ、区別できるよう先頭に `[engine 名]` が付きます。engine ごとに別 channel へ投稿する場合は channel で区別できるため、自動の engine 名は付きません。`MESSAGE_FORMAT` に `{engine}` を指定すると、別 channel への投稿でも設定した位置に engine 名を表示できます。本番運用では engine を 1 つにしてください。

同じユーザーの発話が 1.2 秒以内に続いた場合は、同じ engine と投稿先 channel の範囲で 1 つのメッセージにまとめます。話者、engine、投稿先 channel が異なる発話はまとめません。結合後のメッセージが Discord の 2,000 文字上限を超える場合は別メッセージに分けます。

## 設定 (環境変数)

| 変数 | 既定値 | 説明 |
|---|---|---|
| `DISCORD_TOKEN` | (必須) | Bot のトークン |
| `UPDATE_CONTROL_SECRET` | (空) | updater とアプリ間の更新制御 API 認証に使う共有 secret。空の場合は自動更新を無効にします |
| `MODE` | `all` | `all`、`capture`、`worker` のいずれか。分けるとワーカーだけをスケールできます。両者は `QUEUE_DB` と `AUDIO_DIR` を共有します |
| `ASR_ENGINES` | `sensevoice` | すべての発話を送るエンジン(カンマ区切り) |
| `MESSAGE_FORMAT` | `` `{user}`: `{text}` `` | 投稿メッセージの書式。`{user}`、`{text}`、`{engine}` が使えます。同じ投稿先 channel を使う engine が複数あり、書式に `{engine}` が無い場合は先頭に `[engine 名] ` が付きます |
| `WORKER_ENGINES` | `ASR_ENGINES` と同じ | 1 エントリにつきワーカースレッドを 1 つ起動します。`name:N` で N 個 |
| `ASR_THREADS` | `4` | ワーカー 1 つあたりの ONNX スレッド数 |
| `ASR_LANGUAGE` | `ja` | SenseVoice / Whisper への言語ヒント(空なら自動判定) |
| `INCLUDE_BOTS` | `true` | 他の bot の音声も文字起こしする |
| `HEALTH_PORT` | `8080` | `/health` と `/metrics` の HTTP ポート(`0` で無効。無効のときは Docker の `HEALTHCHECK` も常に成功します) |
| `MODELS_DIR`、`VAD_MODEL`、`AUDIO_DIR`、`QUEUE_DB` | `Config.java` 参照 | 各種パス。Docker イメージでは `/data` 以下に設定済みです |

エンジン: `sensevoice`、`reazon-ja`、`reazon-ja-en`、`qwen3-asr`、`whisper-small`、`whisper-turbo`、`parakeet-ja`、`dolphin-small`(`SherpaEngine.DIRS` 参照)。

### 発話検出・認識の調整

通常は変更不要です。

| 変数 | 既定値 | 説明 |
|---|---|---|
| `ASR_PAD_MS` | `0` | ASR に渡す音声の前後に足す無音の長さ。全エンジンの既定で、エンジン別に変えるには `ASR_PAD_MS_BY_ENGINE` を使います |
| `ASR_PAD_MS_BY_ENGINE` | (空) | エンジン別のパディング(ミリ秒)。`name:ms` のカンマ区切りで、例は `sensevoice:0,reazon-ja:600`。指定のないエンジンは `ASR_PAD_MS` を使います |
| `ASR_NORMALIZE_ENGINES` | (空) | 音量正規化を有効にするエンジン(カンマ区切り)。空なら全エンジンで無効です |
| `ASR_NORMALIZE_TARGET_DBFS` | `-20` | 正規化の目標 RMS(dBFS) |
| `ASR_NORMALIZE_MAX_GAIN_DB` | `20` | 正規化で増幅するゲインの上限(dB)。減衰には効きません |
| `ASR_NORMALIZE_PEAK` | `0.99` | 正規化後のピーク(絶対値)の上限 |
| `PARTIAL_INTERVAL_MS` | `2000` | partial 認識の間隔 |
| `MAX_UTTERANCE_MS` | `30000` | この長さで発話を確定し、続きを新しい発話として扱います |
| `VAD_THRESHOLD` | `0.5` | Silero の発話確率のしきい値 |
| `VAD_START_MS` / `VAD_END_SILENCE_MS` / `VAD_PREROLL_MS` | `96` / `1000` / `320` | 発話開始とみなす連続長、発話終了とみなす無音長、開始検出より前に残しておく音声の長さ |
| `MIN_UTTERANCE_MS` | `300` | これより短い発話は捨てます |
| `AUDIO_TTL_MIN` / `FAILED_AUDIO_TTL_MIN` | `30` / `1440` | すべての final が確定してから、音声を削除するまでの時間 |
| `RETRY_MAX` / `RETRY_BACKOFF_MS` | `5` / `2000` | final ジョブの再試行回数と、指数バックオフの基準時間 |

ASR に渡す前の音声には、正規化、パディングの順に処理を適用します。partial と final のどちらにも同じ処理がかかります。RMS が -60 dBFS 未満の音声は、ノイズから誤った文字が出るのを避けるため、正規化しても増幅しません。

## データの置き場

永続化するものはすべて `./data`(コンテナ内の `/data`)に置きます。

| パス | 内容 | 消したとき |
|---|---|---|
| `models/` | 音声認識と VAD のモデル | 次回起動時に再ダウンロードされます |
| `audio/` | 認識待ちの発話ファイル | `AUDIO_TTL_MIN` 経過後に自動で削除されます。手動で消すと、処理中の発話は失われます |
| `queue.db` | ジョブキューと、`/register` の登録内容 | 登録が消えるので、各サーバーで `/register` のやり直しが必要です |

## 旧版からの移行

旧版の `config.json` と `servers.json` は読み込みません。`DISCORD_TOKEN` を環境変数に設定し、各サーバーで `/register` をやり直してください。

## 制限事項

- 1 サーバーで同時に参加できるボイスチャンネルは 1 つです。

## 運用

- `/health` は、Discord に接続済み(capture)かつ、設定した全ワーカースレッドが生存(worker)のときだけ 200 を返します。Docker の `HEALTHCHECK` がこれを呼びます。
- `/metrics` は Prometheus 形式で、`koemoji_jobs{engine,status}`(キューの深さと結果)、`koemoji_workers_alive`、エンジンごとの認識回数と所要時間を返します。
- 起動時に、未知のエンジン名は即エラーになります。`MODE=all` では、ワーカーのないエンジンもエラーです。`MODE=capture` ではワーカー側が分からないため、ワーカーコンテナの `WORKER_ENGINES` が `ASR_ENGINES` を網羅するようにしてください。
- ユーザー別の音声パイプライン(VAD)は、音声が 5 分途切れると解放されます。
- `updater` は GitHub Releases の stable version を 10 分ごとに確認し、latest tag を使うアプリ service だけを更新します。version 固定や digest 固定の service は対象外です。更新前に音声・キュー・Discord 投稿を drain し、更新後の health check に失敗した場合は直前の image に戻します。drain が 60 秒以内に完了しない場合は更新を中止します。
- 発話の final enqueue に失敗した場合もコンテナを置換せず、プロセスが稼働している間は自動更新を停止します。DB の状態とログを確認してから koemoji コンテナを再起動すると、更新制御が再び有効になります。
- updater は Docker socket と Compose 設定を読み取り専用で mount します。Docker socket はホスト上の root 相当の操作権限を与え、Compose 設定には `.env` の Discord token が含まれます。信頼できる管理者だけがホストと Compose 設定を変更できる環境で実行してください。`UPDATE_CONTROL_SECRET` は推測困難な値を `.env` に保存し、ファイル権限を制限してください。

## 開発

```sh
sudo apt-get install build-essential cmake git
scripts/install-sherpa.sh   # 初回と、既存環境をこの変更へ更新した後に実行: token score 対応の Java/JNI binding を ~/.m2 に入れます
mvn test      # PipelineTest は data/models に sensevoice と test_silero_vad.wav、UserPipelineTest は silero_vad.onnx と test_silero_vad.wav があるときだけ実行されます(wav は音声を含む 16 kHz モノラル 16-bit の WAV を自分で置きます)
mvn package   # target/koemoji.jar
```

設計上の要点:

- partial はスナップショットです。ジョブは、伸び続ける音声ファイルの先頭 `snapshot_bytes` だけを読むので、追記が並行しても、各リビジョンの見る内容は変わりません。
- 発話のキュー済み partial は、より新しいジョブに置き換えられます。final は捨てません。表示するのは最新のリビジョンだけで、遅れて届いた古い結果は無視します。
- オフラインモデルは partial のたびに発話全体を再認識するため、30 秒の発話では、ASR の所要時間が発話の長さの約 8 倍になります。

コンテナは意図的に root で動かしています。rootless Docker では、非 root ユーザーがバインドマウントした `./data` に書き込めないためです。
