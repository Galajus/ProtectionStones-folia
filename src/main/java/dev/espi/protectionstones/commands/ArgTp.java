/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.espi.protectionstones.commands;

import dev.espi.protectionstones.PSL;
import dev.espi.protectionstones.PSPlayer;
import dev.espi.protectionstones.PSRegion;
import dev.espi.protectionstones.ProtectionStones;
import dev.espi.protectionstones.utils.ChatUtil;
import dev.espi.protectionstones.utils.UUIDCache;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ArgTp implements PSCommandArg {

    private static HashMap<UUID, Integer> waitCounter = new HashMap<>();
    private static HashMap<UUID, ScheduledTask> taskCounter = new HashMap<>();

    // /ps tp, /ps home

    @Override
    public List<String> getNames() {
        return Collections.singletonList("tp");
    }

    @Override
    public boolean allowNonPlayersToExecute() {
        return false;
    }

    @Override
    public List<String> getPermissionsToExecute() {
        return Collections.singletonList("protectionstones.tp");
    }

    @Override
    public HashMap<String, Boolean> getRegisteredFlags() {
        return null;
    }

    @Override
    public boolean executeArgument(CommandSender s, String[] args, HashMap<String, String> flags) {
        Player p = (Player) s;

        // preliminary checks
        if (!p.hasPermission("protectionstones.tp"))
            return PSL.msg(p, PSL.NO_PERMISSION_TP.msg());

        if (args.length < 2 || args.length > 3)
            return PSL.msg(p, PSL.TP_HELP.msg());

        if (args.length == 2) { // /ps tp [name/id]
            Bukkit.getAsyncScheduler().runNow(ProtectionStones.getInstance(), (task) -> {
                // get regions from the query
                List<PSRegion> regions = ProtectionStones.getPSRegions(p.getWorld(), args[1]);

                if (regions.isEmpty()) {
                    PSL.msg(s, PSL.REGION_DOES_NOT_EXIST.msg());
                    return;
                }
                if (regions.size() > 1) {
                    ChatUtil.displayDuplicateRegionAliases(p, regions);
                    return;
                }
                teleportPlayer(p, regions.get(0));
            });
        } else { // /ps tp [player] [num]
            // get the region id the player wants to teleport to
            int regionNumber;
            try {
                regionNumber = Integer.parseInt(args[2]);
                if (regionNumber <= 0) {
                    return PSL.msg(p, PSL.NUMBER_ABOVE_ZERO.msg());
                }
            } catch (NumberFormatException e) {
                return PSL.msg(p, PSL.TP_VALID_NUMBER.msg());
            }

            String tpName = args[1];
            // region checks, and set lp to offline player
            if (!UUIDCache.containsName(tpName)) {
                return PSL.msg(p, PSL.PLAYER_NOT_FOUND.msg());
            }
            UUID tpUuid = UUIDCache.getUUIDFromName(tpName);

            // run region search asynchronously to avoid blocking server thread
            Bukkit.getAsyncScheduler().runNow(ProtectionStones.getInstance(), (task) -> {
                List<PSRegion> regions = PSPlayer.fromUUID(tpUuid).getPSRegionsCrossWorld(p.getWorld(), false);

                // check if region was found
                if (regions.isEmpty()) {
                    PSL.msg(p, PSL.REGION_NOT_FOUND_FOR_PLAYER.msg()
                            .replace("%player%", tpName));
                    return;
                } else if (regionNumber > regions.size()) {
                    PSL.msg(p, PSL.ONLY_HAS_REGIONS.msg()
                            .replace("%player%", tpName)
                            .replace("%num%", "" + regions.size()));
                    return;
                }

                teleportPlayer(p, regions.get(regionNumber - 1));
            });
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return null;
    }

    static void teleportPlayer(Player p, PSRegion r) {
        if (r.getTypeOptions() == null) {
            PSL.msg(p, ChatColor.RED + "This region is problematic, and the block type (" + r.getType() + ") is not configured. Please contact an administrator.");
            Bukkit.getLogger().info(ChatColor.RED + "This region is problematic, and the block type (" + r.getType() + ") is not configured.");
            return;
        }

        // teleport player
        if (r.getTypeOptions().tpWaitingSeconds == 0 || p.hasPermission("protectionstones.tp.bypasswait")) {
            // no teleport delay
            PSL.msg(p, PSL.TPING.msg());
            p.getScheduler().run(ProtectionStones.getInstance(), (task) -> p.teleportAsync(r.getHome()), null); // run on main thread, not async
        } else if (!r.getTypeOptions().noMovingWhenTeleportWaiting) {
            // teleport delay, but doesn't care about moving
            p.sendMessage(PSL.TP_IN_SECONDS.msg().replace("%seconds%", "" + r.getTypeOptions().tpWaitingSeconds));

            Bukkit.getAsyncScheduler().runDelayed(ProtectionStones.getInstance(), (task) -> {
                PSL.msg(p, PSL.TPING.msg());
                p.teleport(r.getHome());
            }, 20L * r.getTypeOptions().tpWaitingSeconds * 50, TimeUnit.MILLISECONDS);

        } else {// delay and not allowed to move
            PSL.msg(p, PSL.TP_IN_SECONDS.msg().replace("%seconds%", "" + r.getTypeOptions().tpWaitingSeconds));
            Location l = p.getLocation().clone();
            UUID uuid = p.getUniqueId();

            // remove queued teleport if already running
            if (taskCounter.get(uuid) != null) removeUUIDTimer(uuid);

            // add teleport wait tasks to queue
            waitCounter.put(uuid, 0);
            taskCounter.put(uuid, Bukkit.getGlobalRegionScheduler().runAtFixedRate(ProtectionStones.getInstance(), (task) -> {
                        Player pl = Bukkit.getPlayer(uuid);
                        // cancel if the player is not on the server
                        if (pl == null) {
                            removeUUIDTimer(uuid);
                            return;
                        }

                        if (waitCounter.get(uuid) == null) {
                            removeUUIDTimer(uuid);
                            return;
                        }
                        // increment seconds
                        waitCounter.put(uuid, waitCounter.get(uuid) + 1);

                        ProtectionStones.getInstance().debug(String.format("Checking player movement. Player location: (%.2f, %.2f, %.2f), actual location: (%.2f, %.2f, %.2f)", pl.getLocation().getX(), pl.getLocation().getY(), pl.getLocation().getZ(), l.getX(), l.getY(), l.getZ()));

                        // if the player moved cancel it
                        if (!inThreshold(l.getX(), pl.getLocation().getX()) || !inThreshold(l.getY(), pl.getLocation().getY()) || !inThreshold(l.getZ(), pl.getLocation().getZ())) {
                            ProtectionStones.getInstance().debug(String.format("Not in threshold. X check: %s, Y check: %s, Z check: %s", inThreshold(l.getX(), pl.getLocation().getX()), inThreshold(l.getY(), pl.getLocation().getY()), inThreshold(l.getZ(), pl.getLocation().getZ())));
                            PSL.msg(pl, PSL.TP_CANCELLED_MOVED.msg());
                            removeUUIDTimer(uuid);
                        } else if (waitCounter.get(uuid) == r.getTypeOptions().tpWaitingSeconds * 4) { // * 4 since this loops 4 times a second
                            // if the timer has passed, teleport and cancel
                            PSL.msg(pl, PSL.TPING.msg());
                            pl.getScheduler().run(ProtectionStones.getInstance(), t -> pl.teleportAsync(r.getHome()), null);
                            removeUUIDTimer(uuid);
                        }
                    }, 5, 5) // loop 4 times a second
            );
        }
    }

    private static boolean inThreshold(double location, double playerLoc) {
        return playerLoc <= location + 1.0 && playerLoc >= location - 1.0;
    }

    private static void removeUUIDTimer(UUID uuid) {
        if (taskCounter.get(uuid) != null) taskCounter.get(uuid).cancel();
        waitCounter.remove(uuid);
        taskCounter.remove(uuid);
    }
}
