package com.vylorq.anticheat.platform;

import com.vylorq.anticheat.Ac;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Optional Floodgate bridge (section 4), done with reflection so the mod works (Java-only) when Geyser/Floodgate
 * are not installed.
 */
public final class Floodgate {
    private static Object api;
    private static boolean checked;

    private Floodgate() {
    }

    private static Object api() {
        if (!checked) {
            checked = true;
            if (FabricLoader.getInstance().isModLoaded("floodgate")) {
                try {
                    Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    api = c.getMethod("getInstance").invoke(null);
                    Ac.LOG.info("Floodgate found: Bedrock players will be detected.");
                } catch (Throwable t) {
                    Ac.LOG.warn("Floodgate is installed but its API could not be loaded: {}", t.toString());
                }
            }
        }
        return api;
    }

    public static boolean available() {
        return api() != null;
    }

    public static boolean isBedrock(UUID id) {
        Object a = api();
        if (a == null) {
            return false;
        }
        try {
            return (boolean) a.getClass().getMethod("isFloodgatePlayer", UUID.class).invoke(a, id);
        } catch (Throwable t) {
            // Floodgate UUIDs start with 0s in the high bits.
            return id.getMostSignificantBits() == 0;
        }
    }

    /**
     * Shows a Bedrock form with text boxes. Calls {@code onResult} with the answers (on Floodgate's thread: the
     * caller must hop back to the server thread), or {@code null} if closed.
     *
     * @return false when forms aren't available (caller falls back to chat)
     */
    public static boolean askText(UUID player, String title, List<String> labels, Consumer<String[]> onResult) {
        Object a = api();
        if (a == null) {
            return false;
        }
        try {
            Class<?> customForm = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Object builder = customForm.getMethod("builder").invoke(null);
            Class<?> bc = builder.getClass();
            builder = find(bc, "title", String.class).invoke(builder, title);
            for (String l : labels) {
                builder = find(builder.getClass(), "input", String.class, String.class).invoke(builder, l, "");
            }
            Class<?> responseType = Class.forName("org.geysermc.cumulus.response.CustomFormResponse");
            Object handler = Proxy.newProxyInstance(Floodgate.class.getClassLoader(), new Class<?>[]{Consumer.class},
                    (InvocationHandler) (proxy, method, args) -> {
                        if (method.getName().equals("accept") && args != null && args.length == 1) {
                            Object resp = args[0];
                            String[] out = new String[labels.size()];
                            for (int i = 0; i < labels.size(); i++) {
                                Object v = responseType.getMethod("asInput", int.class).invoke(resp, i);
                                out[i] = v == null ? "" : v.toString();
                            }
                            onResult.accept(out);
                            return null;
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        return null;
                    });
            builder = find(builder.getClass(), "validResultHandler", Consumer.class).invoke(builder, handler);
            Object form = find(builder.getClass(), "build").invoke(builder);
            Class<?> formClass = Class.forName("org.geysermc.cumulus.form.Form");
            Object ok = a.getClass().getMethod("sendForm", UUID.class, formClass).invoke(a, player, form);
            return !(ok instanceof Boolean b) || b;
        } catch (Throwable t) {
            Ac.LOG.warn("Could not show a Bedrock form: {}", t.toString());
            return false;
        }
    }

    private static Method find(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params.length) {
                Class<?>[] t = m.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < t.length; i++) {
                    if (!t[i].isAssignableFrom(params[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }
}
