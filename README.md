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
| `ASR_NORMALIZE_SILENCE_DBFS` | `-60` | 正規化で、RMS がこの値(dBFS)未満の音声は増幅しない閾値。`ASR_NORMALIZE_ENGINES` のエンジンにだけ効きます |
| `ASR_DECODING_METHOD` | `greedy_search` | 全エンジンの既定のデコード方式(`greedy_search`、`modified_beam_search`) |
| `ASR_DECODING_METHOD_BY_ENGINE` | (空) | エンジン別のデコード方式。`name:method` のカンマ区切りで、例は `reazon-ja:modified_beam_search`。指定のないエンジンは `ASR_DECODING_METHOD` を使います |
| `ASR_MAX_ACTIVE_PATHS` | `4` | `modified_beam_search` のビーム幅(1 以上)。`greedy_search` では使われません |
| `ASR_BLANK_PENALTY_BY_ENGINE` | (空) | エンジン別の blank penalty。`name:value` のカンマ区切りで、`value` は有限の 0 以上の数、例は `reazon-ja:1.0`。指定のないエンジンは 0(blank penalty なし)です |
| `ASR_MIN_PEAK` | `0.05` | 発話全体の最大振幅(絶対値、0 以上 1 以下)がこの値未満の発話は、認識せずに破棄して投稿しません。`0` で無効です |
| `ASR_MIN_AVG_LOG_PROB_BY_ENGINE` | (空) | エンジン別の、確信度のしきい値(平均対数確率)。`name:value` のカンマ区切りで、`value` は有限の 0 以下の数、例は `reazon-ja:-0.5`。しきい値を下回る出力は、そのエンジンの結果だけ破棄します。空なら無効です |
| `ASR_SENSEVOICE_ITN` | `true` | sensevoice の逆テキスト正規化(`true`、`false`)。`false` にすると句読点が付かなくなります。他のエンジンには影響しません |
| `PARTIAL_INTERVAL_MS` | `2000` | partial 認識の間隔 |
| `MAX_UTTERANCE_MS` | `30000` | この長さで発話を確定し、続きを新しい発話として扱います |
| `VAD_THRESHOLD` | `0.5` | Silero の発話確率のしきい値 |
| `VAD_START_MS` / `VAD_END_SILENCE_MS` / `VAD_PREROLL_MS` | `96` / `1000` / `320` | 発話開始とみなす連続長、発話終了とみなす無音長、開始検出より前に残しておく音声の長さ |
| `MIN_UTTERANCE_MS` | `300` | これより短い発話は捨てます |
| `AUDIO_TTL_MIN` / `FAILED_AUDIO_TTL_MIN` | `30` / `1440` | すべての final が確定してから、音声を削除するまでの時間 |
| `RETRY_MAX` / `RETRY_BACKOFF_MS` | `5` / `2000` | final ジョブの再試行回数と、指数バックオフの基準時間 |

ASR に渡す前の音声には、正規化、パディングの順に処理を適用します。partial と final のどちらにも同じ処理がかかります。RMS が `ASR_NORMALIZE_SILENCE_DBFS`(既定 -60 dBFS)未満の音声は、ノイズから誤った文字が出るのを避けるため、正規化しても増幅しません。

`modified_beam_search` が使えるのは transducer 系の `reazon-ja` と `reazon-ja-en` だけです。それ以外のエンジンに指定すると、起動時に拒否されます(sherpa-onnx は未対応の値でプロセスごと終了するため)。複数のエンジンを同時に動かすときは、`ASR_DECODING_METHOD_BY_ENGINE` でエンジンを指定してください。精度と CPU 負荷への効果は検証していないので、既定のまま使い、変えるときは replay で同じ発話を比べてから決めてください。replay はデコード方式を条件として振れないので、環境変数を変えて `--out` を分けて 2 回実行し、結果を比べます。出力にはデコード方式が記録されません。

blank penalty は、transducer 系の `reazon-ja` と `reazon-ja-en` だけで使えます。それ以外のエンジンに 0 より大きい値を指定すると、黙って無視されるのを避けるため、起動時に拒否されます。発話の先頭や末尾の語が落ちるときに、`ASR_PAD_MS_BY_ENGINE` を増やす方法と併用すると改善する場合があります。効果は音声や sherpa-onnx のバージョンで変わるので、変えるときは replay で、環境変数を変えて `--out` を分けて 2 回実行し、同じ発話を比べてから決めてください。blank penalty を変えると、確信度(認識結果に付く `(nnn%)`)も変わります(試した例では少し下がりました)。設定の異なる確信度の値を、単純に比べないでください。

