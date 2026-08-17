# ADR 0030: Use shared packet tracks with portable fallback

DreamingRecall V2 stores Minecraft clientbound packet frames as the high-fidelity playback source. A single shared world track is combined with per-player server tracks and optional per-player client telemetry tracks. Existing semantic records remain a portable fallback and are not the primary renderer input.

Compatible playback decodes packet frames through Minecraft's own protocol codecs and packet listeners. Missing or incompatible mod namespaces are isolated and replaced from portable records where possible. The Minecraft version must match.
