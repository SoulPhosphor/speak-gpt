# Offline self-test demo (SYNTHETIC recordings — NOT model evidence)

Runner: OFFLINE (recorded replay) · Token estimator: heuristic(~4 chars/token) · Configs: 2 · Fixtures: 17

## Quality

| Config | Recall | Precision | Gen found | Comp found | Gen missed | Comp missed | Placement err | Stream leak | Invented | Over-extract |
|---|---|---|---|---|---|---|---|---|---|---|
| broad__general+companion+model_rules__t8k__cheap__plain_json | 1.0 | 0.6863 | 27 | 8 | 0 | 0 | 0 | 0 | 16 | 0 |
| conservative__general+companion+model_rules__t8k__cheap__plain_json | 0.6286 | 1.0 | 18 | 4 | 9 | 4 | 0 | 0 | 0 | 0 |

## Rules, types, integrity

| Config | Target err | Invalid type | Dupes | Rules ok | Rules noisy | DNA viol | Unreadable | Dropped |
|---|---|---|---|---|---|---|---|---|
| broad__general+companion+model_rules__t8k__cheap__plain_json | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 |
| conservative__general+companion+model_rules__t8k__cheap__plain_json | 0 | 0 | 0 | 2 | 0 | 0 | 1 | 0 |

## Cost & operations

| Config | Reqs | In tok | Out tok | Latency ms | Cost $ |
|---|---|---|---|---|---|
| broad__general+companion+model_rules__t8k__cheap__plain_json | 16 | 19200 | 3520 | 14400 | 0.004992 |
| conservative__general+companion+model_rules__t8k__cheap__plain_json | 16 | 19200 | 3520 | 14400 | 0.004992 |

