package com.fennecmomo.maidmorework.lib.projecttype;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectTypeRegistry
{
    private static final List<IProjectType> LIST = new ArrayList<>();
    private static final Map<String, IProjectType> INDEX = new LinkedHashMap<>();

    public static void register(IProjectType type)
    {
        if (INDEX.containsKey(type.id()))
        {
            throw new IllegalArgumentException("Duplicate project type id: " + type.id());
        }
        LIST.add(type);
        INDEX.put(type.id(), type);
    }

    public static IProjectType get(String id)
    {
        return INDEX.get(id);
    }

    public static List<IProjectType> getAll()
    {
        return Collections.unmodifiableList(LIST);
    }

    public static int size()
    {
        return LIST.size();
    }

    public static void discover()
    {
        try
        {
            Class<?> clz = Class.forName("com.google.common.reflect.ClassPath");
            var fromMethod = clz.getMethod("from", ClassLoader.class);
            var getTopLevelClassesRecursive = clz.getMethod("getTopLevelClassesRecursive", String.class);
            var classInfos = (Iterable<?>) getTopLevelClassesRecursive.invoke(
                    fromMethod.invoke(null, ProjectTypeRegistry.class.getClassLoader()),
                    "com.fennecmomo.maidmorework");

            for (Object info : classInfos)
            {
                var getName = info.getClass().getMethod("getName");
                String className = (String) getName.invoke(info);
                discoverClass(className);
            }
        }
        catch (Exception e)
        {
            fallbackDiscover();
        }
    }

    private static void discoverClass(String className)
    {
        try
        {
            Class<?> clazz = Class.forName(className);
            if (Modifier.isAbstract(clazz.getModifiers()) || Modifier.isInterface(clazz.getModifiers()))
                return;
            if (!IProjectType.class.isAssignableFrom(clazz))
                return;
            if (!clazz.isAnnotationPresent(RegProjectType.class))
                return;
            register((IProjectType) clazz.getDeclaredConstructor().newInstance());
        }
        catch (Exception ignored) {}
    }

    private static void fallbackDiscover()
    {
        scanPackage("com.fennecmomo.maidmorework.project.type");
    }

    private static void scanPackage(String packageName)
    {
        try
        {
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = ProjectTypeRegistry.class.getClassLoader().getResources(path);
            while (resources.hasMoreElements())
            {
                URL url = resources.nextElement();
                scanUrl(url, packageName);
            }
        }
        catch (IOException ignored) {}
    }

    private static void scanUrl(URL url, String packageName)
    {
        try
        {
            URI uri = url.toURI();
            if ("file".equals(uri.getScheme()))
            {
                File dir = new File(uri);
                File[] files = dir.listFiles();
                if (files != null)
                {
                    for (File file : files)
                    {
                        String name = file.getName();
                        if (name.endsWith(".class"))
                        {
                            discoverClass(packageName + "." + name.substring(0, name.length() - 6));
                        }
                    }
                }
            }
        }
        catch (URISyntaxException ignored) {}
    }
}
