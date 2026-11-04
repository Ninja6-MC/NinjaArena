# Plugin compatibility audit

**Code review** means the result follows from NinjaArena and companion plugin
source or published behavior. **Recorded live** means it was reported in the
[first test-server install notes][test], on Paper 26.3 build 175 on 27 October
2026. This audit adds no new live verification.

## World ownership and teleports

### AntiSpeedrun dimension gates

**Code review and recorded live.** NinjaArena creates its void world with
`World.Environment.NORMAL`. AntiSpeedrun's dimension gates classify the
destination by environment and do not gate entry into a normal world. The test
server reported the same classification. Leave dimension gates enabled. A
restore whose destination is a gated Nether or End world is a separate path.
Test that return with the installed AntiSpeedrun settings before relying on it.

### World-reset plugins

**Code review.** CyberWorldReset documents manual and scheduled world
replacement. NinjaArena owns the load, idle unload and region reset lifecycle
of `ninjaarena_arenas`, rebuilding from its saved arena copies. A resetter
can operate on the files even while the world is unloaded. Treat the world as
immutable to other plugins. Keep it out of managed-world lists, reset schedules
and manual reset or backup-restore targets, including tools that select all
worlds. Allowlist the disposable worlds instead. A resetter that cannot
exclude this world is unsuitable on the same backend. See [arena world
retirement][lifecycle] and [CyberWorldReset][world-reset].

### Multiverse-Core 5 and NetherPortals

**Code review and recorded live.** Multiverse can import and manage existing
worlds. The test server reported no interaction with NinjaArena's plugin-created
world or region portals. Keep `ninjaarena_arenas` unimported and out of spawn,
portal link and teleport targets managed by Multiverse. NinjaArena handles its
own entry and exit. See [Multiverse world management][multiverse].

### EssentialsX flight

