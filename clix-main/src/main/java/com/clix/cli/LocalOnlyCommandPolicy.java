package com.clix.cli;

/** Public CLI surface for the current local-first release. Remote entry points are deferred. */
public final class LocalOnlyCommandPolicy {
    private LocalOnlyCommandPolicy() { }

    public static boolean isDeferredRemoteCommand(String[] args) {
        if (args == null || args.length == 0) return false;
        String first = args[0] == null ? "" : args[0].trim();
        if ("remote".equalsIgnoreCase(first) || "login".equalsIgnoreCase(first)) return true;
        return "serve".equalsIgnoreCase(first) && args.length >= 2
                && "--remote".equalsIgnoreCase(args[1]);
    }
}
