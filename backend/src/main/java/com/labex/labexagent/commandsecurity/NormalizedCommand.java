package com.labex.labexagent.commandsecurity;

/** Immutable normalized identity for a command intent. */
public record NormalizedCommand(
        String normalizerVersion,
        String canonicalCommand,
        String canonicalWorkingDirectory,
        String displayCommand,
        String digest
) {
}
