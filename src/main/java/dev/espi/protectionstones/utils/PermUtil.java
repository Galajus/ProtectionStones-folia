package dev.espi.protectionstones.utils;

import org.apache.commons.lang3.math.NumberUtils;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Optional;

public class PermUtil {

    /**
     * Getting number variable on end of perm
     * @param player Bukkit player
     * @param searchingPerm permission without number variable on end like "protectionstones.owners-amount."
     * @return perm int variable
     */
    public static int getLimitOwnersFromPermission(Player player, String searchingPerm) {
        Optional<PermissionAttachmentInfo> perm = player.getEffectivePermissions().stream()
                .filter(p -> p.getPermission().startsWith(searchingPerm)).findFirst();
        if (perm.isEmpty()) {
            return 0;
        }
        String[] split = perm.get().getPermission().split("\\.");
        if (split.length < 3) {
            return 0;
        }
        String limitString = split[2];

        if (NumberUtils.isCreatable(limitString)) {
            return Integer.parseInt(limitString);
        }
        return 0;
    }
}
