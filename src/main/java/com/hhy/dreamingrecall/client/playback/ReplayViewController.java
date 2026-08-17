package com.hhy.dreamingrecall.client.playback;

import com.hhy.dreamingrecall.director.CameraPose;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplayViewController extends AutoCloseable {
    ReplayWorldController.CameraMode cameraMode();

    void setCameraMode(ReplayWorldController.CameraMode mode);

    void attachPlayer(UUID playerId, boolean firstPerson);

    List<ReplayWorldController.PlayerTarget> playerTargets();

    Optional<ReplayWorldController.PlayerTarget> attachedPlayerTarget();

    List<String> dimensions();

    String activeDimension();

    void selectDimension(String dimensionId);

    void moveFreeCamera(double forward, double strafe, double vertical, boolean fast, double elapsedSeconds);

    void turnFreeCamera(float deltaYaw, float deltaPitch);

    CameraPose cameraPose();

    void applyDirectorPose(CameraPose pose);

    void notifySeekStarted();

    @Override
    void close();
}
