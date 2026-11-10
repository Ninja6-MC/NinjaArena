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

**Code review and simulated.** SpiralGenesis skips returning players with an assigned plot
and no placement owed. For an unassigned player or one still owed placement,
its join, authentication or action-gate path can eventually set their respawn
point and teleport them asynchronously to the plot. NinjaArena independently
starts a pending restore one tick after join. Listener priority alone cannot
order asynchronous placement, so NinjaArena defines the order itself:

1. A placement that lands before the restore is overwritten by it. The player ends
   at the restored location with the restored inventory.
2. Any other plugin's teleport that lands within 100 ticks after a successful
   restore is undone once: a tick later the player is moved back to the spot they
   were restored to. This is not limited to plot placement; a spawn or hub command
   from another plugin in the window is reverted too. The inventory is not written
   again. Only plugin-caused and unknown-cause teleports count, and a second one in
   the window is left to win. Teleports NinjaArena starts itself (admission, portal
   entry, waiting room, match, restore) are marked and never undone, and nothing is
   undone for a player in a match, waiting room, hub or spectating.
3. A placement later than that, and the plot assignment and respawn point the
   plugin sets, are not touched.

This is tested with a simulated competing teleport only. It has not been
checked against SpiralGenesis on a test server, so whether its placement lands
inside this window, and whether it also teleports on later events, is open
(#106). Finish first placement before allowing arena admission and avoid
clearing or reassigning plots for players with pending arena restores.

### Login and spawn plugins

**Code review and simulated.** AuthMe documents pre-authentication spawn teleports and
optional forced spawn after authentication. If a login plugin cancels the
one-tick restore teleport, NinjaArena keeps the snapshot and tries again
after 20, 100, 200, 400 and 600 ticks (six attempts in about 66 seconds,
counting the first), so a restore runs once authentication has let the
teleport through. Every attempt writes the snapshot over the player, so a retry
does not duplicate items, and the snapshot is deleted only after one succeeds.
When the last attempt fails the player is told to rejoin or ask staff, and the
snapshot stays for their next join. Quitting stops the retries. A player who
authenticates later than that window is restored on their next join. A player
who joins dead is restored when they respawn, which is not retried, shows no
failure message and has no settle window. A later
forced spawn after a successful restore is handled like any other competing
teleport (see SpiralGenesis above). Tracked in #105. See [AuthMe's settings][authme].
This is tested with a simulated cancelling teleport, not with AuthMe.

Keep arena entry behind authentication. Keep arena worlds out of forced-spawn
rules and verify that the login flow leaves the restored destination alone,
including post-login teleports and reconnects from inside the arena. If the
backend cannot guarantee that ordering, complete authentication before the
player reaches the arena backend.

### SessionPulse

**Code review.** The connection listener tracks join and quit by UUID. The
activity listener observes movement and other inputs. Neither path teleports
players or writes inventory. Arena activity can contribute to session
activity. With enforcement enabled, a SessionPulse cooldown can deny login
before NinjaArena's join listener runs. The snapshot stays pending until
the player can join again. Leave the cooldown in force and verify session
accounting and reconnect recovery on a test backend.

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
