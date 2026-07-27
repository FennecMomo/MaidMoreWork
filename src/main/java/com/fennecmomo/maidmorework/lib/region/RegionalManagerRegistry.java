package com.fennecmomo.maidmorework.lib.region;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RegionalManagerRegistry
{
    private static final Map<UUID, IRegionalManager> MANAGERS = new ConcurrentHashMap<>();

    private RegionalManagerRegistry() {}

    public static void register(IRegionalManager manager)
    {
        MANAGERS.put(manager.getId(), manager);
    }

    public static void unregister(UUID id)
    {
        MANAGERS.remove(id);
    }

    public static IRegionalManager get(UUID id)
    {
        return MANAGERS.get(id);
    }
}
