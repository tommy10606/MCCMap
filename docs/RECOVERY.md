# Scan and render recovery

MCCMap checkpoints long-running map work every 60 seconds by default and on clean
shutdown. A crash resumes from the latest successful checkpoint, so a small tail
of work may repeat. It does not promise that every last operation survives power
loss or a filesystem failure.

```yaml
progress-checkpoints:
  enabled: true
  interval-seconds: 60
```

The interval accepts 5–3600 seconds. This setting replaces the old
`saverestorepending` and `save-pending-period` settings. Recovery is enabled by
default, including on existing installations.

| Work | Saved state |
| --- | --- |
| Full, radius and bulk update renders | World/map layout, owner, radius, completed-map flags, render frontier, current batch and resume-file scan position. |
| Render completion inventory | Generated-region directory cursor, remaining chunks to project and unique projected base-tile totals. ETA samples restart fresh. |
| Natural background rendering | Independent full-pass frontier, native-file resume cursor, completion inventory, completed layout ledger, service enabled state and cancelled-world suspensions. Saved under its own private checkpoint key. |
| Normal tile updates | Queued and executing tiles, delayed invalidations, queued block and volume changes. Taking a snapshot does not advance live update timers. |
| Zoom renders | Both current and accumulated zoom queues; late changes are saved after workers stop. |
| Stale refresh | World/map/variant and directory position, bounded pending jobs, an executing job, next scan time and cooldown. Restored work stays in the 60-day/10% refresher. |
| Missing-tile checks | Region-header cursor, remaining chunks to project, expected tiles still to check, and repair queues. |
| Initial zoom validation | Tile metadata cursor and accumulated zoom invalidations. |
| Full-render tile cleanup | Tile metadata cursor and zoom side effects; the containing render frontier also survives shutdown. |
| Storage inventory | Root/directory cursor and partial counts. Restored counts receive a fresh validation pass before they can authorize capped rendering. |

Full/radius renders continue to use the current player-count policy (125% of the
original baseline when empty, 50% when populated). Stale work is never promoted
into the normal-speed queue on recovery. Startup delay, TPS, storage and manual
pause checks remain in force. Workers wait for worlds to activate before restoring
world-dependent scan state.

Natural jobs recover into their separate queue, using 25% of the original baseline
when populated and the configured empty-server full-render speed otherwise. They
yield to manual renders and queued, executing or delayed normal updates. Their
final shutdown snapshot is frozen before cancellation so late worker cleanup
cannot replace the saved frontier. Completed or explicitly suspended layouts are
not automatically restarted.

Checkpoint files are private, compressed JSON under `plugins/MCCMap/progress`,
with atomic replacement and a 64 MiB decoded size bound per file. They contain data,
not serialized Java objects. Paths and links are validated; HTTP requests for
checkpoint and legacy pending files are blocked. A failed save retains the prior
checkpoint. Corrupt or incompatible checkpoints are rejected rather than applying
old coordinates to a changed map layout. Scanners can start a new safe pass; an
unrecoverable manual job is logged rather than guessed.

Back up `progress` and `tile-refresh-history` along with the map and configuration.
Existing compatible `.pending` files are imported when no newer world checkpoint
exists, then removed only after a successful new save. Removing checkpoints does
not delete map tiles, but it loses unfinished manual jobs and scan positions.
Completed tile render dates live in `tile-refresh-history` separately.

Directory scans acknowledge work after processing it. An interrupted entry can be
replayed. Files deleted while offline are skipped safely; files added before the
saved position are picked up in a subsequent scan. Layout changes can require a
fresh pass.

Filesystem free-space measurements still run fresh at startup and every 24 hours.
A failed free-space measurement still waits until the next boot before retrying.
Saved inventory totals never bypass the disk reserve or storage-cap protections.
Startup model/texture registries and live external-plugin snapshots are rebuilt
from current inputs, rather than restoring stale runtime objects.
