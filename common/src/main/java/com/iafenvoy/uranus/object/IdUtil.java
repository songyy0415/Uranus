package com.iafenvoy.uranus.object;

import net.minecraft.resources.Identifier;

public class IdUtil {
    public static String build(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path).toString();
    }
}
