package com.hhy.dreamingrecall.capture;

public final class BinaryPayloads {
    private BinaryPayloads() {
    }

    public static byte[] chunkCoordinates(int chunkX, int chunkZ) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(1);
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
        });
    }

    public static byte[] blockPosition(long packedPosition) {
        return BinaryPayloadWriter.encode(output -> {
            output.writeInt(1);
            output.writeLong(packedPosition);
        });
    }
}
