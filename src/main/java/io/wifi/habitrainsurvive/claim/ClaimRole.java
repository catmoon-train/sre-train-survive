package io.wifi.habitrainsurvive.claim;

public enum ClaimRole {
    OWNER(4),
    ADMIN(3),
    TRUSTED(2),
    CONTAINER_TRUST(1),
    ACCESS_TRUST(0),
    BANNED(-1);

    private final int power;

    ClaimRole(int power) {
        this.power = power;
    }

    public int getPower() {
        return power;
    }
}
