# Acceptance Tests

## T08 Single-Target Game Recovery

This is the strictly authorized acceptance driver for the T08 Game Recovery feature.

### Usage

```bash
python tools/acceptance/run_t08_game_recovery.py --mode [converted|move] --run-root path/to/run-root --allow-game-write SINGLE_BACKED_UP_TARGET
```

### Input Contract

The `run-root` must contain exactly:
* `game-fixture.json`: Target specification
* `original-game-data`: Byte-exact content for the previous target state
* `replacement-game-data`: Byte-exact content for the new target state

### `game-fixture.json` format

```json
{
  "bundleName": "<32-character lowercase hex bundle name>",
  "hashDir": "<32-character lowercase hex hash directory name>",
  "expectedAppAndroidId": "<16-character lowercase hex android_id>",
  "oldSha256": "<SHA256 of original-game-data>",
  "newSha256": "<SHA256 of replacement-game-data>",
  "oldBytes": "<integer size of original-game-data>",
  "newBytes": "<integer size of replacement-game-data>",
  "remote": "/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/<bundleName>/<hashDir>/__data"
}
```

### Requirements and Operational Constraints

* Python >= 3.9 (must not run under optimized Python `-O`).
* Requires the whitelist root shell, a debug app (`com.bd2toolsbox`), and the androidTest APK (`com.bd2toolsbox.test`) already installed on the target device.
* Input backups are mandatory before execution.
* This tool does not install an APK and does not commit.
* Artifacts and logs are retained under the run root.
* Failure and rollback can still require manual recovery; do not promise that no side effect can escape.