ほぼ無音の発話は、ASR が幻覚の文字列を出すので、投稿する前に破棄します。最大振幅は、正規化の前の音声で測ります。確信度の判定は、`ASR_MIN_AVG_LOG_PROB_BY_ENGINE` に挙げたエンジンの出力だけに働き、発話全体は破棄しません。確信度が得られないエンジンには効きません。確信度の値は blank penalty や `modified_beam_search` で変わるので、しきい値を決めるときは replay で同じ発話を比べてください。replay も同じ判定を適用します。ただし replay の `empty` 列は、破棄された発話と、ASR がもともと空文字を返した発話を区別せずに数えます。破棄された発話の元のテキストを見るときは、`ASR_MIN_PEAK=0` で実行し、`ASR_MIN_AVG_LOG_PROB_BY_ENGINE` を設定しているときは、これも空にして実行してください。

`modified_beam_search` では、`reazon-ja` と `reazon-ja-en` の確信度(認識結果に付く `(nnn%)`)が `greedy_search` と同じ意味にならず、同じ認識結果でも値が大きく変わることを確認しています。ビーム探索で確信度を使うときは、`greedy_search` の値と比べないでください。

### 保存済みの発話を再認識して比較する(replay)

パディングや音量正規化の設定を変えたとき、同じ発話を条件違いで認識し直して、結果の差を見るためのツールです。アプリの JAR に `replay` サブコマンドとして入っています。音声を新しく保存する仕組みはありません。`audio/` に残っている発話を使います。

準備:

- `AUDIO_TTL_MIN` を延ばして、比較したい期間の音声を `audio/` に残します。延ばしても、音声が増える分だけディスクを使います
- 本番に影響しないよう、`audio/` と、`queue.db`・`queue.db-wal`・`queue.db-shm` をまとめて別の場所にコピーします。アプリを止めてから `queue.db` だけをコピーしてもかまいません。`queue.db` を渡すと、保存時の認識結果(`saved/<engine>`)も比較に入ります

実行例(コンテナの `/data` 以下を指します。`--out` を `/data` の外にすると、実行後に結果が消えます):

```sh
docker compose run --rm koemoji replay \
  --audio-dir /data/audio --queue-db /data/queue.db \
  --engines sensevoice,reazon-ja --pad-ms 0,300,600 --normalize off,on \
  --out /data/replay-out
```

| 引数 | 必須 | 説明 |
|---|---|---|
| `--audio-dir` | ○ | 発話ファイル(`<utterance_id>.pcm`)のあるディレクトリ |
| `--engines` | ○ | 再認識するエンジン(カンマ区切り) |
| `--queue-db` | | `queue.db`。指定すると、対象の発話と読み込むバイト数を `queue.db` から決め、保存時の認識結果も比較します。省略すると、`--audio-dir` の `*.pcm` 全体を対象にします |
| `--reference` | | 正解の文字起こし。UTF-8 で、1 行に `utterance_id`、TAB、本文の順に書きます。指定すると CER を出します |
| `--pad-ms` | | 前後に足す無音の長さ(ミリ秒)の候補。カンマ区切り。省略すると、エンジンごとの環境設定(`ASR_PAD_MS`、`ASR_PAD_MS_BY_ENGINE`)を使います |
| `--normalize` | | 音量正規化の候補(`off`、`on`)。カンマ区切り。省略すると、エンジンごとの環境設定(`ASR_NORMALIZE_ENGINES`)を使います |
| `--limit` | | 先頭から数えて、この件数の発話だけを対象にします |
| `--top` | | 不一致の大きい発話を何件載せるか(既定 `20`) |
| `--out` | | 結果の出力先(既定 `replay-out`)。存在しないか、空のディレクトリだけ指定できます |

条件は、エンジン、`--pad-ms`、`--normalize` のすべての組み合わせです(`<engine>/pad<ms>/norm<on|off>`)。`--queue-db` を指定したときは、保存時の認識結果(`saved/<engine>`)も条件に加わります。

出力:

| ファイル | 内容 |
|---|---|
| `summary.md` | 条件ごとの件数、空の結果の件数、文字数、CER、条件の対ごとの平均不一致率、不一致の大きい発話の一覧。文字起こしの本文は含みません |
| `utterances.tsv` | 発話ごと・条件ごとの認識結果 |
| `disagreements.tsv` | 不一致の大きい発話の、全条件の認識結果 |

不一致率は、2 つの結果を NFKC で正規化し、句読点・記号・空白を除いたうえで、コードポイント単位のレーベンシュタイン距離を長い方の長さで割った値です。条件の対の平均は、両方の結果がある発話について取ります。

`utterances.tsv` と `disagreements.tsv` には会話の本文が入ります。リポジトリ、Issue、PR には載せず、集計の `summary.md` だけを共有してください。

稼働中の本番と同じマシンで実行すると、エンジンのモデルをもう 1 つ読み込むぶんのメモリが必要です。

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