**Code review and recorded live.** The default `world-change-fly-reset: true`
clears flight on world change. An earlier `/narena arena tp` dropped an admin
into the void ([#59][flight]). The command now enables hover after the
teleport succeeds for a cell without a waiting room. Leave the EssentialsX
setting in place and apply any arena hover grant after arrival. See the
[EssentialsX configuration][essentials].

## Login and session tracking

### SpiralGenesis allocation

**Code review.** SpiralGenesis skips returning players with an assigned plot
and no placement owed. For an unassigned player or one still owed placement,
its join, authentication or action-gate path can eventually set their respawn
point and teleport them asynchronously to the plot. NinjaArena independently
starts a pending restore one tick after join. Either teleport can run last, so
the player's final position can differ from the earlier plugin's result.
Listener priority alone cannot order asynchronous placement.

Finish first placement before allowing arena admission. Avoid clearing or
reassigning plots for players with pending arena restores. If deployment
requires both operations for the same login, verify their order on a test
backend or use separate backends until there is an explicit handoff. The race
has no live reproduction in the issue notes.

### Login and spawn plugins

**Code review.** AuthMe documents pre-authentication spawn teleports and
optional forced spawn after authentication. If a login plugin cancels the
one-tick restore teleport, NinjaArena keeps the snapshot and leaves the saved
state unapplied. The join path does not retry when authentication finishes on
that connection. A later forced spawn can also override the landing after a
successful restore. A subsequent join can retry a pending snapshot once the
login flow allows the teleport. See [AuthMe's settings][authme].

Keep arena entry behind authentication. Keep arena worlds out of forced-spawn
rules and verify that the login flow leaves the restored destination alone,
including post-login teleports and reconnects from inside the arena. If the
backend cannot guarantee that ordering, complete authentication before the
player reaches the arena backend.

### SessionPulse

**Code review.** The connection listener tracks join and quit by UUID. The
activity listener observes movement and other inputs. Neither path teleports
players, writes inventory or blocks a restore. Arena activity can contribute
to session activity. No NinjaArena-specific exclusion is needed for restore
ordering. Session accounting with both plugins installed was not measured.

## Inventory and block history

### EssentialsX ender chest

**Code review and recorded test-server finding.** EssentialsX `/enderchest`
opens an inventory view directly. NinjaArena's waiting-room and match
lockdown now cancels the inventory-open event, covering the command as well
as ordinary container access ([#63][ender-chest]). Keep the lockdown in force
during both phases. Operators can retain the command for ordinary play.

### Inventory rollback and per-world inventory plugins

**Code review.** NinjaArena stores the pre-entry snapshot by player UUID and
reapplies it after the return teleport. InventoryRollbackPlus can capture
join, leave, death and world changes and lets staff restore those backups.
An arena-era backup may contain a temporary kit or an empty waiting-room
inventory. A separate inventory manager that writes after NinjaArena can
also overwrite the restored inventory. Inventory-open lockdown cannot stop
another plugin from writing directly to a player inventory.

Exclude arena snapshots and automatic inventory swaps that touch arena
transitions where the plugin supports it. Keep rollback writes away from
players with an active or pending arena snapshot. Where filtering is
unavailable, use a separate backend or restrict staff restores to a verified
pre-arena backup after NinjaArena's restore finishes. See
[InventoryRollbackPlus backup permissions][inventory].

### CoreProtect

**Published behavior and recorded live.** CoreProtect supports per-world
logging settings and block rollbacks. The test server logged arena changes,
including resets. Database growth was not measured. Exclude the arena world
from rollbacks and restores, including global operations. If arena history is
unnecessary, configure a per-world logging override in
`plugins/CoreProtect/ninjaarena_arenas.yml` and disable the relevant logging
options there. If logging stays enabled, measure database growth with
representative matches and resets. See [CoreProtect configuration][core-config]
and [commands][core-commands].

## Bedrock and movement

### Geyser-Spigot and Floodgate

**Code review and recorded startup log only.** The test server reported a
Geyser-Spigot `NoSuchFieldException: childHandler` startup message that
Geyser described as safe to ignore. The note identifies it as unrelated to
NinjaArena and does not establish that a Bedrock client played a match.
NinjaArena keys player state and restores by the Bukkit UUID and resolves
invite and accept targets by their exact server-visible name. Floodgate can
admit Bedrock accounts and link them to Java accounts.

Use the server-visible name, including any configured prefix, for invites and
keep the player's identity consistent while an arena snapshot is pending.
Verify portal entry, invite and accept, menus, kit controls, dash, death and
disconnect/rejoin restoration with a Bedrock client on a test backend. See
[Floodgate setup][floodgate].

### LuckPerms with shared MariaDB

**Published configuration and recorded live.** Shared storage makes global
grants visible across servers. Set a distinct LuckPerms server name on each
backend and scope arena grants with `server=<name>`. On the test server, the
LuckPerms response arrived after the RCON connection closed. Confirm the
effective permission in game on the intended backend and on another backend
where the grant should be absent. See [LuckPerms configuration][luckperms].

### Themis and other anti-cheat plugins

**Code review and recorded install inventory.** NinjaArena's dash applies
player velocity. Themis was absent on the test server, so dash setbacks and
false positives are unverified. Test ordinary and repeated dashes with the
installed checks. If a conflict is reproduced, use the narrowest documented
arena or mechanic exemption the anti-cheat supports.

[test]: https://github.com/Ninja6-MC/NinjaArena/issues/28#issuecomment-6450770353
[flight]: https://github.com/Ninja6-MC/NinjaArena/issues/59
[ender-chest]: https://github.com/Ninja6-MC/NinjaArena/issues/63
[lifecycle]: arena-world-lifecycle.md
[world-reset]: https://github.com/Kihsomray/CyberWorldReset
[multiverse]: https://mvplugins.org/core/fundamentals/basic-usage/
[essentials]: https://github.com/EssentialsX/Essentials/blob/2.x/Essentials/src/main/resources/config.yml
[authme]: https://github.com/AuthMe/AuthMeReloaded/blob/master/docs/config.md
[inventory]: https://github.com/TechnicallyCoded/Inventory-Rollback-Plus#permissions
[core-config]: https://docs.coreprotect.net/config/
[core-commands]: https://docs.coreprotect.net/commands/
[floodgate]: https://geysermc.org/wiki/floodgate/setup/
[luckperms]: https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/resources/config.yml
