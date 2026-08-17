# ADR 0030: Use per-player packet tracks with a global portable fallback

DreamingRecall stores the server-observable world as a global semantic track in every server archive. A client that also has DreamingRecall installed may upload the bounded Minecraft clientbound stream it actually received. The server assigns that stream to `player/client/<uuid>` from the authenticated connection and combines it with that player's visual samples and optional camera samples in the same archive.

Dedicated-server archives open on the global semantic track. Selecting a player with a complete client packet track creates an isolated Minecraft packet replay session for that player; selecting a player without one continues on the semantic renderer. Single-player and client-only archives can use their local packet track immediately. A sequence break, missing bootstrap, bounded-memory overflow, archive backpressure, or shutdown timeout marks only the affected player track incomplete and prevents it from being hard-played.

Compatible playback decodes vanilla packet frames through Minecraft's own protocol codecs and packet listeners. Unknown mod namespaces are never executed implicitly; versioned replay extensions may opt in to them, while portable records and placeholders remain the compatibility path. The Minecraft version must match.
